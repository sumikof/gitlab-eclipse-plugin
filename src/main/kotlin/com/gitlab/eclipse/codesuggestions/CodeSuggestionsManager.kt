package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.*
import org.eclipse.ui.texteditor.ITextEditor

@Suppress("ParameterListWrapping", "EmptyFunctionBlock", "TooManyFunctions")
internal class CodeSuggestionsManager(
  private val platformUtils: PlatformUtils,
  isCodeSuggestionsEnabled: Boolean = BuildConfig.CODE_SUGGESTIONS_ENABLED,
  private val createCodeSuggestionsSession: (ITextEditor) -> CodeSuggestionsSession,
) {
  private val logger = logger<CodeSuggestionsManager>()
  private val editorSessions = mutableMapOf<ITextEditor, CodeSuggestionsSession>()

  init {
    try {
      if (isCodeSuggestionsEnabled) {
        platformUtils.getWorkbench().setupWorkbenchListeners()
      }
    } catch (e: Exception) {
      logger.error("Error setting up platform listeners", e)
    }
  }

  fun requestCodeSuggestion() {
    val editor = platformUtils.getActiveTextEditor()
      ?: return

    editorSessions[editor]?.requestCodeSuggestion() ?: run {
      logger.warn("No active Code Suggestions session found for ${editor.title}.")
    }
  }

  fun acceptCodeSuggestion() {
    val editor = platformUtils.getActiveTextEditor()
      ?: return

    editorSessions[editor]?.acceptCodeSuggestion()
  }

  fun rejectCodeSuggestion() {
    val editor = platformUtils.getActiveTextEditor()
      ?: return

    editorSessions[editor]?.rejectCodeSuggestion()
    logger.info("Code Suggestions rejected for ${editor.title}.")
  }

  fun isCodeSuggestionDisplayed(): Boolean {
    val editor = platformUtils.getActiveTextEditor()
      ?: return false

    val session = editorSessions[editor]
      ?: return false

    return session.isCodeSuggestionDisplayed()
  }

  fun endAllSessions() {
    editorSessions.keys.toList().forEach(::endSession)

    if (editorSessions.isNotEmpty()) editorSessions.clear()

    logger.info("Ended all Code Suggestion sessions.")
  }

  //region Extension functions for platform listeners

  private fun IWorkbench.setupWorkbenchListeners() {
    addWindowListener(createWindowListener { it.setupWindowListeners() })
    workbenchWindows.forEach { it.setupWindowListeners() }
  }

  private fun IWorkbenchWindow.setupWindowListeners() {
    addPageListener(createPageListener { it.setupPageListeners() })
    pages.forEach { it.setupPageListeners() }
  }

  private fun IWorkbenchPage.setupPageListeners() {
    addPartListener(
      createPartListener(
        onOpened = { handleEditorPart(it, ::startSession) },
        onClosed = { handleEditorPart(it, ::endSession) }
      )
    )
  }

  private fun handleEditorPart(
    partRef: IWorkbenchPartReference?,
    action: (ITextEditor) -> Unit
  ) {
    val editor = (partRef as? IEditorReference)?.getEditor(false)

    if (editor is ITextEditor) {
      action(editor)
    }
  }

  private fun startSession(editor: ITextEditor) {
    editorSessions[editor] = createCodeSuggestionsSession(editor)
    logger.info("Code Suggestions session created for ${editor.title}.")
  }

  private fun endSession(editor: ITextEditor) {
    val session = editorSessions[editor]

    if (session != null) {
      session.dispose()
      editorSessions.remove(editor)
      logger.info("Code Suggestions session ended for ${editor.title}.")
    } else {
      logger.info("No Code Suggestions session found for ${editor.title}.")
    }
  }

  private fun createWindowListener(onOpened: (IWorkbenchWindow) -> Unit): IWindowListener =
    object : IWindowListener {
      override fun windowOpened(window: IWorkbenchWindow?) {
        window?.let(onOpened)
      }

      override fun windowActivated(window: IWorkbenchWindow?) {}
      override fun windowDeactivated(window: IWorkbenchWindow?) {}
      override fun windowClosed(window: IWorkbenchWindow?) {}
    }

  private fun createPageListener(onOpened: (IWorkbenchPage) -> Unit): IPageListener =
    object : IPageListener {
      override fun pageOpened(page: IWorkbenchPage?) {
        page?.let(onOpened)
      }

      override fun pageActivated(page: IWorkbenchPage?) {}
      override fun pageClosed(page: IWorkbenchPage?) {}
    }

  private fun createPartListener(
    onOpened: (IWorkbenchPartReference) -> Unit,
    onClosed: (IWorkbenchPartReference) -> Unit
  ): IPartListener2 =
    object : IPartListener2 {
      override fun partOpened(partRef: IWorkbenchPartReference?) {
        partRef?.let(onOpened)
      }

      override fun partClosed(partRef: IWorkbenchPartReference) {
        onClosed(partRef)
      }

      override fun partActivated(partRef: IWorkbenchPartReference?) {}
      override fun partBroughtToTop(partRef: IWorkbenchPartReference?) {}
      override fun partDeactivated(partRef: IWorkbenchPartReference?) {}
      override fun partHidden(partRef: IWorkbenchPartReference?) {}
      override fun partVisible(partRef: IWorkbenchPartReference?) {}
      override fun partInputChanged(partRef: IWorkbenchPartReference?) {}
    }

  //endregion
}
