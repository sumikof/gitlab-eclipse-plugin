package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.dismissActiveCodeSuggestion
import com.gitlab.eclipse.codesuggestions.refreshCodeSuggestionsToggle
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.menus.UIElement
import org.eclipse.ui.preferences.ScopedPreferenceStore
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ToggleCodeSuggestionsHandlerTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxUnitFun = true)
  val configurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)
  val uiElement = mockk<UIElement>(relaxUnitFun = true)
  val executionEvent = mockk<ExecutionEvent>()

  val handler = ToggleCodeSuggestionsHandler()

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.codesuggestions.CodeSuggestionsToggleStatusKt")
    mockkStatic("com.gitlab.eclipse.codesuggestions.CodeSuggestionsDismissKt")
    startKoin {
      modules(
        module {
          single { preferenceStore }
          single { configurationService }
        },
      )
    }
  }

  beforeEach {
    every { refreshCodeSuggestionsToggle() } returns Unit
    every { dismissActiveCodeSuggestion() } returns Unit
  }

  afterEach { clearAllMocks() }
  afterSpec {
    stopKoin()
    unmockkAll()
  }

  describe("execute") {
    it("turns off when currently enabled, sends config, dismisses active suggestion") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns true

      handler.execute(executionEvent)

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ENABLED, "false")
        configurationService.sendConfiguration()
        dismissActiveCodeSuggestion()
        refreshCodeSuggestionsToggle()
      }
    }

    it("turns on when currently disabled and does not dismiss") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns false

      handler.execute(executionEvent)

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ENABLED, "true")
        configurationService.sendConfiguration()
        refreshCodeSuggestionsToggle()
      }
      verify(exactly = 0) { dismissActiveCodeSuggestion() }
    }
  }

  describe("updateElement") {
    it("shows Disable when enabled") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns true
      handler.updateElement(uiElement, emptyMap<Any, Any>())
      verify { uiElement.setText("Disable Code Suggestions") }
    }
    it("shows Enable when disabled") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns false
      handler.updateElement(uiElement, emptyMap<Any, Any>())
      verify { uiElement.setText("Enable Code Suggestions") }
    }
  }
})
