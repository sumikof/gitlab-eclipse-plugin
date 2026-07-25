package com.gitlab.eclipse.lsp.configuration

import com.gitlab.eclipse.codesuggestions.languages.refreshCodeSuggestionsLanguageToggle
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.capabilities.DidChangeWatchedFileCapability
import com.gitlab.eclipse.lsp.utils.LanguageServerLanguage.languageId
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.eclipse.jface.text.DocumentEvent
import org.eclipse.jface.text.IDocumentListener
import org.eclipse.lsp4j.*
import org.eclipse.ui.*

class GitLabLanguageServerOpenFilesService(
  private val platformUtils: PlatformUtils,
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper,
  private val coroutineScope: CoroutineScope
) : IPartListener2, IDocumentListener {
  init {
    val activeWorkbench = PlatformUI.getWorkbench().activeWorkbenchWindow

    when {
      activeWorkbench != null -> activeWorkbench.activePage.addPartListener(this)
      else -> PlatformUI.getWorkbench().addWindowListener(WindowListener(this))
    }
  }

  // Overload (not a default argument): a default expression reading the wrapper would be
  // evaluated by the Kotlin $default bridge even on MockK mocks, NPE-ing tests that mock
  // this service and trigger a no-arg send.
  fun sendOpenTabs() = sendOpenTabs(gitLabLanguageServerWrapper.languageServer)

  // The [server] parameter binds the queued didOpen/didSetActive notifications to the
  // server captured at CALL time (the readiness callback passes its own initialized
  // proxy): a rapid restart may register a new pre-initialize server before the
  // coroutines run, and the queued work must strand with the old server instead of
  // being redirected at the new one. Workbench listeners use the no-arg paths, which
  // resolve the wrapper's current server at call time.
  fun sendOpenTabs(server: GitLabLanguageServer?) {
    val activePage = PlatformUI.getWorkbench().activeWorkbenchWindow?.activePage
      ?: return

    activePage.editorReferences.forEach { editorRef -> editorOpen(editorRef, server) }
    activePage.activePartReference?.let { activePagePart -> editorActive(activePagePart, server) }
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
      gitLabLanguageServerWrapper.languageServer?.didClose(
        DidCloseTextDocumentParams(
          TextDocumentIdentifier(editorInput.file.locationURI.toASCIIString())
        )
      )

      platformUtils
        .getActiveTextEditor()
        ?.documentProvider
        ?.getDocument(editorInput)
        ?.removeDocumentListener(this@GitLabLanguageServerOpenFilesService)
    }

    refreshCodeSuggestionsLanguageToggle()
  }

  private fun editorActive(
    editorRef: IWorkbenchPartReference,
    server: GitLabLanguageServer? = gitLabLanguageServerWrapper.languageServer,
  ) {
    val editorInput = editorRef.fileEditorInput
      ?: return

    coroutineScope.launch {
      server?.didChangeDocumentInActiveEditor(
        editorInput.file.locationURI.toASCIIString()
      )
    }

    refreshCodeSuggestionsLanguageToggle()
  }

  @Suppress("SwallowedException")
  override fun documentChanged(event: DocumentEvent) {
    coroutineScope.launch {
      gitLabLanguageServerWrapper.languageServer?.didChange(
        DidChangeTextDocumentParams(
          VersionedTextDocumentIdentifier(
            event.document.uri,
            event.modificationStamp.toInt()
          ),
          listOf(TextDocumentContentChangeEvent(event.document.get()))
        )
      )

      service<DidChangeWatchedFileCapability>().documentChanged(event.document.uri)
    }
  }

  override fun documentAboutToBeChanged(event: DocumentEvent) = Unit

  private fun editorOpen(
    editorRef: IWorkbenchPartReference,
    server: GitLabLanguageServer? = gitLabLanguageServerWrapper.languageServer,
  ) {
    val editorInput = editorRef.fileEditorInput
      ?: return

    coroutineScope.launch {
      server?.didOpen(
        DidOpenTextDocumentParams(editorInput.toTextDocumentItem())
      )

      platformUtils
        .getActiveTextEditor()
        ?.documentProvider
        ?.getDocument(editorInput)
        ?.addDocumentListener(this@GitLabLanguageServerOpenFilesService)
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
    window.activePage?.removePartListener(partListener)
  }

  override fun windowActivated(p0: IWorkbenchWindow?) = Unit

  override fun windowDeactivated(p0: IWorkbenchWindow?) = Unit
}
