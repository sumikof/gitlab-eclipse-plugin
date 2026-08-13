package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.logger
import org.eclipse.ui.*
import org.eclipse.ui.texteditor.ITextEditor

@Suppress("ParameterListWrapping", "EmptyFunctionBlock", "TooManyFunctions")
internal class CodeSuggestionsManager(
  platformUtils: PlatformUtils,
  private val createCodeSuggestionsSession: (ITextEditor) -> CodeSuggestionsSession,
) {
  private val logger = logger<CodeSuggestionsManager>()
  private val editorSessions = mutableMapOf<ITextEditor, CodeSuggestionsSession>()

  init {
    try {
      platformUtils.getWorkbench().setupWorkbenchListeners()
    } catch (e: Exception) {
      logger.error("Error setting up platform listeners", e)
    }
  }

  fun getOrCreateSession(editor: ITextEditor): CodeSuggestionsSession {
    return editorSessions[editor] ?: startSession(editor)
  }

  /**
   * The session already established for [editor], or null if there is none — **never creates one**.
   *
   * For enablement checks. Those run while the platform recomputes key bindings, so constructing a
   * session there turns any construction failure into an exception thrown out of
   * `BindingManager.computeBindings`, which aborts the computation and leaves commands stuck
   * disabled (issue #74). No session also means no suggestion is displayed, so the answer such a
   * check needs is already known without building anything.
   */
  fun getSession(editor: ITextEditor): CodeSuggestionsSession? = editorSessions[editor]

  /**
   * Whether a suggestion is currently shown in [editor] — the question every command's `isEnabled`
   * actually asks. **Never creates a session and never throws.**
   *
   * Both properties matter. The platform calls `isEnabled` from
   * `BindingManager.computeBindings`, so anything thrown here aborts the binding computation and
   * leaves commands stuck disabled, with no way back short of restarting into a clean workspace
   * (issue #74). No session means nothing is displayed, which is the honest answer anyway.
   */
  fun isSuggestionDisplayed(editor: ITextEditor): Boolean =
    try {
      getSession(editor)?.isCodeSuggestionDisplayed() == true
    } catch (e: Exception) {
      // Type only: this runs on every binding recomputation, so it must stay quiet and cheap.
      logger.error("Could not determine the code suggestion state: ${e.javaClass.name}")
      false
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

  private fun startSession(editor: ITextEditor): CodeSuggestionsSession {
    val newSession = createCodeSuggestionsSession(editor)

    editorSessions[editor] = newSession
    logger.info("Code Suggestions session created for ${editor.title}.")
    return newSession
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
