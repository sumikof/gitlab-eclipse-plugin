package com.gitlab.eclipse.lsp.edit

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.eclipse.core.filebuffers.IFileBufferStatusCodes
import org.eclipse.core.filebuffers.ITextFileBuffer
import org.eclipse.core.filebuffers.LocationKind
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.filesystem.IFileInfo
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.resources.IResourceStatus
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Path
import org.eclipse.core.runtime.Status
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.IDocument
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.text.undo.IDocumentUndoManager
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.texteditor.IDocumentProvider
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.UnsupportedCharsetException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val FILE_URI = "file:///tmp/project/a.txt"

/** Replaces the first character of "hello" with "J": the document reads "Jello" afterwards. */
private val EDIT_FIRST_CHAR = edit(0, 0, 0, 1, "J")

private fun edit(startLine: Int, startChar: Int, endLine: Int, endChar: Int, text: String) =
  TextEdit(Range(Position(startLine, startChar), Position(endLine, endChar)), text)

private fun documentEdit(uri: String, edits: List<Either<TextEdit, SnippetTextEdit>>) =
  TextDocumentEdit(VersionedTextDocumentIdentifier(uri, null), edits)

private fun paramsOf(uri: String = FILE_URI, vararg edits: TextEdit): ApplyWorkspaceEditParams {
  val change = documentEdit(uri, edits.map { Either.forLeft<TextEdit, SnippetTextEdit>(it) })
  return ApplyWorkspaceEditParams(WorkspaceEdit(listOf(Either.forLeft<TextDocumentEdit, ResourceOperation>(change))))
}

/** The rejection `commitFileBufferContent` throws before writing when the file changed on disk. */
private fun outOfSync() =
  CoreException(
    Status(IStatus.WARNING, "org.eclipse.core.filebuffers", IResourceStatus.OUT_OF_SYNC_LOCAL, "out of sync", null),
  )

/** A failure that can happen after the content is on disk (annotation model commit, stamp revert). */
private fun postWriteFailure() =
  CoreException(Status(IStatus.ERROR, "org.eclipse.core.resources", "annotation model commit failed"))

private fun CompletableFuture<ApplyWorkspaceEditResponse>.applied(): Boolean = get(5, TimeUnit.SECONDS).isApplied

/**
 * Every seam the applier has, driven by hand: UI runnables and timeouts are queued and released
 * by the test, so no test waits on wall-clock time.
 */
private class Harness(
  target: EditTarget?,
  val access: BufferAccess = mockk(relaxUnitFun = true),
  val undoManager: IDocumentUndoManager? = mockk(relaxUnitFun = true),
  val probeBuffer: ITextFileBuffer? = null,
  onUiThread: ((Runnable) -> Unit)? = null,
  scheduleTimeout: ((Duration, Runnable) -> Unit)? = null,
  notifyUser: ((Notice) -> Unit)? = null,
) {
  val ui = ArrayDeque<Runnable>()
  val timeouts = ArrayDeque<Runnable>()
  val notices = mutableListOf<Notice>()
  var bufferAccessRequests = 0
  val resolver: EditTargetResolver = mockk<EditTargetResolver>().also { every { it.resolve(any()) } returns target }

  val applier = WorkspaceEditApplier(
    onUiThread = onUiThread ?: { ui.addLast(it) },
    scheduleTimeout = scheduleTimeout ?: { _, runnable -> timeouts.addLast(runnable) },
    resolver = resolver,
    bufferAccessOf = {
      bufferAccessRequests++
      access
    },
    undoManagerFor = { undoManager },
    bufferOf = { probeBuffer },
    notifyUser = notifyUser ?: { notices += it },
  )

  fun runUi() {
    while (ui.isNotEmpty()) ui.removeFirst().run()
  }

  fun fireTimeout() {
    while (timeouts.isNotEmpty()) timeouts.removeFirst().run()
  }
}

/** Route 1: an editor is open; its provider hands out the document and saves it. */
private class OpenEditorFixture(text: String = "hello") {
  val document: IDocument = Document(text)
  val input = mockk<IEditorInput>()
  val provider = mockk<IDocumentProvider>(relaxUnitFun = true).also { every { it.getDocument(input) } returns document }
  val target = EditTarget.OpenEditor(input, provider)

  fun saveFailsWith(failure: Throwable) {
    every { provider.saveDocument(any(), input, document, false) } throws failure
  }
}

/** Route 2: no editor; the buffer manager's buffer carries the document. */
private class BufferedFixture(text: String = "hello") {
  val document: IDocument = Document(text)
  val buffer = mockk<ITextFileBuffer>(relaxUnitFun = true).also { every { it.document } returns document }
  val access = mockk<BufferAccess>(relaxUnitFun = true).also { every { it.current() } returns buffer }
  val target = EditTarget.Buffered.ByPath(Path.fromOSString("/project/a.txt"), LocationKind.IFILE)

  fun commitFailsWith(failure: Throwable) {
    every { buffer.commit(any(), false) } throws failure
  }

  /** The disconnect discarded the buffer: the second `current()` — the exit observation — is null. */
  fun goneAfterDisconnect() {
    every { access.current() } returns buffer andThen null
  }
}

/** A buffer whose file store answers the persistence probe with [onDisk], or with a failure. */
private fun probeBuffer(encoding: String?, exists: Boolean = true, onDisk: ByteArray? = null): ITextFileBuffer {
  val info = mockk<IFileInfo>().also { every { it.exists() } returns exists }
  val store = mockk<IFileStore>().also {
    every { it.fetchInfo() } returns info
    if (onDisk == null) {
      every { it.openInputStream(EFS.NONE, any()) } throws IOException("unreadable")
    } else {
      every { it.openInputStream(EFS.NONE, any()) } returns ByteArrayInputStream(onDisk)
    }
  }
  return mockk<ITextFileBuffer>().also {
    every { it.fileStore } returns store
    every { it.encoding } returns encoding
  }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private val UTF_8_BOM = bytes(0xEF, 0xBB, 0xBF)
private val UTF_16LE_BOM = bytes(0xFF, 0xFE)

class WorkspaceEditApplierTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("response exactly once, and the state machine") {
    it("timeout settles first, then the runnable runs: the document is not modified, the answer stays false") {
      val fixture = OpenEditorFixture()
      val harness = Harness(fixture.target)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.fireTimeout()
      future.applied() shouldBe false
      harness.runUi()

      fixture.document.get() shouldBe "hello"
      future.applied() shouldBe false
      verify { harness.resolver wasNot Called }
      harness.notices.shouldBeEmpty()
    }

    it("runnable is RUNNING when the timeout fires: the timeout does not answer, the runnable does") {
      val fixture = OpenEditorFixture()
      val harness = Harness(fixture.target)
      lateinit var future: CompletableFuture<ApplyWorkspaceEditResponse>
      var doneAfterTimeoutMidSave: Boolean? = null
      every { fixture.provider.saveDocument(any(), fixture.input, fixture.document, false) } answers {
        harness.fireTimeout()
        doneAfterTimeoutMidSave = future.isDone
      }

      future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      doneAfterTimeoutMidSave shouldBe false
      future.applied() shouldBe true // the timeout's answer would have been false
      harness.timeouts.shouldBeEmpty()
    }

    it("a long save keeps the future uncompleted while RUNNING") {
      val fixture = OpenEditorFixture()
      val harness = Harness(fixture.target)
      lateinit var future: CompletableFuture<ApplyWorkspaceEditResponse>
      var doneMidSave: Boolean? = null
      every { fixture.provider.saveDocument(any(), fixture.input, fixture.document, false) } answers {
        doneMidSave = future.isDone
      }

      future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      future.isDone shouldBe false
      harness.runUi()

      doneMidSave shouldBe false
      future.applied() shouldBe true
    }

    it("scheduleTimeout throws after registration: false at once, and the runnable later modifies nothing") {
      val fixture = OpenEditorFixture()
      val harness = Harness(
        fixture.target,
        scheduleTimeout = { _, _ -> throw RejectedExecutionException("scheduler is down") },
      )

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))

      future.applied() shouldBe false
      harness.ui.size shouldBe 1 // registered before the timeout was attempted
      harness.runUi()
      fixture.document.get() shouldBe "hello"
      verify { harness.resolver wasNot Called }
    }

    it("scheduleTimeout throws while the runnable is already RUNNING: the setup failure does not answer") {
      val fixture = OpenEditorFixture()
      val reachedRunning = CountDownLatch(1)
      val release = CountDownLatch(1)
      var reached = false
      val harness = Harness(
        fixture.target,
        onUiThread = { runnable ->
          thread(name = "fake-ui", isDaemon = true) { runnable.run() }
          reached = reachedRunning.await(5, TimeUnit.SECONDS)
        },
        scheduleTimeout = { _, _ -> throw RejectedExecutionException("scheduler is down") },
      )
      every { harness.resolver.resolve(any()) } answers {
        reachedRunning.countDown()
        release.await(5, TimeUnit.SECONDS)
        fixture.target
      }

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      val doneWhileRunning = future.isDone
      release.countDown()

      reached shouldBe true
      doneWhileRunning shouldBe false
      future.applied() shouldBe true // the setup failure's answer would have been false
      fixture.document.get() shouldBe "Jello"
    }

    it("endCompoundChange throws after the edit: still exactly one answer, decided by the rules") {
      val fixture = OpenEditorFixture()
      val undoManager = mockk<IDocumentUndoManager>(relaxUnitFun = true)
      every { undoManager.endCompoundChange() } throws IllegalStateException("undo history closed")
      val harness = Harness(fixture.target, undoManager = undoManager)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      fixture.document.get() shouldBe "Jello"
      future.applied() shouldBe true // the editor holds the edit; the save never ran
      harness.notices shouldContainExactly listOf(Notice.SAVE_MANUALLY)
      verify(exactly = 0) { fixture.provider.saveDocument(any(), any(), any(), any()) }
    }

    it("disconnect throws after a successful commit: the answer is still true and nothing is notified") {
      val fixture = BufferedFixture()
      every { fixture.access.disconnect(any()) } throws CoreException(Status.error("disconnect failed"))
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      harness.notices.shouldBeEmpty()
    }

    it("notifyUser throws: the future is already completed and nothing escapes the runnable") {
      val fixture = OpenEditorFixture()
      fixture.saveFailsWith(outOfSync())
      val attempted = mutableListOf<Notice>()
      lateinit var future: CompletableFuture<ApplyWorkspaceEditResponse>
      var doneWhenNotified: Boolean? = null
      val harness = Harness(
        fixture.target,
        notifyUser = {
          attempted += it
          doneWhenNotified = future.isDone // the response must be settled before the notification
          error("popup failed")
        },
      )

      future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      shouldNotThrowAny { harness.runUi() }

      doneWhenNotified shouldBe true
      future.applied() shouldBe true
      attempted shouldContainExactly listOf(Notice.SAVE_MANUALLY)
    }

    it("the exit observation throws: the future still completes, and committed decides the value") {
      val fixture = BufferedFixture()
      fixture.commitFailsWith(outOfSync())
      every { fixture.access.current() } returns fixture.buffer andThenThrows IllegalStateException("manager gone")
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      shouldNotThrowAny { harness.runUi() }

      future.applied() shouldBe false
      harness.notices shouldContainExactly listOf(Notice.STALE_BUFFER_UNKNOWN)
    }

    it("the exit observation is not consulted at all when the save succeeded") {
      val fixture = BufferedFixture()
      every { fixture.access.current() } returns fixture.buffer andThenThrows IllegalStateException("manager gone")
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      harness.notices.shouldBeEmpty()
      verify(exactly = 1) { fixture.access.current() }
    }
  }

  describe("routing") {
    it("OpenEditor: edits go to the provider's document and the save is the provider's, overwrite = false") {
      val fixture = OpenEditorFixture()
      val harness = Harness(fixture.target)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      fixture.document.get() shouldBe "Jello"
      verify(exactly = 1) { fixture.provider.saveDocument(any(), fixture.input, fixture.document, false) }
      verify(exactly = 0) { fixture.provider.saveDocument(any(), any(), any(), true) }
      harness.bufferAccessRequests shouldBe 0
      verify { harness.access wasNot Called }
    }

    it("OpenEditor: the edit is one compound undo change") {
      val fixture = OpenEditorFixture()
      val undoManager = mockk<IDocumentUndoManager>(relaxUnitFun = true)
      val harness = Harness(fixture.target, undoManager = undoManager)

      harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      verifyOrder {
        undoManager.beginCompoundChange()
        undoManager.endCompoundChange()
      }
    }

    it("Buffered: connect, current, commit(overwrite = false), disconnect") {
      val fixture = BufferedFixture()
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      fixture.document.get() shouldBe "Jello"
      verifyOrder {
        fixture.access.connect(any())
        fixture.access.current()
        fixture.buffer.commit(any(), false)
        fixture.access.disconnect(any())
      }
      verify(exactly = 0) { fixture.buffer.commit(any(), true) }
      harness.bufferAccessRequests shouldBe 1
    }

    it("Buffered: disconnect is reached even when the save throws") {
      val fixture = BufferedFixture()
      fixture.commitFailsWith(outOfSync())
      fixture.goneAfterDisconnect()
      val harness = Harness(fixture.target, access = fixture.access)

      harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      verify(exactly = 1) { fixture.access.disconnect(any()) }
    }

    it("resolution returns null: false, nothing modified") {
      val harness = Harness(target = null)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe false
      harness.bufferAccessRequests shouldBe 0
      harness.notices.shouldBeEmpty()
    }

    it("getDocument returns null: false") {
      val fixture = OpenEditorFixture()
      every { fixture.provider.getDocument(fixture.input) } returns null
      val harness = Harness(fixture.target)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe false
      verify(exactly = 0) { fixture.provider.saveDocument(any(), any(), any(), any()) }
      harness.notices.shouldBeEmpty()
    }
  }

  describe("response value") {
    it("save succeeds: true, no notification") {
      val fixture = BufferedFixture()
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      harness.notices.shouldBeEmpty()
    }

    it("save fails, OpenEditor: true, edit kept in the editor, exactly one 'save manually' notification") {
      val fixture = OpenEditorFixture()
      fixture.saveFailsWith(outOfSync())
      val harness = Harness(fixture.target)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe true
      fixture.document.get() shouldBe "Jello"
      harness.notices shouldContainExactly listOf(Notice.SAVE_MANUALLY)
    }

    it("save fails, Buffered, buffer gone after disconnect: false, no notification") {
      val fixture = BufferedFixture()
      fixture.commitFailsWith(outOfSync())
      fixture.goneAfterDisconnect()
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe false
      harness.notices.shouldBeEmpty()
    }

    it("save fails, Buffered, a different buffer instance is registered afterwards: false, no notification") {
      val fixture = BufferedFixture()
      fixture.commitFailsWith(outOfSync())
      every { fixture.access.current() } returns fixture.buffer andThen mockk<ITextFileBuffer>()
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe false
      harness.notices.shouldBeEmpty()
    }

    it("save fails, Buffered, the same buffer instance still registered: false plus one 'stale buffer' notice") {
      val fixture = BufferedFixture()
      fixture.commitFailsWith(outOfSync())
      val harness = Harness(fixture.target, access = fixture.access)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      future.applied() shouldBe false
      harness.notices shouldContainExactly listOf(Notice.STALE_BUFFER)
    }

    it("applyEdits throws after mutating, Buffered, same buffer still registered: false plus 'stale buffer'") {
      val fixture = BufferedFixture()
      val undoManager = mockk<IDocumentUndoManager>(relaxUnitFun = true)
      every { undoManager.endCompoundChange() } throws IllegalStateException("undo history closed")
      val harness = Harness(fixture.target, access = fixture.access, undoManager = undoManager)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
      harness.runUi()

      fixture.document.get() shouldBe "Jello"
      future.applied() shouldBe false
      harness.notices shouldContainExactly listOf(Notice.STALE_BUFFER)
      verify(exactly = 0) { fixture.buffer.commit(any(), any()) }
      verify(exactly = 1) { fixture.access.disconnect(any()) }
    }

    it("apply throws something other than MalformedTreeException: treated as mutated, so the editor rule applies") {
      val partial = object : Document("hello world") {
        private var replacements = 0

        override fun replace(pos: Int, length: Int, text: String?) {
          if (replacements++ == 1) error("listener failed mid-apply")
          super.replace(pos, length, text)
        }
      }
      val fixture = OpenEditorFixture()
      every { fixture.provider.getDocument(fixture.input) } returns partial
      val harness = Harness(fixture.target)

      val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(edit(0, 0, 0, 1, "J"), edit(0, 6, 0, 7, "W"))))
      harness.runUi()

      future.applied() shouldBe true
      harness.notices shouldContainExactly listOf(Notice.SAVE_MANUALLY)
      verify(exactly = 0) { fixture.provider.saveDocument(any(), any(), any(), any()) }
    }

    describe("pre-apply failures: false, nothing modified, no notification") {
      it("range outside the document") {
        val fixture = OpenEditorFixture()
        val harness = Harness(fixture.target)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(edit(0, 0, 0, 99, "J"))))
        harness.runUi()

        future.applied() shouldBe false
        fixture.document.get() shouldBe "hello"
        harness.notices.shouldBeEmpty()
        verify(exactly = 0) { fixture.provider.saveDocument(any(), any(), any(), any()) }
      }

      it("overlapping edits") {
        val fixture = OpenEditorFixture()
        val harness = Harness(fixture.target)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(edit(0, 0, 0, 3, "A"), edit(0, 2, 0, 4, "B"))))
        harness.runUi()

        future.applied() shouldBe false
        fixture.document.get() shouldBe "hello"
        harness.notices.shouldBeEmpty()
      }

      it("connect throws") {
        val fixture = BufferedFixture()
        every { fixture.access.connect(any()) } throws CoreException(Status.error("cannot connect"))
        val harness = Harness(fixture.target, access = fixture.access)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
        harness.runUi()

        future.applied() shouldBe false
        fixture.document.get() shouldBe "hello"
        harness.notices.shouldBeEmpty()
        verify(exactly = 0) { fixture.access.disconnect(any()) }
      }
    }
  }

  describe("persistence probe") {
    val document: IDocument = Document("Jello")
    val expected = "Jello".toByteArray(Charsets.UTF_8)

    it("out-of-sync CoreException: NOT_MATCHED without touching the disk") {
      var probed = false
      val bufferFor = {
        probed = true
        null
      }
      persistedDespiteFailure(bufferFor, document, outOfSync()) shouldBe Persistence.NOT_MATCHED
      probed shouldBe false
    }

    it("a CoreException with the same code from another plugin is not the out-of-sync rejection") {
      val other = CoreException(Status(IStatus.WARNING, "org.eclipse.core.resources", 274, "not filebuffers", null))
      persistedDespiteFailure({ probeBuffer("UTF-8", onDisk = expected) }, document, other) shouldBe Persistence.MATCHED
    }

    it("charset-mapping CoreException (filebuffers, CHARSET_MAPPING_FAILED): NOT_MATCHED without touching the disk") {
      val mapping = CoreException(
        Status(IStatus.ERROR, "org.eclipse.core.filebuffers", IFileBufferStatusCodes.CHARSET_MAPPING_FAILED, "x", null),
      )
      var probed = false
      val bufferFor = {
        probed = true
        null
      }
      persistedDespiteFailure(bufferFor, document, mapping) shouldBe Persistence.NOT_MATCHED
      probed shouldBe false

      val otherPlugin = CoreException(
        Status(IStatus.ERROR, "org.eclipse.core.resources", IFileBufferStatusCodes.CHARSET_MAPPING_FAILED, "x", null),
      )
      persistedDespiteFailure({ probeBuffer("UTF-8", onDisk = expected) }, document, otherPlugin)
        .shouldBe(Persistence.MATCHED)
    }

    it("charset-construction CoreException: NOT_MATCHED") {
      fun wrapped(cause: Throwable) =
        CoreException(Status(IStatus.ERROR, "org.eclipse.core.filebuffers", 0, "x", cause))
      val unsupported = wrapped(UnsupportedCharsetException("x"))
      val illegal = wrapped(IllegalCharsetNameException("x"))
      persistedDespiteFailure({ null }, document, unsupported) shouldBe Persistence.NOT_MATCHED
      persistedDespiteFailure({ null }, document, illegal) shouldBe Persistence.NOT_MATCHED
    }

    it("target missing: NOT_MATCHED") {
      val buffer = probeBuffer("UTF-8", exists = false, onDisk = expected)
      persistedDespiteFailure({ buffer }, document, postWriteFailure()) shouldBe Persistence.NOT_MATCHED
    }

    it("disk bytes equal expected: MATCHED") {
      val buffer = probeBuffer("UTF-8", onDisk = expected)
      persistedDespiteFailure({ buffer }, document, postWriteFailure()) shouldBe Persistence.MATCHED
    }

    it("disk bytes equal BOM + expected: MATCHED") {
      val buffer = probeBuffer("UTF-8", onDisk = UTF_8_BOM + expected)
      persistedDespiteFailure({ buffer }, document, postWriteFailure()) shouldBe Persistence.MATCHED
    }

    it("content itself begins with U+FEFF after the BOM: MATCHED, and without the BOM: MATCHED") {
      val withFeff: IDocument = Document("\uFEFFJello")
      val encoded = "\uFEFFJello".toByteArray(Charsets.UTF_8)
      persistedDespiteFailure({ probeBuffer("UTF-8", onDisk = UTF_8_BOM + encoded) }, withFeff, postWriteFailure())
        .shouldBe(Persistence.MATCHED)
      persistedDespiteFailure({ probeBuffer("UTF-8", onDisk = encoded) }, withFeff, postWriteFailure())
        .shouldBe(Persistence.MATCHED)
    }

    it("UTF-16 with the LE BOM is compared as UTF-16LE, the way the commit writes it") {
      val little = "Jello".toByteArray(Charsets.UTF_16LE)
      persistedDespiteFailure({ probeBuffer("UTF-16", onDisk = UTF_16LE_BOM + little) }, document, postWriteFailure())
        .shouldBe(Persistence.MATCHED)
      val javaDefault = "Jello".toByteArray(Charsets.UTF_16) // Java's own BOM, big-endian
      persistedDespiteFailure({ probeBuffer("UTF-16", onDisk = javaDefault) }, document, postWriteFailure())
        .shouldBe(Persistence.MATCHED)
    }

    it("bytes differ: NOT_MATCHED") {
      val buffer = probeBuffer("UTF-8", onDisk = "hello".toByteArray(Charsets.UTF_8))
      persistedDespiteFailure({ buffer }, document, postWriteFailure()) shouldBe Persistence.NOT_MATCHED
    }

    it("the probe itself fails: UNKNOWN") {
      persistedDespiteFailure({ probeBuffer("UTF-8", onDisk = null) }, document, postWriteFailure())
        .shouldBe(Persistence.UNKNOWN)
      persistedDespiteFailure({ throw IllegalStateException("no manager") }, document, postWriteFailure())
        .shouldBe(Persistence.UNKNOWN)
      persistedDespiteFailure({ null }, document, postWriteFailure()) shouldBe Persistence.UNKNOWN
      persistedDespiteFailure({ probeBuffer(encoding = null, onDisk = expected) }, document, postWriteFailure())
        .shouldBe(Persistence.UNKNOWN)
    }

    it("expectedDiskContents offers no BOM candidate for encodings the commit never prefixes") {
      expectedDiskContents("Jello", "ISO-8859-1").size shouldBe 1
      expectedDiskContents("Jello", "UTF-16BE").size shouldBe 1
    }

    describe("through the applier") {
      it("MATCHED after a post-write failure: true and no notification, even with no editor and the buffer gone") {
        val fixture = BufferedFixture()
        fixture.commitFailsWith(postWriteFailure())
        fixture.goneAfterDisconnect()
        every { fixture.buffer.fileStore } returns probeBuffer("UTF-8", onDisk = expected).fileStore
        every { fixture.buffer.encoding } returns "UTF-8"
        val harness = Harness(fixture.target, access = fixture.access)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
        harness.runUi()

        future.applied() shouldBe true
        harness.notices.shouldBeEmpty()
      }

      it("UNKNOWN after a post-write failure: true plus one 'result unknown' notification") {
        val fixture = BufferedFixture()
        fixture.commitFailsWith(postWriteFailure())
        fixture.goneAfterDisconnect()
        every { fixture.buffer.fileStore } returns probeBuffer("UTF-8", onDisk = null).fileStore
        every { fixture.buffer.encoding } returns "UTF-8"
        val harness = Harness(fixture.target, access = fixture.access)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
        harness.runUi()

        future.applied() shouldBe true
        harness.notices shouldContainExactly listOf(Notice.SAVE_RESULT_UNKNOWN)
      }

      it("NOT_MATCHED after a post-write failure: the routing rules decide") {
        val fixture = BufferedFixture()
        fixture.commitFailsWith(postWriteFailure())
        fixture.goneAfterDisconnect()
        every { fixture.buffer.fileStore } returns probeBuffer("UTF-8", onDisk = "hello".toByteArray()).fileStore
        every { fixture.buffer.encoding } returns "UTF-8"
        val harness = Harness(fixture.target, access = fixture.access)

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
        harness.runUi()

        future.applied() shouldBe false
        harness.notices.shouldBeEmpty()
      }

      it("OpenEditor: the probe looks the buffer up by document") {
        val fixture = OpenEditorFixture()
        fixture.saveFailsWith(postWriteFailure())
        val harness = Harness(fixture.target, probeBuffer = probeBuffer("UTF-8", onDisk = expected))

        val future = harness.applier.applyEdit(paramsOf(edits = arrayOf(EDIT_FIRST_CHAR)))
        harness.runUi()

        future.applied() shouldBe true
        harness.notices.shouldBeEmpty()
      }
    }
  }

  describe("pre-validation: false without entering the state machine") {
    fun rejects(params: ApplyWorkspaceEditParams) {
      val harness = Harness(OpenEditorFixture().target)
      val future = harness.applier.applyEdit(params)
      future.isDone shouldBe true
      future.applied() shouldBe false
      harness.ui.shouldBeEmpty()
      harness.timeouts.shouldBeEmpty()
      verify { harness.resolver wasNot Called }
    }

    it("a `changes`-only edit") {
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(mapOf(FILE_URI to listOf(EDIT_FIRST_CHAR)))))
    }

    it("no edit at all") {
      rejects(ApplyWorkspaceEditParams())
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(emptyList())))
    }

    it("a resource operation") {
      val create = Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile(FILE_URI))
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(listOf(create))))
    }

    it("a snippet edit") {
      val snippet = Either.forRight<TextEdit, SnippetTextEdit>(SnippetTextEdit())
      val change = documentEdit(FILE_URI, listOf(Either.forLeft(EDIT_FIRST_CHAR), snippet))
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(listOf(Either.forLeft(change)))))
    }

    it("more than one TextDocumentEdit") {
      val change = documentEdit(FILE_URI, listOf(Either.forLeft(EDIT_FIRST_CHAR)))
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(listOf(Either.forLeft(change), Either.forLeft(change)))))
    }

    it("a URI that is not file:") {
      rejects(paramsOf("http://example.com/a.txt", EDIT_FIRST_CHAR))
    }

    it("a URI that does not parse") {
      rejects(paramsOf("file:///tmp/a b.txt", EDIT_FIRST_CHAR))
    }

    it("a missing URI") {
      val change = TextDocumentEdit(VersionedTextDocumentIdentifier(), listOf(Either.forLeft(EDIT_FIRST_CHAR)))
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(listOf(Either.forLeft(change)))))
    }

    it("a text edit without a range") {
      val change = documentEdit(FILE_URI, listOf(Either.forLeft(TextEdit())))
      rejects(ApplyWorkspaceEditParams(WorkspaceEdit(listOf(Either.forLeft(change)))))
    }

    it("accepts a file: URI whose scheme is upper-case") {
      val harness = Harness(OpenEditorFixture().target)
      harness.applier.applyEdit(paramsOf("FILE:///tmp/project/a.txt", EDIT_FIRST_CHAR))
      harness.ui.size shouldBe 1
    }
  }
})
