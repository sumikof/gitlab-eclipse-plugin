package com.gitlab.eclipse.lsp.edit

import org.eclipse.core.filebuffers.LocationKind
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.runtime.IPath
import org.eclipse.ui.IEditorInput
import org.eclipse.ui.texteditor.IDocumentProvider

/**
 * The document an incoming edit belongs to, as decided by [EditTargetResolver].
 *
 * There are exactly two routes to a document and they are not interchangeable:
 *
 *  * [OpenEditor] — an editor part is already open on the file. Its buffer key is a function of the
 *    *input type* the editor was opened with, not of the URI, so it cannot be reconstructed from
 *    the URI alone. We ask the editor's own document provider instead of guessing.
 *  * [Buffered] — no editor part exists, so the edit goes through `ITextFileBufferManager`.
 *
 * [Buffered] carries the **whole** buffer key, never just the path. `ITextFileBufferManager` holds
 * two maps that never consult each other — one keyed by `IPath` + [LocationKind]
 * (`fFilesBuffers`), one keyed by `IFileStore` (`fFileStoreFileBuffers`) — so connecting under one
 * key and looking a buffer up under another silently produces a *second* buffer over the same file,
 * with the user's editor and the agent's edit on opposite sides of it. Every variant below is
 * therefore consumed through exactly one manager API family; see [bufferAccessFor].
 */
sealed interface EditTarget {
  /**
   * Route 1: an editor is open on the file.
   *
   * @property input the editor's own [IEditorInput], to be passed back to [provider] unchanged
   * @property provider the editor's document provider, already connected to [input]
   */
  data class OpenEditor(
    val input: IEditorInput,
    val provider: IDocumentProvider,
  ) : EditTarget

  /** Route 2: no editor part. Join an existing file buffer if there is one, else create one. */
  sealed interface Buffered : EditTarget {
    /**
     * A buffer in the manager's `IPath`-keyed map.
     *
     * @property path `IFile.getFullPath()` (`/project/dir/file`) when [kind] is
     *   [LocationKind.IFILE]; the **physical** filesystem path when it is [LocationKind.NORMALIZE]
     *   or [LocationKind.LOCATION]
     * @property kind the location kind the buffer is (or will be) keyed under. It is part of the
     *   key, so it is carried rather than assumed: a probe that found a `NORMALIZE`-keyed buffer
     *   and then connected under `IFILE` would create the second buffer described above.
     */
    data class ByPath(val path: IPath, val kind: LocationKind) : Buffered

    /**
     * A buffer in the manager's `IFileStore`-keyed map — the route for files outside the
     * workspace.
     *
     * @property fileStore the store obtained from `EFS.getStore(uri)`, matching the call
     *   `TextFileDocumentProvider.createFileInfo` makes for the same case
     */
    data class ByFileStore(val fileStore: IFileStore) : Buffered
  }
}
