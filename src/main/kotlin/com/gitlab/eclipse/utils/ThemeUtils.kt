package com.gitlab.eclipse.utils

import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.eclipse.ui.themes.ITheme
import java.awt.Color

@Suppress("MagicNumber")
object ThemeUtils {
  fun ITheme.isDarkTheme(): Boolean {
    val color = colorRegistry.get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START")
    val brightness = Color.RGBtoHSB(color.red, color.green, color.blue, null)[2]

    // Use a brightness threshold to determine if a theme is dark or not.
    return brightness < 0.5f
  }

  fun getThemedIcon(iconName: String): ImageDescriptor? {
    val theme = PlatformUI.getWorkbench().themeManager.currentTheme
    val folder = if (theme.isDarkTheme()) "light" else "dark"

    return AbstractUIPlugin.imageDescriptorFromPlugin(
      "com.gitlab.eclipse.gitlab-eclipse-plugin",
      "icons/$folder/$iconName.png"
    )
  }
}
