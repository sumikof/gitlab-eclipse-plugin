package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.codesuggestions.status.DuoStatusInputsProvider
import com.gitlab.eclipse.inject.lazyService
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import com.gitlab.eclipse.utils.theming.ThemeUtils
import org.eclipse.core.commands.AbstractHandler
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.swt.SWTException
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.commands.IElementUpdater
import org.eclipse.ui.menus.UIElement

class CodeSuggestionsStatusHandler : AbstractHandler(), IElementUpdater {
  private val stateService by lazyService<CodeSuggestionsStateService>()

  override fun execute(event: ExecutionEvent) {
    val platform = service<PlatformUtils>()
    val activeTextEditor = platform.getActiveTextEditor()

    if (activeTextEditor != null) {
      currentDisplay.syncExec { platform.getTextWidget(activeTextEditor)?.setFocus() }
    } else {
      NotificationUtils.show("No active editor. Open a file to use Duo Code Suggestions.")
    }
  }

  override fun isEnabled() = stateService.isEnabled

  /** The label and icon of the Duo status of the menu's own window (design §8.3), as the trim shows it. */
  override fun updateElement(element: UIElement, parameters: MutableMap<Any?, Any?>) {
    try {
      val window = element.serviceLocator?.getService(IWorkbenchWindow::class.java)
      val status = service<DuoStatusInputsProvider>().status(window)
      element.setText(status.menuLabel)
      element.setIcon(ThemeUtils.getThemedIcon(status.icon.iconName))
    } catch (ignored: SWTException) {
      /* display disposed mid-render: nothing to update */
    } catch (ignored: IllegalStateException) {
      /* workbench closing: nothing to update */
    } catch (e: Exception) {
      // Keep the current label; never break the menu render.
      logger<CodeSuggestionsStatusHandler>().warn("Duo status menu update failed (${e.javaClass.simpleName}).")
    }
  }

  companion object {
    const val API_ERROR_CHECK_ID = "code-suggestions-api-error"
  }
}
