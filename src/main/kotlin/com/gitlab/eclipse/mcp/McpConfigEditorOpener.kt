package com.gitlab.eclipse.mcp

import org.eclipse.core.filesystem.EFS
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.ide.IDE
import java.nio.file.Path

/** Opens a local (possibly outside-workspace) file in an editor on the UI thread. */
fun openMcpConfigInEditor(path: Path) {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
  val fileStore = EFS.getLocalFileSystem().getStore(path.toUri())
  IDE.openEditorOnFileStore(page, fileStore)
}
