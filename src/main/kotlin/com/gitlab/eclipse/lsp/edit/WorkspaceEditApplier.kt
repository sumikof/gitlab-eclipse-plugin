package com.gitlab.eclipse.lsp.edit

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.filebuffers.FileBuffers
import org.eclipse.core.filebuffers.ITextFileBuffer
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.resources.IResourceStatus
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.core.runtime.content.IContentDescription
import org.eclipse.jface.text.IDocument
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.SnippetTextEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.text.edits.MalformedTreeException
import org.eclipse.text.edits.MultiTextEdit
import org.eclipse.text.undo.DocumentUndoManagerRegistry
import org.eclipse.text.undo.IDocumentUndoManager
import java.net.URI
import java.net.URISyntaxException
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.IllegalCharsetNameException
import java.nio.charset.StandardCharsets
import java.nio.charset.UnsupportedCharsetException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val log by lazy { logger<WorkspaceEditApplier>() }

private const val START_TIMEOUT_SECONDS = 10L

/** How long a request may wait for the UI thread before it is declined, unapplied. */
private val START_TIMEOUT: Duration = Duration.ofSeconds(START_TIMEOUT_SECONDS)

/**
 * The `overwrite` flag of every save. `false` keeps Eclipse's out-of-sync check, so a file changed
 * on disk since the buffer was read makes the save fail (and the rules in [WorkspaceEditApplier]
 * decide) instead of being silently overwritten with stale content.
 */
private const val OVERWRITE = false

/**
 * Applies a `workspace/applyEdit` request to the document the user is looking at, or declines it.
 *
 * The language server treats `applied:false` as "the client declined" and then writes the file
 * itself. A wrong `false` is therefore not a no-op: the server re-applies the same
 * original-coordinate edits to content that may already contain them, and the file is corrupted.
 * Every rule in this class follows from that asymmetry — **corruption outranks a lost edit** —
 * and from the requirement that exactly one response is sent, across the lsp4j dispatch thread
 * (which calls [applyEdit]) and the SWT UI thread (which runs the edit).
 *
 * The two threads are reconciled by one state machine per request, [ResponseSlot]:
 *
 * ```
 *   PENDING --(UI runnable wins the CAS)--> RUNNING --(response decided)--> SETTLED
 *   PENDING --(timeout or setup failure wins the CAS)--> SETTLED           never from RUNNING
 * ```
 *
 * with the invariants the spec pins:
 *
 *  - I1 only the runnable that won `PENDING -> RUNNING` may touch a document
 *  - I2 once RUNNING, neither the timeout nor a setup failure may answer
 *  - I3 only the transition into SETTLED completes the future
 *  - I4 the RUNNING owner settles on every path, including exceptions
 *  - I5 `applied` equals "the edit survives after the runnable exits"
 *
 * Nothing here is verifiable on a real workbench in a headless build, so every platform touch is
 * a constructor seam with a production default, and the spec drives the seams.
 *
 * @property onUiThread posts a runnable to the UI thread. Never `syncExec`: it deadlocks against
 *   the lsp4j dispatch thread.
 * @property scheduleTimeout fires a runnable after a delay without blocking the caller. The
 *   default is a shared daemon scheduler ([scheduleOnSharedTimer]); it must be independent of the
 *   UI thread because its whole job is to answer when the UI thread cannot be reached.
 * @property resolver decides the target document; UI-thread-only because it walks the workbench
 * @property bufferAccessOf binds a [EditTarget.Buffered] to the file buffer manager
 * @property undoManagerFor the undo manager that groups the edit into one undoable change, if any
 * @property bufferOf the file buffer managing a document, used only to probe whether a failed save
 *   nevertheless wrote the file
 * @property notifyUser shows a [Notice] to the user; called on the UI thread after the response
 */
class WorkspaceEditApplier(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val scheduleTimeout: (Duration, Runnable) -> Unit = ::scheduleOnSharedTimer,
  private val resolver: EditTargetResolver = EditTargetResolver(),
  private val bufferAccessOf: (EditTarget.Buffered) -> BufferAccess = {
    bufferAccessFor(it, FileBuffers.getTextFileBufferManager())
  },
  private val undoManagerFor: (IDocument) -> IDocumentUndoManager? =
    DocumentUndoManagerRegistry::getDocumentUndoManager,
  private val bufferOf: (IDocument) -> ITextFileBuffer? = {
    FileBuffers.getTextFileBufferManager().getTextFileBuffer(it)
  },
  private val notifyUser: (Notice) -> Unit = { NotificationUtils.show(it.message) },
) {
  /**
   * Entry point, on the lsp4j dispatch thread. Never throws and never blocks.
   *
   * Pre-validation answers `applied:false` straight away without entering the state machine —
   * nothing has been touched, so declining is safe. It checks URI *syntax* only: resolving the
   * target needs the workbench and happens on the UI thread.
   */
  fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
    val request = try {
      requestOf(params)
    } catch (t: Throwable) {
      log.warn("workspace/applyEdit rejected: ${t.javaClass.name}")
      null
    }
    if (request == null) return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(false))
    return EditRun(request).arm()
  }

  /**
   * One request: armed on the dispatch thread, run on the UI thread.
   *
   * The fields below are written and read by the UI runnable only. The only state shared across
   * threads is [response].
   */
  private inner class EditRun(private val request: EditRequest) : Runnable {
    private val response = ResponseSlot()
    private val monitor = NullProgressMonitor()

    private var documentMutated = false
    private var committed = false
    private var persistence: Persistence? = null
    private var target: EditTarget? = null
    private var editedBuffer: ITextFileBuffer? = null
    private var access: BufferAccess? = null

    /**
     * Registers the runnable **before** scheduling the timeout. If [scheduleTimeout] then throws,
     * the `settleIfPending(false)` below either wins the CAS (and the queued runnable no-ops) or
     * loses it (because the runnable is already RUNNING and will answer) — exactly one response
     * either way. The reverse order would leave a failed registration waiting the full timeout.
     *
     * `orTimeout` is deliberately not used: it completes the future without passing through the
     * state machine, so a queued runnable could still apply the edit after the server was told
     * `false`.
     */
    fun arm(): CompletableFuture<ApplyWorkspaceEditResponse> {
      try {
        onUiThread(this)
        scheduleTimeout(
          START_TIMEOUT,
          Runnable {
            if (response.settleIfPending(applied = false)) {
              log.info("workspace/applyEdit declined: the UI thread was not reached before the start timeout")
            }
          },
        )
      } catch (t: Throwable) {
        log.warn("workspace/applyEdit setup failed: ${t.javaClass.name}")
        response.settleIfPending(applied = false)
      }
      return response.future
    }

    /** UI thread. The outer catch only logs: the response is decided in [exit], on every path. */
    override fun run() {
      if (!response.claimRunning()) return // I1: the timeout or a setup failure already answered
      try {
        attempt()
      } catch (t: Throwable) {
        log.warn("workspace/applyEdit failed: ${t.javaClass.name}")
      } finally {
        exit()
      }
    }

    private fun attempt() {
      val resolved = resolver.resolve(request.uri) ?: throw UnresolvableTargetException()
      target = resolved
      when (resolved) {
        is EditTarget.OpenEditor -> editOpenEditor(resolved)
        is EditTarget.Buffered -> editBuffered(resolved)
      }
    }

    /** Route 1: the editor's own document, saved through the editor's own provider. */
    private fun editOpenEditor(editor: EditTarget.OpenEditor) {
      val document = editor.provider.getDocument(editor.input) ?: throw UnresolvableTargetException()
      applyEdits(document)
      saveVia(document, bufferFor = { bufferOf(document) }) {
        editor.provider.saveDocument(monitor, editor.input, document, OVERWRITE)
      }
    }

    /** Route 2: a file buffer, connected and disconnected in pairs — including on failure. */
    private fun editBuffered(buffered: EditTarget.Buffered) {
      val bufferAccess = bufferAccessOf(buffered)
      access = bufferAccess
      bufferAccess.connect(monitor)
      try {
        val buffer = bufferAccess.current() ?: throw UnresolvableTargetException()
        val document = buffer.document
        applyEdits(document)
        editedBuffer = buffer
        saveVia(document, bufferFor = { buffer }) { buffer.commit(monitor, OVERWRITE) }
      } finally {
        bufferAccess.disconnect(monitor)
      }
    }

    /**
     * Converts, then applies as one [MultiTextEdit] inside one compound undo change.
     *
     * Range checking happens in the conversion, before the document is touched. Overlaps are
     * rejected by `MultiTextEdit.addChild` (not by `apply`), and `apply`'s own integrity pass runs
     * before any change — so a [MalformedTreeException] from either leaves the document intact.
     * Anything else out of `apply` is treated as a possible partial modification:
     * `TextEditProcessor.performEdits` has no rollback, and "modified but reported `false`" is the
     * double-apply corruption, while "unmodified but reported `true`" merely loses an edit.
     */
    private fun applyEdits(document: IDocument) {
      val edits = LspTextEditConverter.toReplaceEdits(document, request.edits)
      val undoManager = undoManagerFor(document)
      undoManager?.beginCompoundChange()
      try {
        try {
          val tree = MultiTextEdit()
          edits.forEach(tree::addChild)
          tree.apply(document)
          documentMutated = true
        } catch (e: MalformedTreeException) {
          throw e // thrown before the document changes; documentMutated stays false
        } catch (t: Throwable) {
          documentMutated = true // pessimistic: a partial apply is possible
          throw t
        }
      } finally {
        undoManager?.endCompoundChange()
      }
    }

    /**
     * A save can write the bytes and *then* throw, so a failed [save] is not taken as "not
     * written": [persistedDespiteFailure] decides, and only a definite `NOT_MATCHED` rethrows.
     */
    private fun saveVia(document: IDocument, bufferFor: () -> ITextFileBuffer?, save: () -> Unit) {
      try {
        save()
        committed = true
        persistence = Persistence.MATCHED
      } catch (t: Throwable) {
        log.warn("workspace/applyEdit save failed: ${t.javaClass.name}")
        val probed = persistedDespiteFailure(bufferFor, document, t)
        persistence = probed
        committed = probed != Persistence.NOT_MATCHED // UNKNOWN counts as written
        if (!committed) throw t
      }
    }

    /**
     * Decides and sends the response, then notifies — in that order, each contained on its own.
     *
     * An exception inside a `finally` abandons the rest of the block, so notifying first would let
     * a failing notification strand the future in RUNNING, where the timeout can no longer answer
     * either (I4).
     */
    private fun exit() {
      var retained = committed // saved => no observation needed at all
      var notice: Notice? = if (persistence == Persistence.UNKNOWN) Notice.SAVE_RESULT_UNKNOWN else null
      try {
        if (!committed && documentMutated) {
          val observed = observeRetention()
          retained = observed.first
          notice = observed.second
        }
      } catch (t: Throwable) {
        log.warn("workspace/applyEdit exit observation failed: ${t.javaClass.name}")
        retained = committed // cannot observe => do not claim retention
        notice = if (documentMutated) Notice.STALE_BUFFER_UNKNOWN else notice
      } finally {
        try {
          response.settleFromRunning(applied = retained)
        } catch (t: Throwable) {
          log.error("workspace/applyEdit response failed: ${t.javaClass.name}")
        }
        try {
          notice?.let(notifyUser)
        } catch (t: Throwable) {
          log.warn("workspace/applyEdit notification failed: ${t.javaClass.name}")
        }
      }
    }

    /**
     * Whether an applied-but-unsaved edit survives this runnable, and what to tell the user.
     *
     * An open editor holds its document, so the edit stays visible and saveable there. Without an
     * editor the disconnect discards the buffer — unless the same instance is still registered, in
     * which case an unsaved copy lingers and the user should know.
     */
    private fun observeRetention(): Pair<Boolean, Notice?> {
      if (target is EditTarget.OpenEditor) return true to Notice.SAVE_MANUALLY
      val edited = editedBuffer ?: return false to null
      val lingering = access?.current() === edited
      return false to if (lingering) Notice.STALE_BUFFER else null
    }
  }
}

/** What the user is told when an edit was applied but its fate needs their attention. No paths. */
enum class Notice(val message: String) {
  SAVE_MANUALLY(
    "GitLab Duo changed an open editor but could not save it. Save the editor to keep the change.",
  ),
  STALE_BUFFER(
    "GitLab Duo changed a file but could not save it, and an unsaved copy may remain. " +
      "Open the file to review, save or discard it.",
  ),
  STALE_BUFFER_UNKNOWN(
    "GitLab Duo changed a file but could not save it, and could not check whether an unsaved copy " +
      "remains. Open the file to review it.",
  ),
  SAVE_RESULT_UNKNOWN(
    "GitLab Duo changed a file but could not confirm that the save reached the disk. Check the file.",
  ),
}

/** Verdict of [persistedDespiteFailure]. Three values on purpose: only [UNKNOWN] notifies. */
internal enum class Persistence { MATCHED, NOT_MATCHED, UNKNOWN }

/** The response state machine; the only object two threads share. */
private class ResponseSlot {
  private enum class State { PENDING, RUNNING, SETTLED }

  private val state = AtomicReference(State.PENDING)
  val future = CompletableFuture<ApplyWorkspaceEditResponse>()

  /** `PENDING -> RUNNING`; the caller may touch a document only if this returns `true`. */
  fun claimRunning(): Boolean = state.compareAndSet(State.PENDING, State.RUNNING)

  /** `PENDING -> SETTLED` with [applied], or nothing at all if the request is RUNNING or SETTLED. */
  fun settleIfPending(applied: Boolean): Boolean {
    if (!state.compareAndSet(State.PENDING, State.SETTLED)) return false
    future.complete(ApplyWorkspaceEditResponse(applied))
    return true
  }

  /** `RUNNING -> SETTLED`; called by the RUNNING owner exactly once. */
  fun settleFromRunning(applied: Boolean) {
    state.set(State.SETTLED)
    future.complete(ApplyWorkspaceEditResponse(applied))
  }
}

/** A pre-validated request: one `file:` URI and its plain text edits. */
private class EditRequest(val uri: URI, val edits: List<TextEdit>)

private class UnresolvableTargetException : RuntimeException()

/**
 * Pre-validation, syntax only. `null` means decline without entering the state machine.
 *
 * Exactly one `TextDocumentEdit` is accepted: the server sends one per request, and a request
 * naming several documents has no single target for the state machine to own, so it is declined
 * unapplied rather than half-applied.
 */
private fun requestOf(params: ApplyWorkspaceEditParams): EditRequest? {
  val changes = params.edit?.documentChanges
  if (changes.isNullOrEmpty()) {
    log.info("workspace/applyEdit rejected: no documentChanges")
    return null
  }
  if (changes.size != 1) {
    log.warn("workspace/applyEdit rejected: ${changes.size} documentChanges, expected 1")
    return null
  }
  val change = changes.single()
  if (change == null || !change.isLeft) {
    log.warn("workspace/applyEdit rejected: resource operation")
    return null
  }
  val edits = plainEditsOf(change.left.edits) ?: return null
  val uri = fileUriOf(change.left.textDocument?.uri) ?: return null
  return EditRequest(uri, edits)
}

private fun plainEditsOf(edits: List<Either<TextEdit, SnippetTextEdit>?>?): List<TextEdit>? {
  if (edits == null) {
    log.warn("workspace/applyEdit rejected: edits absent")
    return null
  }
  if (edits.any { it == null || !it.isLeft }) {
    log.warn("workspace/applyEdit rejected: snippet edit")
    return null
  }
  val plain = edits.filterNotNull().map { it.left }
  if (plain.any { it.range?.start == null || it.range?.end == null || it.newText == null }) {
    log.warn("workspace/applyEdit rejected: incomplete text edit")
    return null
  }
  return plain
}

/** The URI as a parsed `file:` URI, or `null`. Logs the scheme at most — never the URI. */
private fun fileUriOf(raw: String?): URI? {
  if (raw == null) {
    log.warn("workspace/applyEdit rejected: uri absent")
    return null
  }
  val uri = try {
    URI(raw)
  } catch (e: URISyntaxException) {
    log.warn("workspace/applyEdit rejected: ${e.javaClass.name}")
    return null
  }
  if (!"file".equals(uri.scheme, ignoreCase = true)) {
    log.warn("workspace/applyEdit rejected: scheme ${uri.scheme ?: "<none>"}")
    return null
  }
  return uri
}

/**
 * Whether a save that threw [thrown] nevertheless wrote the document — four stages, three values.
 *
 * `ResourceTextFileBuffer.commitFileBufferContent` calls `IFile.setContents`, then
 * `revertModificationStamp`, then `IPersistableAnnotationModel.commit`; the last two can throw
 * after the content is on disk, and answering "not written" there is exactly the double-apply
 * corruption. So:
 *
 *  1. a failure known to precede the write -> `NOT_MATCHED` (no double-apply risk, so no tilt)
 *  2. the file no longer exists -> `NOT_MATCHED` (nothing can have been persisted)
 *  3. bytes on disk equal one of the two byte strings the commit could have written -> `MATCHED`,
 *     else `NOT_MATCHED`
 *  4. undeterminable -> `UNKNOWN`, the only stage that tilts toward "written"
 */
internal fun persistedDespiteFailure(
  bufferFor: () -> ITextFileBuffer?,
  document: IDocument,
  thrown: Throwable,
): Persistence {
  if (isKnownPreWriteFailure(thrown)) return Persistence.NOT_MATCHED
  return try {
    val buffer = bufferFor() ?: return Persistence.UNKNOWN
    val store = buffer.fileStore ?: return Persistence.UNKNOWN
    if (!store.fetchInfo().exists()) return Persistence.NOT_MATCHED
    val encoding = buffer.encoding ?: return Persistence.UNKNOWN
    val onDisk = store.openInputStream(EFS.NONE, NullProgressMonitor()).use { it.readAllBytes() }
    val written = expectedDiskContents(document.get(), encoding).any { it.contentEquals(onDisk) }
    if (written) Persistence.MATCHED else Persistence.NOT_MATCHED
  } catch (t: Throwable) {
    log.warn("workspace/applyEdit save probe failed: ${t.javaClass.name}")
    Persistence.UNKNOWN
  }
}

/**
 * Stage 1: the two failures `commitFileBufferContent` raises before it writes anything.
 *
 * Both verified against `org.eclipse.core.filebuffers` 3.8.500 bytecode: the out-of-sync
 * rejection is `Status(WARNING, "org.eclipse.core.filebuffers", 274, ..)` thrown at the top of the
 * method, and a `Charset.forName` failure is wrapped as a `CoreException` whose status exception —
 * which `CoreException.getCause()` returns — is the charset exception.
 */
private fun isKnownPreWriteFailure(thrown: Throwable): Boolean {
  if (thrown !is CoreException) return false
  val status = thrown.status
  val outOfSync =
    status.plugin == "org.eclipse.core.filebuffers" && status.code == IResourceStatus.OUT_OF_SYNC_LOCAL
  val cause = thrown.cause
  return outOfSync || cause is UnsupportedCharsetException || cause is IllegalCharsetNameException
}

/**
 * The byte strings a commit of [text] under [encoding] can produce, compared as bytes.
 *
 * The commit writes `SequenceInputStream(ByteArrayInputStream(fBOM), encode(document.get()))`,
 * where `fBOM` is the encoding's BOM or absent — so "with BOM" and "without" are exactly the two
 * candidates, and the content is written verbatim (no line-delimiter translation). Java's
 * `"UTF-16"` encoder emits its own BOM, which is why the commit substitutes `"UTF-16LE"` when the
 * stored BOM is the little-endian one; the same substitution is applied here. Comparing bytes,
 * rather than decoding and stripping a `U+FEFF`, keeps a file whose content genuinely begins with
 * `U+FEFF` after its BOM from being misread as "not written".
 */
internal fun expectedDiskContents(text: String, encoding: String): List<ByteArray> {
  val charset = Charset.forName(encoding)
  val encoded = encodeAsCommitDoes(text, charset)
  return when (charset) {
    StandardCharsets.UTF_8 -> listOf(encoded, IContentDescription.BOM_UTF_8 + encoded)
    StandardCharsets.UTF_16LE -> listOf(encoded, IContentDescription.BOM_UTF_16LE + encoded)
    StandardCharsets.UTF_16 ->
      listOf(encoded, IContentDescription.BOM_UTF_16LE + encodeAsCommitDoes(text, StandardCharsets.UTF_16LE))
    else -> listOf(encoded)
  }
}

/** The commit's encoder configuration: malformed input replaced, unmappable characters reported. */
private fun encodeAsCommitDoes(text: String, charset: Charset): ByteArray {
  val bytes = charset.newEncoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .encode(CharBuffer.wrap(text))
  return ByteArray(bytes.remaining()).also(bytes::get)
}

/**
 * Production default for [WorkspaceEditApplier.scheduleTimeout]: one shared daemon thread.
 *
 * Not `Display.timerExec`, which needs the very UI thread whose absence the timeout exists to
 * cover; not the common pool, whose threads may be blocked by unrelated work. `schedule` returns
 * immediately, so the dispatch thread is never held.
 */
private val startTimeoutScheduler: ScheduledExecutorService by lazy {
  Executors.newSingleThreadScheduledExecutor { runnable ->
    Thread(runnable, "gitlab-applyEdit-start-timeout").apply { isDaemon = true }
  }
}

private fun scheduleOnSharedTimer(delay: Duration, task: Runnable) {
  startTimeoutScheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS)
}
