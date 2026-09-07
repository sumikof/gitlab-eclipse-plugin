package com.gitlab.eclipse.lsp

import com.gitlab.eclipse.lsp.edit.fileStoreOf
import com.gitlab.eclipse.lsp.edit.workspaceFilesForLocation
import com.gitlab.eclipse.lsp.messages.OpenFileParams
import com.gitlab.eclipse.lsp.messages.usablePayload
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.openInActiveEditor
import org.eclipse.core.resources.ResourcesPlugin
import org.eclipse.core.runtime.IPath
import java.net.URI

/**
 * Opens the file the language server names in `$/gitlab/openFile`.
 *
 * A notification: there is no response to get right, and no user-facing error either. What must be
 * right is *which* file gets opened — it has to be the one `workspace/applyEdit` would edit, or
 * the agent's edits and the user's editor end up on two buffers over one file, which is the defect
 * this cycle removes. So the workspace-inside/outside decision is not made here: it is taken from
 * `workspace/applyEdit`'s own classifier (see [openInActivePage]).
 *
 * Resolution of the incoming `filePath`, in order:
 *
 *  1. Absolute — used as it stands, never joined to a root.
 *  2. Relative — resolved against each workspace root **in the order this plugin reports
 *     `workspaceFolders` to the server** (`ResourcesPlugin.getWorkspace().root.projects`, see
 *     `com.gitlab.eclipse.lsp.utils.workspaceFolders`), taking the first candidate that exists.
 *     VS Code joins against a single `rootPath`; this plugin reports one folder per open project,
 *     so there are several roots and "first that exists" is the only rule that can pick among them.
 *  3. Nothing exists — do nothing. No response, no notification, and no path in the log.
 *
 * The protocol carries `{ filePath }` and nothing else in this version — no `selection`, no
 * `range` — so there is deliberately no reveal or scroll behaviour.
 *
 * Everything except the open itself runs on the calling (lsp4j dispatch) thread; the open hops to
 * the UI thread with `asyncExec`. **Never `syncExec`**: the dispatch thread would deadlock against
 * a UI thread waiting on it.
 *
 * @property onUiThread the UI-thread hop; defaults to `currentDisplay.asyncExec`
 * @property workspaceRoots the roots to resolve a relative path against, in report order;
 *   defaults to [projectLocations]
 * @property fileExists whether a candidate names an openable file; defaults to a `isFile` probe
 * @property openInEditor opens a resolved file; defaults to [openInActivePage]. **UI thread only.**
 */
class WorkspaceFileOpener(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val workspaceRoots: () -> List<IPath> = ::projectLocations,
  private val fileExists: (IPath) -> Boolean = { it.toFile().isFile },
  private val openInEditor: (URI) -> Unit = ::openInActivePage,
) {
  private val log = logger<WorkspaceFileOpener>()

  /** Opens the file [params] names, or does nothing if it names none that exists. */
  fun open(params: OpenFileParams) {
    val requested = usablePayload(params.filePath)?.let(IPath::fromOSString)
    if (requested == null) {
      log.info("openFile: the language server sent no usable file path.")
      return
    }
    val resolved = candidatesFor(requested).firstOrNull(fileExists)
    if (resolved == null) {
      // No path in the message, by cycle-wide rule; the server can be asked what it sent.
      log.info("openFile: no file exists at the requested path, under any workspace root.")
      return
    }
    // File -> URI rather than a hand-built one: it is the exact inverse of the `java.io.File(uri)`
    // conversion `EditTargetResolver` uses, so both sides spell the same file the same way.
    schedule(resolved.toFile().toURI())
  }

  /**
   * The paths to try, in order. An absolute path is its own only candidate — joining it to a root
   * would silently name a different file (`IPath.append` drops the leading separator).
   */
  private fun candidatesFor(requested: IPath): List<IPath> =
    if (requested.isAbsolute) listOf(requested) else workspaceRoots().map { it.append(requested) }

  /**
   * Hands the open to the UI thread and lets nothing come back.
   *
   * Two containments, because there are two thread contexts. Inside the runnable: an open that
   * throws (`PartInitException` for a file no editor can be found for, say) must not surface as an
   * "Unhandled event loop exception". Around the hop: the display lookup throws
   * [IllegalStateException] once the workbench is torn down and `asyncExec` throws
   * [org.eclipse.swt.SWTException] on a disposed display, and this runs on the lsp4j dispatch
   * thread, where an escaping exception is not this notification's to spend.
   */
  private fun schedule(uri: URI) {
    try {
      onUiThread(
        Runnable {
          try {
            openInEditor(uri)
          } catch (e: Exception) {
            log.error("openFile: could not open an editor for the file: ${e.javaClass.name}")
          }
        },
      )
    } catch (e: Exception) {
      log.warn("openFile: could not reach the UI thread: ${e.javaClass.name}")
    }
  }
}

/**
 * Production default for [WorkspaceFileOpener.workspaceRoots]: the location of every project, in
 * `IWorkspaceRoot.getProjects()` order — the same source and the same order as the
 * `workspaceFolders` this plugin reports to the server, so a relative path resolves against the
 * roots the server believes it has. A project with no local location contributes nothing and does
 * not disturb the order of the rest.
 */
fun projectLocations(): List<IPath> =
  ResourcesPlugin.getWorkspace().root.projects.mapNotNull { it.location }

/**
 * Production default for [WorkspaceFileOpener.openInEditor]. **UI thread only.**
 *
 * The inside/outside decision is `findFilesForLocationURI` with the hidden and team-private flags
 * — `workspace/applyEdit`'s own classifier, called through the very function that classifier uses
 * ([workspaceFilesForLocation]), so the two can only agree.
 *
 * **Not `IWorkspaceRoot.getFileForLocation`**, which the merge-request opener uses: it matches a
 * location against each project's own location prefix and so never finds a **linked resource**. A
 * linked file would then be opened through the file-store family while `applyEdit` edits it
 * through the `IFILE` family — two buffers over one file, the exact divergence this cycle exists
 * to remove.
 *
 * No `IFile.exists()` filter, for the same reason: `EditTargetResolver.classify` takes the first
 * candidate unfiltered, and a filter here would put the two back out of step. A stale (not yet
 * refreshed) resource makes `IDE.openEditor` throw, which the caller contains.
 */
fun openInActivePage(uri: URI) {
  openInActiveEditor(
    workspaceFile = { workspaceFilesForLocation(uri).firstOrNull() },
    fileStore = { fileStoreOf(uri) },
  )
}
