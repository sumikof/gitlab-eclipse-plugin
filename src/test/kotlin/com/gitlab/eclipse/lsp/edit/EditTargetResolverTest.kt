package com.gitlab.eclipse.lsp.edit

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.eclipse.core.filebuffers.ITextFileBuffer
import org.eclipse.core.filebuffers.ITextFileBufferManager
import org.eclipse.core.filebuffers.LocationKind
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.resources.IContainer
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.core.runtime.Path
import org.eclipse.jface.text.IDocument
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor
import java.net.URI

private val TARGET = URI("file:///tmp/project/a.txt")

/** The physical path [TARGET] converts to: `NORMALIZE` and `LOCATION` keys use this. */
private val PHYSICAL: IPath = Path.fromOSString("/tmp/project/a.txt")

/** A workspace-relative `IFile.getFullPath()`, which is what the `IFILE` key uses instead. */
private val FULL_PATH: IPath = Path.fromOSString("/project/a.txt")

/** A second workspace path for the same file, as a linked resource in another project gives. */
private val LINKED_FULL_PATH: IPath = Path.fromOSString("/other/linked-a.txt")

/** A manager that answers "no buffer" to every probe, so each test opts exactly one in. */
private fun emptyManager(): ITextFileBufferManager =
  mockk<ITextFileBufferManager>().also {
    every { it.getTextFileBuffer(any<IPath>(), any()) } returns null
    every { it.getFileStoreTextFileBuffer(any()) } returns null
  }

private fun workspaceFile(fullPath: IPath): IFile =
  mockk<IFile>().also { every { it.fullPath } returns fullPath }

class EditTargetResolverTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val store = mockk<IFileStore>()

  beforeTest {
    // The resolver calls EFS.getStore directly, exactly as TextFileDocumentProvider does. Stubbing
    // it to succeed everywhere also keeps the rejection tests honest: a target that is refused is
    // refused on its own merits, not because no store could be built.
    mockkStatic(EFS::class)
    every { EFS.getStore(any()) } returns store
  }

  // Symmetric with the beforeTest above: a static mock left in place outlives this spec's JVM
  // slot, and a later spec that never mentions EFS would then fail for a reason it cannot see.
  afterTest { unmockkStatic(EFS::class) }

  fun resolver(
    manager: ITextFileBufferManager,
    candidates: Array<IFile> = emptyArray(),
  ) = EditTargetResolver(
    findOpenEditorFor = { null },
    findFilesForLocationURI = { candidates },
    bufferManager = { manager },
  )

  describe("resolve, editor route") {
    it("returns the editor's own input and provider") {
      val input = mockk<IEditorInput>()
      val provider = mockk<IDocumentProvider>()
      val resolved = EditTargetResolver(
        findOpenEditorFor = { input to provider },
        findFilesForLocationURI = { emptyArray() },
        bufferManager = { emptyManager() },
      ).resolve(TARGET)

      resolved shouldBe EditTarget.OpenEditor(input, provider)
    }

    it("consults neither the workspace nor the buffer manager once an editor matches") {
      val input = mockk<IEditorInput>()
      val provider = mockk<IDocumentProvider>()
      val findFiles = mockk<(URI) -> Array<IFile>>()
      val bufferManager = mockk<() -> ITextFileBufferManager>()
      val resolver = EditTargetResolver(
        findOpenEditorFor = { input to provider },
        findFilesForLocationURI = findFiles,
        bufferManager = bufferManager,
      )

      resolver.resolve(TARGET) shouldBe EditTarget.OpenEditor(input, provider)

      // Rule 1 beats rule 2 with no fallback: classification must never run for an open editor,
      // because its buffer key follows from its input type and not from the URI.
      verify(exactly = 0) { findFiles(any()) }
      verify(exactly = 0) { bufferManager() }
    }
  }

  describe("matchedEditor, the walk's per-editor step") {
    /** An input that adapts to an `IFile` at [locationUri], which is how rule 1 matches. */
    fun fileInput(locationUri: URI): IEditorInput {
      val file = mockk<IFile>()
      every { file.locationURI } returns locationUri
      return mockk<IEditorInput>().also { every { it.getAdapter(IFile::class.java) } returns file }
    }

    it("pairs the adapted text editor's own input with its provider, not the outer part's") {
      // A multi-page editor: the outer part's input names the container, the nested text editor
      // holds its own input, and the provider was connected under that one alone.
      val outerInput = fileInput(TARGET)
      val nestedInput = fileInput(TARGET)
      val document = mockk<IDocument>()
      val provider = mockk<IDocumentProvider>()
      every { provider.getDocument(nestedInput) } returns document
      every { provider.getDocument(outerInput) } returns null
      val textEditor = mockk<ITextEditor>()
      every { textEditor.editorInput } returns nestedInput
      every { textEditor.documentProvider } returns provider
      val editor = mockk<IEditorPart>()
      every { editor.editorInput } returns outerInput
      every { editor.getAdapter(ITextEditor::class.java) } returns textEditor
      val reference = mockk<IEditorReference>()
      every { reference.getEditor(false) } returns editor

      val matched = matchedEditor(reference, TARGET)

      matched shouldBe (nestedInput to provider)
      // The pair has to work as a pair: this is the call the applier makes, and the outer input
      // against this provider answers null, which refuses the edit and lets the server write the
      // file behind a dirty editor over it.
      provider.getDocument(matched?.first) shouldBe document
    }

    it("skips an editor that adapts to no text editor") {
      val editor = mockk<IEditorPart>()
      every { editor.getAdapter(ITextEditor::class.java) } returns null
      val reference = mockk<IEditorReference>()
      every { reference.getEditor(false) } returns editor

      matchedEditor(reference, TARGET).shouldBeNull()
    }

    it("treats an unrestored reference as not open") {
      val reference = mockk<IEditorReference>()
      every { reference.getEditor(false) } returns null

      matchedEditor(reference, TARGET).shouldBeNull()
    }
  }

  describe("resolve, joining an existing buffer") {
    it("joins a workspace-keyed buffer, even under a candidate that is not the first") {
      val manager = emptyManager()
      // Two projects can link the same file. Only the second one holds a buffer, so the probe and
      // the classification below disagree about which full path to use -- which is what makes this
      // a test of the probe rather than of the fallback.
      every { manager.getTextFileBuffer(LINKED_FULL_PATH, LocationKind.IFILE) } returns mockk<ITextFileBuffer>()
      val candidates = arrayOf(workspaceFile(FULL_PATH), workspaceFile(LINKED_FULL_PATH))

      resolver(manager, candidates).resolve(TARGET) shouldBe
        EditTarget.Buffered.ByPath(LINKED_FULL_PATH, LocationKind.IFILE)
    }

    it("joins a NORMALIZE-keyed buffer under the physical path, keeping the kind") {
      val manager = emptyManager()
      every { manager.getTextFileBuffer(PHYSICAL, LocationKind.NORMALIZE) } returns mockk<ITextFileBuffer>()

      val resolved = resolver(manager, arrayOf(workspaceFile(FULL_PATH))).resolve(TARGET)

      // The kind is asserted on its own: carrying IFILE here would connect under a key that does
      // not hold this buffer and so create a second buffer over the same file.
      resolved shouldBe EditTarget.Buffered.ByPath(PHYSICAL, LocationKind.NORMALIZE)
      (resolved as EditTarget.Buffered.ByPath).kind shouldBe LocationKind.NORMALIZE
    }

    it("joins a LOCATION-keyed buffer under the physical path, keeping the kind") {
      val manager = emptyManager()
      every { manager.getTextFileBuffer(PHYSICAL, LocationKind.LOCATION) } returns mockk<ITextFileBuffer>()

      val resolved = resolver(manager, arrayOf(workspaceFile(FULL_PATH))).resolve(TARGET)

      resolved shouldBe EditTarget.Buffered.ByPath(PHYSICAL, LocationKind.LOCATION)
      (resolved as EditTarget.Buffered.ByPath).kind shouldBe LocationKind.LOCATION
    }

    it("joins a file-store-keyed buffer even when the file is in the workspace") {
      val manager = emptyManager()
      every { manager.getFileStoreTextFileBuffer(store) } returns mockk<ITextFileBuffer>()

      resolver(manager, arrayOf(workspaceFile(FULL_PATH))).resolve(TARGET) shouldBe
        EditTarget.Buffered.ByFileStore(store)
    }
  }

  describe("resolve, classifying a file with no buffer yet") {
    it("gives a workspace file the IFILE key on its full path") {
      resolver(emptyManager(), arrayOf(workspaceFile(FULL_PATH))).resolve(TARGET) shouldBe
        EditTarget.Buffered.ByPath(FULL_PATH, LocationKind.IFILE)
    }

    it("gives a file outside the workspace the file store key") {
      resolver(emptyManager(), emptyArray()).resolve(TARGET) shouldBe
        EditTarget.Buffered.ByFileStore(store)
    }
  }

  describe("resolve, unresolvable targets") {
    it("returns null for an authority-form URI rather than a path to a different file") {
      // file://C:/dir/a.txt parses C: as a host and leaves /dir/a.txt, which names another file.
      val manager = emptyManager()

      resolver(manager, emptyArray()).resolve(URI("file://C:/dir/a.txt")).shouldBeNull()

      verify(exactly = 0) { manager.getFileStoreTextFileBuffer(any()) }
      verify(exactly = 0) { manager.getTextFileBuffer(any<IPath>(), any()) }
    }
  }

  describe("physicalPathOf") {
    it("normalises file:/… and file:///… to the same path") {
      physicalPathOf(URI("file:/tmp/a.txt")) shouldBe Path.fromOSString("/tmp/a.txt")
      physicalPathOf(URI("file:///tmp/a.txt")) shouldBe Path.fromOSString("/tmp/a.txt")
      physicalPathOf(URI("file:/tmp/a.txt")) shouldBe physicalPathOf(URI("file:///tmp/a.txt"))
    }

    it("returns null for a URI whose drive letter parsed as an authority") {
      physicalPathOf(URI("file://C:/dir/a.txt")).shouldBeNull()
    }

    it("returns null for a URI that is not a file at all") {
      physicalPathOf(URI("https://example.com/a.txt")).shouldBeNull()
    }
  }

  describe("workspaceFilesForLocation") {
    it("asks for hidden and team-private resources too") {
      val root = mockk<IWorkspaceRoot>()
      val files = arrayOf(workspaceFile(FULL_PATH))
      every { root.findFilesForLocationURI(any(), any()) } returns files

      workspaceFilesForLocation(TARGET, root) shouldBe files

      verify {
        root.findFilesForLocationURI(
          TARGET,
          IContainer.INCLUDE_HIDDEN or IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS,
        )
      }
      // The one-argument overload silently drops hidden and team-private resources, which would
      // make a file under a hidden linked folder look like it is outside the workspace.
      verify(exactly = 0) { root.findFilesForLocationURI(any()) }
    }
  }

  describe("bufferAccessFor") {
    it("uses only the IPath family for a ByPath target") {
      val manager = mockk<ITextFileBufferManager>(relaxUnitFun = true)
      val buffer = mockk<ITextFileBuffer>()
      val monitor = mockk<IProgressMonitor>()
      every { manager.getTextFileBuffer(PHYSICAL, LocationKind.NORMALIZE) } returns buffer

      val access = bufferAccessFor(EditTarget.Buffered.ByPath(PHYSICAL, LocationKind.NORMALIZE), manager)
      access.connect(monitor)
      access.current() shouldBe buffer
      access.disconnect(monitor)

      verify { manager.connect(PHYSICAL, LocationKind.NORMALIZE, monitor) }
      verify { manager.disconnect(PHYSICAL, LocationKind.NORMALIZE, monitor) }
      // Never the other map, and never the kind-less overloads, which key under LOCATION instead.
      verifyNoKindLessPathCalls(manager)
      verify(exactly = 0) { manager.connectFileStore(any(), any()) }
      verify(exactly = 0) { manager.getFileStoreTextFileBuffer(any()) }
      verify(exactly = 0) { manager.disconnectFileStore(any(), any()) }
    }

    it("uses only the IFileStore family for a ByFileStore target") {
      val manager = mockk<ITextFileBufferManager>(relaxUnitFun = true)
      val buffer = mockk<ITextFileBuffer>()
      val monitor = mockk<IProgressMonitor>()
      every { manager.getFileStoreTextFileBuffer(store) } returns buffer

      val access = bufferAccessFor(EditTarget.Buffered.ByFileStore(store), manager)
      access.connect(monitor)
      access.current() shouldBe buffer
      access.disconnect(monitor)

      verify { manager.connectFileStore(store, monitor) }
      verify { manager.disconnectFileStore(store, monitor) }
      verify(exactly = 0) { manager.connect(any(), any(), any()) }
      verify(exactly = 0) { manager.disconnect(any(), any(), any()) }
      verify(exactly = 0) { manager.getTextFileBuffer(any<IPath>(), any()) }
    }
  }
})

/**
 * The kind-less `connect(IPath, IProgressMonitor)` / `disconnect(IPath, IProgressMonitor)`
 * overloads are deprecated precisely because they silently mean [LocationKind.LOCATION]; using one
 * for an `IFILE`- or `NORMALIZE`-keyed target would connect to a different buffer than the one
 * looked up. Suppressed narrowly here so the rest of the spec keeps its deprecation warnings.
 */
@Suppress("DEPRECATION")
private fun verifyNoKindLessPathCalls(manager: ITextFileBufferManager) {
  verify(exactly = 0) { manager.connect(any(), any<IProgressMonitor>()) }
  verify(exactly = 0) { manager.disconnect(any(), any<IProgressMonitor>()) }
}
