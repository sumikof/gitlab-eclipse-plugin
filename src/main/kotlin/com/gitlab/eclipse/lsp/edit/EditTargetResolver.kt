package com.gitlab.eclipse.lsp.edit

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.filebuffers.FileBuffers
import org.eclipse.core.filebuffers.ITextFileBufferManager
import org.eclipse.core.filebuffers.LocationKind
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.resources.IContainer
import org.eclipse.core.resources.IFile
import org.eclipse.core.resources.IWorkspaceRoot
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.CoreException
import org.eclipse.core.runtime.IPath
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.editors.text.ILocationProvider
import org.eclipse.ui.editors.text.ILocationProviderExtension
import org.eclipse.ui.texteditor.IDocumentProvider
import org.eclipse.ui.texteditor.ITextEditor
import java.io.File
import java.net.URI

private val log by lazy { logger<EditTargetResolver>() }

/**
 * Decides which document an incoming edit belongs to.
 *
 * Pure logic: this class touches neither SWT nor the workbench. Every platform lookup arrives as a
 * constructor function with a production default, which is what makes the decision reachable in a
 * headless test.
 *
 * The order below is binding.
 *
 *  1. **An open editor wins outright.** If [findOpenEditorFor] answers, the result is
 *     [EditTarget.OpenEditor] and nothing else is consulted — no workspace lookup, no buffer
 *     manager. There is deliberately no fallback from rule 1 to rule 2: an open editor's buffer key
 *     is a function of its input type, not of the URI, so rule 2 cannot reproduce it in general and
 *     would key a second buffer over the same file.
 *  2. **Otherwise probe every key before creating anything.** The manager may already hold a buffer
 *     for this file under any of four keys; joining the one that exists is the only way to stay on
 *     the same document. Only when no probe hits does the URI get classified into a new key.
 *  3. **Otherwise `null`** — the caller reports the edit as not applied.
 *
 * @property findOpenEditorFor rule 1's workbench walk; defaults to [findOpenEditorInWorkbench]
 * @property findFilesForLocationURI workspace files at a location; defaults to
 *   [workspaceFilesForLocation], which passes the hidden/team-private flags
 * @property bufferManager the file buffer manager; defaults to `FileBuffers.getTextFileBufferManager()`
 */
class EditTargetResolver(
  private val findOpenEditorFor: (URI) -> Pair<IEditorInput, IDocumentProvider>? =
    ::findOpenEditorInWorkbench,
  private val findFilesForLocationURI: (URI) -> Array<IFile> = ::workspaceFilesForLocation,
  private val bufferManager: () -> ITextFileBufferManager = FileBuffers::getTextFileBufferManager,
) {
  /** Resolves [uri] to the document an edit should be applied to, or `null` if there is none. */
  fun resolve(uri: URI): EditTarget? {
    findOpenEditorFor(uri)?.let { (input, provider) -> return EditTarget.OpenEditor(input, provider) }
    val candidates = findFilesForLocationURI(uri)
    // A URI we cannot turn into a filesystem path is unresolvable, not something to approximate:
    // the authority form `file://C:/dir/a.txt` parses `C:` as a host and leaves `/dir/a.txt`, so
    // any store or path derived from it would name a *different* file. Rule 3 applies.
    val physical = physicalPathOf(uri) ?: return null
    val fileStore = fileStoreOf(uri)
    val manager = bufferManager()
    return joinExistingBuffer(manager, candidates, physical, fileStore)
      ?: classify(candidates, fileStore)
  }

  /**
   * Rule 2's probe: the first key that already holds a buffer wins, so the edit joins the document
   * the user (or an earlier edit) is already on. Order matters only in that the workspace key is
   * the one the platform's own providers use, so it is tried first.
   */
  private fun joinExistingBuffer(
    manager: ITextFileBufferManager,
    candidates: Array<IFile>,
    physical: IPath,
    fileStore: IFileStore?,
  ): EditTarget.Buffered? {
    candidates.forEach { candidate ->
      val fullPath = candidate.fullPath
      if (manager.getTextFileBuffer(fullPath, LocationKind.IFILE) != null) {
        return EditTarget.Buffered.ByPath(fullPath, LocationKind.IFILE)
      }
    }
    if (manager.getTextFileBuffer(physical, LocationKind.NORMALIZE) != null) {
      return EditTarget.Buffered.ByPath(physical, LocationKind.NORMALIZE)
    }
    if (manager.getTextFileBuffer(physical, LocationKind.LOCATION) != null) {
      return EditTarget.Buffered.ByPath(physical, LocationKind.LOCATION)
    }
    if (fileStore != null && manager.getFileStoreTextFileBuffer(fileStore) != null) {
      return EditTarget.Buffered.ByFileStore(fileStore)
    }
    return null
  }

  /**
   * Rule 2's fallback, reached only when no buffer exists yet: a file inside the workspace gets the
   * workspace key the platform would give it, anything else gets the file store key.
   */
  private fun classify(candidates: Array<IFile>, fileStore: IFileStore?): EditTarget.Buffered? =
    candidates.firstOrNull()?.let { EditTarget.Buffered.ByPath(it.fullPath, LocationKind.IFILE) }
      ?: fileStore?.let { EditTarget.Buffered.ByFileStore(it) }
}

/**
 * The physical filesystem path [uri] names, or `null` when it names none.
 *
 * `java.io.File(URI)` rather than `org.eclipse.core.filesystem.URIUtil.toPath(URI)`, and the
 * difference is not cosmetic. `URIUtil.toPath` converts through `URI.getSchemeSpecificPart()`,
 * which is `/C:/dir/a.txt` for `file:/C:/…` but `///C:/dir/a.txt` for `file:///C:/…`; measured,
 * `IPath.fromOSString("///C:/dir/a.txt")` yields a path with `isUNC = true`, i.e. a different file
 * for the same input spelled two legal ways. `java.io.File(URI)` goes through `URI.getPath()`,
 * which normalises both spellings to `/C:/dir/a.txt` (measured), and leaves the Windows
 * drive-letter leading slash to the platform's own `File` handling.
 *
 * The exception cases are real inputs, not defects: `file://C:/dir/a.txt` parses `C:` as an
 * authority and `http://…` is not a file at all. Both are unresolvable and say so.
 */
internal fun physicalPathOf(uri: URI): IPath? =
  try {
    IPath.fromOSString(File(uri).absolutePath)
  } catch (e: IllegalArgumentException) {
    // Class name only: the URI itself must never reach the log.
    log.warn("Edit target URI does not name a filesystem path: ${e.javaClass.name}")
    null
  }

/**
 * `EFS.getStore(uri)`, or `null` when the URI has no file system. Matching the call
 * `TextFileDocumentProvider.createFileInfo` makes is the point: a store obtained any other way
 * could compare unequal to the one an editor connected under.
 */
internal fun fileStoreOf(uri: URI): IFileStore? =
  try {
    EFS.getStore(uri)
  } catch (e: CoreException) {
    log.warn("Edit target URI has no file system: ${e.javaClass.name}")
    null
  }

/**
 * Production default for [EditTargetResolver.findFilesForLocationURI].
 *
 * The **two**-argument overload: the one-argument `findFilesForLocationURI(URI)` silently omits
 * hidden and team-private resources, so a file under a hidden linked folder would look like it is
 * outside the workspace and be given a file store key while the platform gives it a workspace one.
 */
fun workspaceFilesForLocation(uri: URI): Array<IFile> =
  workspaceFilesForLocation(uri, ResourcesPlugin.getWorkspace().root)

/** [workspaceFilesForLocation] against an explicit [root], so the flags are assertable headless. */
internal fun workspaceFilesForLocation(uri: URI, root: IWorkspaceRoot): Array<IFile> =
  root.findFilesForLocationURI(
    uri,
    IContainer.INCLUDE_HIDDEN or IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS,
  )

/**
 * Production default for [EditTargetResolver.findOpenEditorFor]: rule 1's workbench walk.
 *
 * Lives outside [EditTargetResolver] so the resolver itself never mentions `PlatformUI` and stays
 * constructible headless. Must be called on the UI thread.
 */
fun findOpenEditorInWorkbench(uri: URI): Pair<IEditorInput, IDocumentProvider>? =
  PlatformUI.getWorkbench().workbenchWindows
    .asSequence()
    .flatMap { window -> window.pages.asSequence() }
    .flatMap { page -> page.editorReferences.asSequence() }
    .firstNotNullOfOrNull { reference -> matchedEditor(reference, uri) }

/**
 * [reference] as an input/provider pair if it is open on [uri], else `null`.
 *
 * `getEditor(false)` on purpose. An unrestored reference has no editor part, which means
 * `createFileInfo` never ran and it holds no file buffer at all — so it is genuinely "not open",
 * and the buffer rule 2 goes on to create is the only one and cannot conflict with it. Forcing a
 * restore with `getEditor(true)` would instead materialise an editor, a document and a buffer as a
 * side effect of an edit nobody asked to be shown.
 *
 * An editor with no document provider (an image editor, say) is skipped rather than treated as the
 * answer: another editor further along the walk may still be a text editor on the same file.
 */
private fun matchedEditor(reference: IEditorReference, uri: URI): Pair<IEditorInput, IDocumentProvider>? {
  val editor = reference.getEditor(false) ?: return null
  val input = editor.editorInput ?: return null
  if (!inputMatches(input, uri)) return null
  val textEditor = editor.getAdapter(ITextEditor::class.java) ?: (editor as? ITextEditor) ?: return null
  return textEditor.documentProvider?.let { input to it }
}

/**
 * Whether [input] is an editor input on [uri], using the **same adapter priority**
 * `TextFileDocumentProvider.createFileInfo` uses (verified against
 * `org.eclipse.ui.editors-3.20.200` bytecode): `IFile` first and exclusively if present, then
 * `ILocationProvider`'s extension URI, then that same provider's path.
 *
 * Following the platform's order matters because it is the order that decides the editor's buffer
 * key; matching on some other property could pair a URI with an editor whose buffer is over a
 * different file.
 */
private fun inputMatches(input: IEditorInput, uri: URI): Boolean {
  input.getAdapter(IFile::class.java)?.let { return sameFile(it.locationURI, uri) }
  val provider = input.getAdapter(ILocationProvider::class.java) ?: return false
  if (provider is ILocationProviderExtension) {
    provider.getURI(input)?.let { return sameFile(it, uri) }
  }
  val path = provider.getPath(input) ?: return false
  return sameFile(path.toFile().toURI(), uri)
}

/**
 * Whether two URIs name the same file.
 *
 * Not `URI.equals` alone: `file:/tmp/a.txt` and `file:///tmp/a.txt` are unequal URIs over one file,
 * and the two spellings arrive from different sources (the language server and the platform).
 * Equality is tried first, and is the only test for non-`file` schemes — those have no physical
 * path, so there is no second spelling to reconcile.
 */
private fun sameFile(candidate: URI?, uri: URI): Boolean {
  if (candidate == null) return false
  if (candidate == uri) return true
  if (!isFileScheme(candidate) || !isFileScheme(uri)) return false
  val candidatePath = physicalPathOf(candidate) ?: return false
  return candidatePath == physicalPathOf(uri)
}

private fun isFileScheme(uri: URI): Boolean = "file".equals(uri.scheme, ignoreCase = true)
