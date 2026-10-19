package com.gitlab.eclipse.mergerequests.diff

import org.eclipse.compare.CompareConfiguration
import org.eclipse.compare.CompareEditorInput
import org.eclipse.compare.CompareUI
import org.eclipse.core.runtime.NullProgressMonitor
import org.eclipse.ui.IEditorReference
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.PlatformUI

/**
 * Opens, finds and closes MR file diff editors (design §5.1). UI thread only.
 *
 * Lookups go through `page.editorReferences` filtered on the Compare editor id and only look at
 * editors that are already materialized (`getEditor(false)`), so they never restore other editors.
 */
object MrCompareOpener {
  /** Compare's editor id (org.eclipse.compare plugin.xml). */
  const val COMPARE_EDITOR_ID = "org.eclipse.compare.CompareEditor"

  /**
   * Opens [content] on [page]. The input is run here, which only returns its prebuilt node, so
   * Compare starts neither a Job nor a modal context. [configuration] and [openOnPage] are seams
   * for headless tests; at runtime they are Compare's plugin-store configuration and `CompareUI`.
   */
  fun open(
    content: MrFileDiffContent,
    page: IWorkbenchPage,
    configuration: () -> CompareConfiguration = ::CompareConfiguration,
    openOnPage: (CompareEditorInput, IWorkbenchPage) -> Unit = CompareUI::openCompareEditorOnPage,
  ) {
    val input = MrCompareEditorInput(content, configuration())
    input.run(NullProgressMonitor())
    openOnPage(input, page)
  }

  /** Activates the editor already showing [key] on [page]; `true` if there was one. */
  fun activateExisting(key: MrFileDiffKey, page: IWorkbenchPage): Boolean {
    val part =
      page.editorReferences
        .asSequence()
        .filter { it.id == COMPARE_EDITOR_ID }
        .mapNotNull { it.getEditor(false) }
        .firstOrNull { (it.editorInput as? MrCompareEditorInput)?.key == key }
        ?: return false
    page.activate(part)
    return true
  }

  /** Bundle stop: closes every MR diff editor unless the workbench is closing (they go with it). */
  fun closeAll() {
    closeAllIn(if (PlatformUI.isWorkbenchRunning()) PlatformUI.getWorkbench() else null)
  }

  internal fun closeAllIn(workbench: IWorkbench?) {
    if (workbench == null || workbench.isClosing) return
    workbench.workbenchWindows.forEach { window ->
      window.pages.forEach { page ->
        val mine = page.editorReferences.filter(::isMrDiffEditor)
        if (mine.isNotEmpty()) page.closeEditors(mine.toTypedArray(), false)
      }
    }
  }

  private fun isMrDiffEditor(ref: IEditorReference): Boolean =
    ref.id == COMPARE_EDITOR_ID && ref.getEditor(false)?.editorInput is MrCompareEditorInput
}
