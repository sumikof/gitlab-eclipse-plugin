package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.codesuggestions.refreshCodeSuggestionsToggle
import com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationService
import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.utils.PlatformUtils
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
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ToggleCodeSuggestionsHandlerTest : DescribeSpec({
  val preferenceStore = mockk<ScopedPreferenceStore>(relaxUnitFun = true)
  val configurationService = mockk<GitLabLanguageServerConfigurationService>(relaxUnitFun = true)
  val platformUtils = mockk<PlatformUtils>()
  val codeSuggestionsManager = mockk<CodeSuggestionsManager>()
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)
  val uiElement = mockk<UIElement>(relaxUnitFun = true)
  val executionEvent = mockk<ExecutionEvent>()
  val editor = mockk<ITextEditor>()

  val handler = ToggleCodeSuggestionsHandler()

  beforeSpec {
    mockkStatic("com.gitlab.eclipse.codesuggestions.CodeSuggestionsToggleStatusKt")
    startKoin {
      modules(module {
        single { preferenceStore }
        single { configurationService }
        single { platformUtils }
        single { codeSuggestionsManager }
      })
    }
  }

  beforeEach {
    every { refreshCodeSuggestionsToggle() } returns Unit
    every { platformUtils.getActiveTextEditor() } returns editor
    every { codeSuggestionsManager.getOrCreateSession(editor) } returns session
    every { session.isCodeSuggestionDisplayed() } returns true
  }

  afterEach { clearAllMocks() }
  afterSpec { stopKoin(); unmockkAll() }

  describe("execute") {
    it("turns off when currently enabled, sends config, dismisses active suggestion") {
      every { preferenceStore.getBoolean(PreferenceConstants.CODE_SUGGESTIONS_ENABLED) } returns true

      handler.execute(executionEvent)

      verify {
        preferenceStore.putValue(PreferenceConstants.CODE_SUGGESTIONS_ENABLED, "false")
        configurationService.sendConfiguration()
        session.rejectCodeSuggestion()
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
      verify(exactly = 0) { session.rejectCodeSuggestion() }
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
