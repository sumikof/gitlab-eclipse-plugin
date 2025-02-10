package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.languageId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.ui.*

class GitLabLanguageServerOpenFilesService(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) : IPartListener2 {
  init {
    val activeWorkbench = PlatformUI.getWorkbench().activeWorkbenchWindow

    when {
      activeWorkbench != null -> activeWorkbench.activePage.addPartListener(this)
      else -> PlatformUI.getWorkbench().addWindowListener(WindowListener(this))
    }
  }

  fun sendOpenTabs() {
    val activePage = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
      ?: return

    activePage.editorReferences.forEach { editorRef -> editorOpen(editorRef) }
    activePage.activePartReference?.let { activePagePart -> editorActive(activePagePart) }
  }

  override fun partOpened(partRef: IWorkbenchPartReference) {
    editorOpen(partRef)
  }

  override fun partActivated(partRef: IWorkbenchPartReference) {
    editorActive(partRef)
  }

  override fun partClosed(partRef: IWorkbenchPartReference) {
    val editorInput = partRef.fileEditorInput
      ?: return

    coroutineScope.launch {
      gitLabLanguageServerWrapper.languageServer?.textDocumentService?.didClose(
        DidCloseTextDocumentParams(
          TextDocumentIdentifier(editorInput.file.locationURI.toASCIIString())
        )
      )
    }
  }

  private fun editorActive(editorRef: IWorkbenchPartReference) {
    val editorInput = editorRef.fileEditorInput
      ?: return

    coroutineScope.launch {
      gitLabLanguageServerWrapper.languageServer?.didChangeDocumentInActiveEditor(
        editorInput.file.locationURI.toASCIIString()
      )
    }
  }

  private fun editorOpen(editorRef: IWorkbenchPartReference) {
    val editorInput = editorRef.fileEditorInput
      ?: return

    coroutineScope.launch {
      gitLabLanguageServerWrapper.languageServer?.textDocumentService?.didOpen(
        DidOpenTextDocumentParams(editorInput.toTextDocumentItem())
      )
    }
  }

  private val IWorkbenchPartReference.fileEditorInput: IFileEditorInput?
    get() = run {
      if (this !is IEditorReference) {
        return@run null
      }

      return editorInput as? IFileEditorInput
    }

  private fun IFileEditorInput.toTextDocumentItem() = TextDocumentItem(
    file.locationURI.toASCIIString(),
    file.languageId,
    file.modificationStamp.toInt(),
    file.getContents(true).readAllBytes().decodeToString()
  )
}

private class WindowListener(
  private val partListener: IPartListener2
) : IWindowListener {
  override fun windowOpened(window: IWorkbenchWindow) {
    window.activePage.addPartListener(partListener)
  }

  override fun windowClosed(window: IWorkbenchWindow) {
    window.activePage.removePartListener(partListener)
  }

  override fun windowActivated(p0: IWorkbenchWindow?) = Unit

  override fun windowDeactivated(p0: IWorkbenchWindow?) = Unit
}
