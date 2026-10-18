package com.gitlab.eclipse.mcp

import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.openInActiveEditor
import org.eclipse.core.filesystem.EFS
import org.eclipse.core.resources.IFile
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens the config [McpWorkspaceConfigWriter] handed back as [McpWorkspaceConfigOutcome.Ready].
 *
 * One plain `asyncExec` to reach the UI thread, **with no scheduling rule**: the write is already
 * done, and taking a rule on the UI thread is what could block it behind a build. The workspace file
 * is opened when it exists, so the editor shares the workspace buffer; otherwise the file on disk
 * (a resource filter can hide `.gitlab`). Neither — the config was removed after the job — is a
 * notice.
 *
 * Every UI and platform touch is a constructor seam with a production default, so the class is
 * headless-testable; nothing it does on the UI thread escapes into the event loop.
 *
 * @param fallbackWindow the workbench's active window, consulted on the UI thread only when the
 *   command's event carried none.
 */
class McpWorkspaceConfigOpener(
  private val onUiThread: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val fallbackWindow: () -> IWorkbenchWindow? = { PlatformUI.getWorkbench().activeWorkbenchWindow },
  private val openEditor: (IWorkbenchWindow, IFile?, Path?) -> Boolean = ::openConfigInWindow,
  private val notify: (String) -> Unit = { NotificationUtils.show(it) },
) {
  private val logger by lazy { logger<McpWorkspaceConfigOpener>() }

  /** Any thread (the job's `done` callback in production). Never throws. */
  fun open(window: IWorkbenchWindow?, ready: McpWorkspaceConfigOutcome.Ready) {
    try {
      onUiThread(Runnable { openOnUiThread(window, ready) })
    } catch (e: Exception) {
      // Display gone (SWTException) or workbench torn down (IllegalStateException): nothing to open into.
      logger.warn("$LOG_PREFIX could not reach the UI thread: ${e.javaClass.name}")
    }
  }

  /** UI thread. Class names only in the log: a `PartInitException` message carries the path. */
  private fun openOnUiThread(window: IWorkbenchWindow?, ready: McpWorkspaceConfigOutcome.Ready) {
    try {
      val target = window ?: fallbackWindow()
      val file = ready.file.takeIf { it.exists() }
      val store = if (file == null) ready.configFile.takeIf { Files.isRegularFile(it) } else null
      val opened = when {
        target == null -> false.also { logger.error("$LOG_PREFIX no workbench window to open in") }
        file == null && store == null -> false.also { logger.error("$LOG_PREFIX the config is gone") }
        else -> openEditor(target, file, store).also { if (!it) logger.error("$LOG_PREFIX no active page") }
      }
      when {
        !opened -> notify(McpConfigMessages.OPEN_FAILED)
        // With several projects the first one is often not the one in use (and not the first the
        // Project Explorer shows: the order is by `String.compareTo`), so say which one it was.
        ready.projectCount > 1 -> notify(McpConfigMessages.openedIn(ready.projectName))
      }
    } catch (e: Exception) {
      logger.error("$LOG_PREFIX could not open the config: ${e.javaClass.name}")
      notify(McpConfigMessages.OPEN_FAILED)
    }
  }

  private companion object {
    const val LOG_PREFIX = "MCP workspace configuration:"
  }
}

/**
 * Production default. UI thread only; false when [window] has no active page. Passes [window]'s own
 * page rather than [openInActiveEditor]'s default, which would resolve the globally active window again.
 */
private fun openConfigInWindow(window: IWorkbenchWindow, file: IFile?, store: Path?): Boolean =
  openInActiveEditor(
    workspaceFile = { file },
    fileStore = { store?.let { EFS.getLocalFileSystem().getStore(it.toUri()) } },
    activePage = { window.activePage },
  )
