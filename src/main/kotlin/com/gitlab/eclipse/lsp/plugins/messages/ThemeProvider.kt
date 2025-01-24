package com.gitlab.eclipse.lsp.plugins.messages

import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.graphics.Color
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.themes.ITheme

fun Color.css() = "rgba($red, $green, $blue, $alpha)"

class ThemeProvider {
  // NOTE: The name HAS TO be styles so that is maps to the correct JSON key
  data class Theme(val styles: Map<String, String>)

  companion object {
    private val logger = logger<ThemeProvider>()
    private val ACTIVE_HYPERLINK_COLOR_VARIABLES = listOf("--editor-textLink-foreground-active")
    private val BACKGROUND_VARIABLES = listOf(
      "--editor-alert-background",
      "--editor-background",
      "--editor-button-background",
      "--editor-checkbox-background",
      "--editor-checkbox-background-selected",
      "--editor-dropdown-background",
      "--editor-input-background",
      "--editor-input-background--focus",
      "--editor-textCodeBlock-background",
      "--editor-textPreformat-background",
      "--editor-token-background"
    )
    private val BORDER_VARIABLES = listOf(
      "--editor-alert-border-color",
      "--editor-border-color",
      "--editor-button-border",
      "--editor-checkbox-border",
      "--editor-checkbox-border-selected",
      "--editor-dropdown-border",
      "--editor-input-border",
    )
    private val FOREGROUND_VARIABLES = listOf(
      "--editor-alert-foreground",
      "--editor-button-foreground",
      "--editor-button-foreground--hover",
      "--editor-dropdown-foreground",
      "--editor-error-foreground",
      "--editor-foreground",
      "--editor-foreground--disabled",
      "--editor-foreground--muted",
      "--editor-heading-foreground",
      "--editor-icon-foreground",
      "--editor-input-foreground",
      "--editor-input-foreground--focus",
      "--editor-input-placeholder-foreground",
      "--editor-textPreformat-foreground",
      "--editor-token-foreground"
    )
    private val HOVER_BACKGROUND_VARIABLES = listOf("--editor-button-background--hover", "--editor-widget-shadow")
    private val HYPERLINK_COLOR_VARIABLES = listOf("--editor-textLink-foreground")
    private val INFORMATION_BACKGROUND_VARIABLES = listOf("--editor-background-alternative")

    fun currentTheme(): Theme {
      val eclipseTheme = PlatformUI.getWorkbench().themeManager.currentTheme
      val styles = mutableMapOf<String, String>()

      eclipseTheme.applyCodeFont(styles)
      eclipseTheme.applyColors(styles)
      eclipseTheme.applyFont(styles)

      return Theme(styles)
    }

    /**
     * Set theme variables based on available Eclipse fonts.
     */
    private fun ITheme.applyCodeFont(styles: MutableMap<String, String>) {
      try {
        fontRegistry.getFontData("org.eclipse.jdt.ui.editors.textfont").firstOrNull()?.apply {
          styles["--editor-code-font-family"] = name
          styles["--editor-code-font-size"] = height.toString()
          styles["--editor-code-font-weight"] = style.toString()
        }
      } catch (ex: Exception) {
        logger.warn("Unable to apply Eclipse code font styles for webview.", ex)
      }
    }

    private fun ITheme.applyColors(styles: MutableMap<String, String>) {
      styles.apply {
        putAll(cssColors("ACTIVE_HYPERLINK_COLOR", ACTIVE_HYPERLINK_COLOR_VARIABLES))
        putAll(cssColors("HYPERLINK_COLOR", HYPERLINK_COLOR_VARIABLES))
        putAll(cssColors("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START", BACKGROUND_VARIABLES))
        putAll(cssColors("org.eclipse.ui.workbench.ACTIVE_TAB_OUTLINE_COLOR", BORDER_VARIABLES))
        putAll(cssColors("org.eclipse.ui.workbench.ACTIVE_TAB_TEXT_COLOR", FOREGROUND_VARIABLES))
        putAll(cssColors("org.eclipse.ui.workbench.HOVER_BACKGROUND", HOVER_BACKGROUND_VARIABLES))
        putAll(cssColors("org.eclipse.ui.workbench.INFORMATION_BACKGROUND", INFORMATION_BACKGROUND_VARIABLES))
      }
    }

    /**
     * Apply Eclipse theme color to a given list of CSS variables.
     */
    private fun ITheme.cssColors(colorName: String, variables: List<String>): MutableMap<String, String> {
      val styles = mutableMapOf<String, String>()
      try {
        colorRegistry.get(colorName)?.let { color ->
          variables.forEach { variable ->
            styles[variable] = color.css()
          }
        }
      } catch (ex: Exception) {
        logger.warn("Unable to apply Eclipse color $colorName for webview.", ex)
      }
      return styles
    }

    /**
     * Set theme variables based on available Eclipse fonts.
     */
    private fun ITheme.applyFont(styles: MutableMap<String, String>) {
      try {
        fontRegistry.getFontData("org.eclipse.jface.headerfont").firstOrNull()?.apply {
          styles["--editor-font-family"] = name
          styles["--editor-font-size"] = height.toString()
        }
      } catch (ex: Exception) {
        logger.warn("Unable to apply Eclipse font styles for webview.", ex)
      }
    }
  }
}
