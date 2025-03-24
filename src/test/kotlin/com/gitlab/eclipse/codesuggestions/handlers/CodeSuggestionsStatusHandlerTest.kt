package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.isCodeSuggestionsApiAvailable
import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.utils.PlatformUtils
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.system.SystemUtils
import com.gitlab.eclipse.utils.theming.IconTone
import com.gitlab.eclipse.utils.theming.ThemeUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.swt.custom.StyledText
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.dsl.module

class CodeSuggestionsStatusHandlerTest : DescribeSpec({
  val textEditor = mockk<ITextEditor>(relaxed = true)
  val textWidget = mockk<StyledText>(relaxed = true)
  val element = mockk<UIElement>(relaxUnitFun = true)

  val platformUtils = mockk<PlatformUtils>()
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()

  val handler by lazy { CodeSuggestionsStatusHandler() }

  beforeSpec {
    mockkObject(SystemUtils)
    mockkObject(ThemeUtils)
    mockkObject(NotificationUtils)
    mockkStatic("com.gitlab.eclipse.utils.DisplayKt")

    startKoin {
      modules(
        module {
          single<PlatformUtils> { platformUtils }
          single<CodeSuggestionsStateService> { codeSuggestionsStateService }
        }
      )
    }
  }

  beforeEach {
    isCodeSuggestionsApiAvailable = true

    every { ThemeUtils.getThemedIcon(any()) } returns mockk<ImageDescriptor>()
    every { ThemeUtils.getThemedIcon(any(), any()) } returns mockk<ImageDescriptor>()

    every { NotificationUtils.show(any()) } returns Unit

    every { currentDisplay.syncExec(any()) } answers { firstArg<Runnable>().run() }

    every { platformUtils.getActiveTextEditor() } returns textEditor
    every { platformUtils.getTextWidget(textEditor) } returns textWidget

    every { SystemUtils.isWindows() } returns false
  }

  afterEach {
    isCodeSuggestionsApiAvailable = true
    clearAllMocks()
  }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("execute") {
    it("should focus the active text editor") {
      every { platformUtils.getActiveTextEditor() } returns textEditor
      every { platformUtils.getTextWidget(textEditor) } returns textWidget

      handler.execute(mockk())

      verify { textWidget.setFocus() }
    }

    it("should show a notification when there is no active text editor") {
      every { platformUtils.getActiveTextEditor() } returns null

      handler.execute(mockk())

      verify { NotificationUtils.show("No active editor. Open a file to use Duo Code Suggestions.") }
    }
  }

  it("should be enabled only when chat is enabled") {
    every { codeSuggestionsStateService.isEnabled } returns true
    handler.isEnabled() shouldBe true

    every { codeSuggestionsStateService.isEnabled } returns false
    handler.isEnabled() shouldBe false
  }

  describe("updateElement") {
    it("should always display dark icon on windows") {
      every { codeSuggestionsStateService.getFirstEngagedCheck() } returns null
      every { SystemUtils.isWindows() } returns true
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("duo_on_edit", IconTone.DARK)
        element.setText("Code Suggestions: Enabled")
        element.setIcon(any())
      }
    }

    it("should show unavailable status when API is unavailable") {
      every { codeSuggestionsStateService.getFirstEngagedCheck() } returns null
      isCodeSuggestionsApiAvailable = false

      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("duo_off_edit")
        element.setText("Code Suggestions: Unavailable")
        element.setIcon(any())
      }
    }

    it("should show enabled status when API is available and code suggestions has no engaged check") {
      every { codeSuggestionsStateService.getFirstEngagedCheck() } returns null
      isCodeSuggestionsApiAvailable = true

      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("duo_on_edit")
        element.setText("Code Suggestions: Enabled")
        element.setIcon(any())
      }
    }

    it("should show disabled status when a code suggestions check is engaged") {
      every { codeSuggestionsStateService.getFirstEngagedCheck() } returns FeatureStateChangeCheck(
        checkId = "authentication-required",
        engaged = true
      )
      handler.updateElement(element, mutableMapOf())

      verify {
        ThemeUtils.getThemedIcon("duo_off_edit")
        element.setText("Code Suggestions: Disabled (authentication-required)")
        element.setIcon(any())
      }
    }
  }
})
