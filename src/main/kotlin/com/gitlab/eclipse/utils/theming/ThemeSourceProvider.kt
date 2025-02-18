package com.gitlab.eclipse.utils.theming

import com.gitlab.eclipse.utils.theming.ThemeUtils.isDarkTheme
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources
import org.eclipse.ui.IWindowListener
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.PlatformUI

class ThemeSourceProvider : AbstractSourceProvider() {
  companion object {
    private const val THEME_TONE_KEY = "theme_tone"
  }

  init {
    // The theme is only loaded after the workbench has been created.
    PlatformUI.getWorkbench().addWindowListener(
      object : IWindowListener {
        override fun windowActivated(window: IWorkbenchWindow) = Unit
        override fun windowDeactivated(window: IWorkbenchWindow) = Unit
        override fun windowClosed(window: IWorkbenchWindow) = Unit

        override fun windowOpened(window: IWorkbenchWindow) {
          val workbench = window.workbench
            ?: return

          val tone = when {
            workbench.themeManager.currentTheme.isDarkTheme() -> "dark"
            else -> "light"
          }

          fireSourceChanged(ISources.WORKBENCH, THEME_TONE_KEY, tone)
          workbench.removeWindowListener(this)
        }
      }
    )
  }

  override fun getCurrentState(): Map<String, String> {
    return mapOf(THEME_TONE_KEY to "unknown")
  }

  override fun getProvidedSourceNames() = arrayOf(THEME_TONE_KEY)

  override fun dispose() = Unit
}
