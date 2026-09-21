package com.gitlab.eclipse.diagnostics

import org.eclipse.core.filesystem.EFS
import org.eclipse.swt.SWT
import org.eclipse.swt.widgets.FileDialog
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.ide.IDE
import java.nio.file.Path

/**
 * The two places this feature touches SWT (design §8.2).
 *
 * Kept apart from everything else so the rest of the feature stays headless-testable: a test JVM
 * cannot create a `Display`, so anything that does has to be reachable without going through the
 * logic under test (the same separation `ClipboardWriter` makes with `ClipboardTarget`).
 */

/**
 * Opens a generated diagnostics file in an editor. **UI thread only.**
 *
 * Uses the file-store route rather than the workspace route: these files live in the plugin's state
 * directory, which is outside the workspace, so there is no `IFile` for them. Same call the MCP
 * config feature makes.
 */
internal fun openDiagnosticsFile(path: Path) {
  val page = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage ?: return
  IDE.openEditorOnFileStore(page, EFS.getLocalFileSystem().getStore(path.toUri()))
}

/**
 * Asks the user where to save the export, returning `null` if they cancel. **UI thread only** —
 * called from a command handler, which Eclipse already runs there.
 *
 * `SWT.SAVE` makes the platform ask about overwriting an existing file, so the confirmation is the
 * native one the user expects rather than something invented here.
 */
internal fun promptForArchiveDestination(suggestedName: String): Path? {
  val shell = PlatformUI.getWorkbench().activeWorkbenchWindow?.shell ?: return null
  val dialog = FileDialog(shell, SWT.SAVE).apply {
    setText("Export GitLab Diagnostics")
    setFileName(suggestedName)
    // Both setters are varargs in SWT 3.135.0 (javap on org.eclipse.swt.gtk.linux.aarch64), which
    // is also why they are not usable as Kotlin properties.
    setFilterExtensions("*.zip")
    setFilterNames("ZIP Archive")
    setOverwrite(true)
  }
  return dialog.open()?.let(Path::of)
}
