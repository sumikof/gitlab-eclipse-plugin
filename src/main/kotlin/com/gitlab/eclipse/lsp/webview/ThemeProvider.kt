package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.utils.ThemeUtils.isDarkTheme
import com.gitlab.eclipse.utils.logger
import org.eclipse.swt.graphics.Color
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.themes.ITheme

class ThemeProvider {
  data class ThemeCSSColors(
    val primaryColor: String,
    val secondaryColor: String,
    val border: String,
    val activeText: String,
    val inactiveText: String,
    val hyperlink: String,
    val activeHyperlink: String,
  )

  companion object {
    private val logger = logger<ThemeProvider>()

    fun currentTheme(): ThemeChangedParams {
      val eclipseTheme = PlatformUI.getWorkbench().themeManager.currentTheme
      val styles = mutableMapOf<String, String>()

      eclipseTheme.applyColors(styles)
      eclipseTheme.applyCodeFont(styles)
      eclipseTheme.applyFont(styles)

      return ThemeChangedParams(styles)
    }

    /**
     * Set theme variables based on available Eclipse colors.
     */
    private fun ITheme.applyColors(styles: MutableMap<String, String>) {
      val themeColors = when {
        this.isDarkTheme() -> getDarkThemeColors()
        else -> getLightThemeColors()
      }

      styles.apply {
        put("--editor-foreground", themeColors.activeText)
        put("--editor-foreground-muted", themeColors.inactiveText)
        put("--editor-foreground-disabled", themeColors.inactiveText)

        // Relates to the background, "alternative" is the main background and the other is the chat message bubble.
        put("--editor-background", themeColors.secondaryColor)
        put("--editor-background-alternative", themeColors.primaryColor)

        put("--editor-border-color", themeColors.border)

        // Relates to shadow around <copy-code> and <insert-code-snippet> elements
        put("--editor-widget-shadow", themeColors.secondaryColor)

        put("--editor-alert-foreground", themeColors.activeText)
        put("--editor-alert-background", themeColors.secondaryColor)
        put("--editor-alert-border-color", themeColors.border)

        put("--editor-token-foreground", themeColors.activeText)
        put("--editor-token-background", themeColors.secondaryColor)

        put("--editor-icon-foreground", themeColors.activeText)

        // Relates to links displayed on screen
        put("--editor-textLink-foreground", themeColors.hyperlink)
        put("--editor-textLink-foreground-active", themeColors.activeHyperlink)

        // Relates to <insert-code-snippet>, <copy-code> and code editor.
        put("--editor-textPreformat-foreground", themeColors.activeText)
        put("--editor-textPreformat-background", themeColors.primaryColor)

        // Relates to the chat box
        put("--editor-input-border", themeColors.border)
        put("--editor-input-background", themeColors.primaryColor)
        put("--editor-input-foreground", themeColors.activeText)
        put("--editor-input-background-focus", themeColors.secondaryColor)
        put("--editor-input-border-focus", themeColors.border)
        put("--editor-input-foreground-focus", themeColors.activeText)

        put("--editor-button-border", themeColors.border)
        put("--editor-button-background", themeColors.secondaryColor)
        put("--editor-button-foreground", themeColors.activeText)
        put("--editor-button-background-selection", themeColors.secondaryColor)
        put("--editor-button-border-selection", themeColors.primaryColor)

        // Relates to predefined prompt in the start screen
        put("--editor-buttonSecondary-background", themeColors.secondaryColor)
        put("--editor-buttonSecondary-foreground", themeColors.activeText)
        put("--editor-buttonSecondary-background-hover", themeColors.primaryColor)

        // Relates to the commands menu
        put("--editor-dropdown-background", themeColors.primaryColor)
        put("--editor-dropdown-foreground", themeColors.activeText)
        put("--editor-dropdown-border", themeColors.border)
        put("--editor-list-activeSelection-background", themeColors.secondaryColor)
        put("--editor-list-activeSelection-foreground", themeColors.activeText)
      }
    }

    /**
     * Set theme variables based on available Eclipse fonts.
     */
    private fun ITheme.applyCodeFont(styles: MutableMap<String, String>) {
      try {
        fontRegistry.getFontData("org.eclipse.jdt.ui.editors.textfont").firstOrNull()?.apply {
          styles["--editor-code-font-family"] = getName().orEmpty()
          styles["--editor-code-font-size"] = getHeight().toString()
          styles["--editor-code-font-weight"] = getStyle().toString()
        }
      } catch (ex: Exception) {
        logger.warn("Unable to apply Eclipse code font styles for webview.", ex)
      }
    }

    /**
     * Set theme variables based on available Eclipse fonts.
     */
    private fun ITheme.applyFont(styles: MutableMap<String, String>) {
      try {
        fontRegistry.getFontData("org.eclipse.jface.headerfont").firstOrNull()?.apply {
          styles["--editor-font-family"] = getName().orEmpty()
          styles["--editor-font-style"] = getStyle().toString()
          styles["--editor-font-size"] = getHeight().toString()
        }
      } catch (ex: Exception) {
        logger.warn("Unable to apply Eclipse font styles for webview.", ex)
      }
    }

    // NOTE: Since there is only one default dark theme, there isn't much flexibility we can give.
    private fun ITheme.getDarkThemeColors() = ThemeCSSColors(
      primaryColor = "rgba(46, 46 , 46, 255)",
      secondaryColor = "rgba(82, 86 , 88, 255)",
      border = "rgba(82, 86 , 88, 255)",
      activeText = color("org.eclipse.ui.workbench.ACTIVE_TAB_TEXT_COLOR"),
      inactiveText = color("org.eclipse.ui.workbench.INACTIVE_TAB_TEXT_COLOR"),
      hyperlink = color("HYPERLINK_COLOR"),
      activeHyperlink = color("ACTIVE_HYPERLINK_COLOR")
    )

    // NOTE: Let's give some fun flexibility for the light theme since there are multiple of them.
    // The only restriction is that the primaryColor should not be the same as the secondaryColor.
    private fun ITheme.getLightThemeColors() = ThemeCSSColors(
      primaryColor = "rgba(255, 255, 255, 255)",
      secondaryColor = when (val color = color("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START")) {
        "rgba(255, 255, 255, 255)" -> "rgba(240, 240 , 240, 255)"
        else -> color
      },
      border = "rgba(240, 240 , 240, 255)",
      activeText = color("org.eclipse.ui.workbench.ACTIVE_TAB_TEXT_COLOR"),
      inactiveText = color("org.eclipse.ui.workbench.INACTIVE_TAB_TEXT_COLOR"),
      hyperlink = color("HYPERLINK_COLOR"),
      activeHyperlink = color("ACTIVE_HYPERLINK_COLOR")
    )

    private fun ITheme.color(colorName: String): String {
      val color = colorRegistry.get(colorName)

      if (color == null) {
        logger.warn("Unable to find Eclipse color $colorName")
        return ""
      }

      return color.css()
    }
  }
}

fun Color.css() = "rgba($red, $green, $blue, $alpha)"
