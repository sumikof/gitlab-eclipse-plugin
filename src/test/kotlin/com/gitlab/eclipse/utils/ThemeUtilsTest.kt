package com.gitlab.eclipse.utils

import com.gitlab.eclipse.utils.ThemeUtils.isDarkTheme
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.jface.resource.ColorRegistry
import org.eclipse.ui.IWorkbench
import org.eclipse.ui.PlatformUI
import org.eclipse.ui.plugin.AbstractUIPlugin
import org.eclipse.ui.themes.ITheme
import org.eclipse.ui.themes.IThemeManager

class ThemeUtilsTest : DescribeSpec({
  val theme = mockk<ITheme>()
  val colorRegistry = mockk<ColorRegistry>()
  val themeManager = mockk<IThemeManager>()

  val workbench = mockk<IWorkbench>()

  beforeSpec {
    mockkStatic(PlatformUI::getWorkbench)
    mockkStatic(AbstractUIPlugin::imageDescriptorFromPlugin)
  }

  beforeEach {
    every { PlatformUI.getWorkbench() } returns workbench
    every { workbench.themeManager } returns themeManager
    every { themeManager.currentTheme } returns theme
    every { theme.colorRegistry } returns colorRegistry

    every { AbstractUIPlugin.imageDescriptorFromPlugin(any(), any()) } returns mockk()
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    unmockkAll()
  }

  describe("isDarkTheme") {
    it("should be a dark theme if the tab color is dark") {
      val color = mockk<org.eclipse.swt.graphics.Color>()
      every { colorRegistry.get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START") } returns color
      every { color.red } returns 50
      every { color.green } returns 50
      every { color.blue } returns 50

      theme.isDarkTheme() shouldBe true
    }

    it("should not be a dark theme if the tab color is light") {
      val color = mockk<org.eclipse.swt.graphics.Color>()
      every { colorRegistry.get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START") } returns color
      every { color.red } returns 200
      every { color.green } returns 200
      every { color.blue } returns 200

      theme.isDarkTheme() shouldBe false
    }
  }

  describe("getThemedIcon") {
    it("should return light themed icon when theme is dark") {
      val color = mockk<org.eclipse.swt.graphics.Color>()
      every { colorRegistry.get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START") } returns color
      every { color.red } returns 50
      every { color.green } returns 50
      every { color.blue } returns 50

      ThemeUtils.getThemedIcon("test-icon")

      verify {
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/light/test-icon.png"
        )
      }
    }

    it("should return dark themed icon when theme is light") {
      val color = mockk<org.eclipse.swt.graphics.Color>()
      every { colorRegistry.get("org.eclipse.ui.workbench.ACTIVE_TAB_BG_START") } returns color
      every { color.red } returns 200
      every { color.green } returns 200
      every { color.blue } returns 200

      ThemeUtils.getThemedIcon("test-icon")

      verify {
        AbstractUIPlugin.imageDescriptorFromPlugin(
          "com.gitlab.eclipse.gitlab-eclipse-plugin",
          "icons/dark/test-icon.png"
        )
      }
    }
  }
})
