package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.utils.theming.ThemeUtils
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.jface.resource.ImageDescriptor
import org.eclipse.ui.menus.UIElement
import org.junit.jupiter.api.Assertions.*
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin
import org.koin.dsl.module

class CodeSuggestionsStatusHandlerTest : DescribeSpec({
  val element = mockk<UIElement>(relaxUnitFun = true)
  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()
  val handler = CodeSuggestionsStatusHandler()

  beforeSpec {
    mockkObject(ThemeUtils)

    startKoin {
      modules(
        module {
          single<CodeSuggestionsStateService> { codeSuggestionsStateService }
        }
      )
    }
  }

  beforeEach {
    every { ThemeUtils.getThemedIcon(any()) } returns mockk<ImageDescriptor>()
  }

  afterEach { clearAllMocks() }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("updateElement") {
    it("should show enabled status when code suggestions has no engaged check") {
      every { codeSuggestionsStateService.getFirstEngagedCheck() } returns null
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
