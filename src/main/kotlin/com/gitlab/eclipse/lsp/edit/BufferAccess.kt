package com.gitlab.eclipse.lsp.edit

import org.eclipse.core.filebuffers.ITextFileBuffer
import org.eclipse.core.filebuffers.ITextFileBufferManager
import org.eclipse.core.filebuffers.LocationKind
import org.eclipse.core.filesystem.IFileStore
import org.eclipse.core.runtime.IPath
import org.eclipse.core.runtime.IProgressMonitor

/**
 * The three manager calls an edit needs against one file buffer, bound to a single key.
 *
 * The point of the abstraction is that the key is chosen once, by [bufferAccessFor], and then
 * cannot drift: connect, look up and disconnect are guaranteed to name the same entry in the same
 * one of `ITextFileBufferManager`'s two maps. Callers never see the key again, so they cannot
 * connect under an `IPath` and read back under an `IFileStore`.
 *
 * Every method delegates straight to the manager and so may throw
 * `org.eclipse.core.runtime.CoreException`.
 */
interface BufferAccess {
  /** Connects (or increments the reference count on) the buffer. */
  fun connect(monitor: IProgressMonitor)

  /** The connected buffer, or `null` when nothing is connected under this key. */
  fun current(): ITextFileBuffer?

  /** Releases the reference taken by [connect]. Must be paired with it, including on failure. */
  fun disconnect(monitor: IProgressMonitor)
}

/**
 * Binds [target] to [manager], picking the one API family that matches the target's key.
 *
 * `ByPath` -> `connect`/`getTextFileBuffer`/`disconnect`;
 * `ByFileStore` -> `connectFileStore`/`getFileStoreTextFileBuffer`/`disconnectFileStore`.
 * The `when` is exhaustive over a sealed hierarchy, so a new variant cannot be added without
 * choosing a family for it.
 */
fun bufferAccessFor(target: EditTarget.Buffered, manager: ITextFileBufferManager): BufferAccess =
  when (target) {
    is EditTarget.Buffered.ByPath -> PathBufferAccess(manager, target.path, target.kind)
    is EditTarget.Buffered.ByFileStore -> FileStoreBufferAccess(manager, target.fileStore)
  }

/** `IPath` + [LocationKind] key: the manager's `fFilesBuffers` map. */
private class PathBufferAccess(
  private val manager: ITextFileBufferManager,
  private val path: IPath,
  private val kind: LocationKind,
) : BufferAccess {
  override fun connect(monitor: IProgressMonitor) = manager.connect(path, kind, monitor)

  override fun current(): ITextFileBuffer? = manager.getTextFileBuffer(path, kind)

  override fun disconnect(monitor: IProgressMonitor) = manager.disconnect(path, kind, monitor)
}

/** `IFileStore` key: the manager's `fFileStoreFileBuffers` map. */
private class FileStoreBufferAccess(
  private val manager: ITextFileBufferManager,
  private val fileStore: IFileStore,
) : BufferAccess {
  override fun connect(monitor: IProgressMonitor) = manager.connectFileStore(fileStore, monitor)

  override fun current(): ITextFileBuffer? = manager.getFileStoreTextFileBuffer(fileStore)

  override fun disconnect(monitor: IProgressMonitor) = manager.disconnectFileStore(fileStore, monitor)
}
