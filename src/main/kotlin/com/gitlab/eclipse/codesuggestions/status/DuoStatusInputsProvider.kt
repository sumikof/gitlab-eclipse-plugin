package com.gitlab.eclipse.codesuggestions.status

import com.gitlab.eclipse.authentication.AuthState
import com.gitlab.eclipse.codesuggestions.fileLanguageIdOf
import com.gitlab.eclipse.lsp.lifecycle.LanguageServerLifecycleState
import org.eclipse.ui.IWorkbenchWindow

/**
 * Gathers the [DuoStatusInputs] of one workbench window (design §8.1). Called on the UI thread at
 * `updateElement` time; every source is an in-memory read (F5: secure storage and token providers
 * are never read here).
 *
 * The window is the one carried by the rendered element (`UIElement.serviceLocator`, F6), so the
 * trim item and the pulldown's menu item of the same window agree on the active-language row, and
 * two windows can show different states.
 *
 * @param activeLanguageId the Language Server language id of the window's active editor, or null
 *   when it shows no workspace file.
 */
class DuoStatusInputsProvider(
  private val lifecycle: () -> LanguageServerLifecycleState,
  private val authState: () -> AuthState,
  private val codeSuggestionsEnabled: () -> Boolean,
  private val featureStateKnown: () -> Boolean,
  private val primaryCheckId: () -> String?,
  private val isLanguageEnabled: (String) -> Boolean,
  private val activeLanguageId: (IWorkbenchWindow) -> String? = ::activeEditorLanguageId,
) {
  fun inputs(window: IWorkbenchWindow?): DuoStatusInputs = DuoStatusInputs(
    lifecycle = lifecycle(),
    auth = authState(),
    codeSuggestionsEnabled = codeSuggestionsEnabled(),
    activeLanguageEnabled = window?.let(::languageIdOf)?.let(isLanguageEnabled),
    featureStateKnown = featureStateKnown(),
    primaryCheckId = primaryCheckId(),
  )

  /** The resolved status of [window]: the trim uses its icon and tooltip, the menu its label and icon. */
  fun status(window: IWorkbenchWindow?): DuoStatus = DuoStatusResolver.resolve(inputs(window))

  /** A window torn down mid-lookup counts as "no editor" rather than failing the whole render. */
  private fun languageIdOf(window: IWorkbenchWindow): String? =
    try {
      activeLanguageId(window)
    } catch (ignored: RuntimeException) {
      null
    }
}

/** The language id of [window]'s active editor, exactly as didOpen sends it; null without one. */
internal fun activeEditorLanguageId(window: IWorkbenchWindow): String? =
  fileLanguageIdOf(window.activePage?.activeEditor?.editorInput)
