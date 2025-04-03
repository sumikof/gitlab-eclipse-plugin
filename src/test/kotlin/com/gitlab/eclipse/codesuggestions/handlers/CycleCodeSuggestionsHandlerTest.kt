package com.gitlab.eclipse.codesuggestions.handlers

import com.gitlab.eclipse.BuildConfig
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsManager
import com.gitlab.eclipse.codesuggestions.CodeSuggestionsSession
import com.gitlab.eclipse.codesuggestions.CycleDirection
import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class CycleCodeSuggestionsHandlerTest : DescribeSpec({
  val platformUtils = mockk<PlatformUtils>()
  val codeSuggestionsManager = mockk<CodeSuggestionsManager>()
  val textEditor = mockk<ITextEditor>()
  val session = mockk<CodeSuggestionsSession>()
  val event = mockk<ExecutionEvent>()

  beforeSpec {
    startKoin {
      modules(
        module {
          single<PlatformUtils> { platformUtils }
          single<CodeSuggestionsManager> { codeSuggestionsManager }
        }
      )
    }
  }

  beforeEach {
    every { platformUtils.getActiveTextEditor() } returns textEditor
    every { codeSuggestionsManager.getOrCreateSession(textEditor) } returns session
    every { session.isCodeSuggestionDisplayed() } returns true
    every { session.cycleCodeSuggestion(any()) } returns Unit
    every { event.parameters } returns null
  }

  afterEach {
    clearAllMocks()
  }

  afterSpec {
    stopKoin()
  }

  describe("CycleToNextCodeSuggestionHandler") {
    val nextHandler = CycleToNextCodeSuggestionHandler()

    it("should cycle to next suggestion") {
      nextHandler.execute(event)

      verify { session.cycleCodeSuggestion(CycleDirection.NEXT) }
    }

    // TODO: Remove this once Code Suggestions feature flag is removed
    if (BuildConfig.CODE_SUGGESTIONS_ENABLED) {
      it("should be enabled when code suggestions are displayed") {
        val enabled = nextHandler.isEnabled()

        enabled shouldBe true
      }

      it("should be disabled when no code suggestions are displayed") {
        every { session.isCodeSuggestionDisplayed() } returns false

        val enabled = nextHandler.isEnabled()

        enabled shouldBe false
      }

      it("should be disabled when no active editor") {
        every { platformUtils.getActiveTextEditor() } returns null

        val enabled = nextHandler.isEnabled()

        enabled shouldBe false
      }
    }
  }

  describe("CycleToPreviousCodeSuggestionHandler") {
    val prevHandler = CycleToPreviousCodeSuggestionHandler()

    it("should cycle to previous suggestion") {
      prevHandler.execute(event)

      verify { session.cycleCodeSuggestion(CycleDirection.PREVIOUS) }
    }

    // TODO: Remove this once Code Suggestions feature flag is removed
    if (BuildConfig.CODE_SUGGESTIONS_ENABLED) {
      it("should be enabled when code suggestions are displayed") {
        val enabled = prevHandler.isEnabled()

        enabled shouldBe true
      }

      it("should be disabled when no code suggestions are displayed") {
        every { session.isCodeSuggestionDisplayed() } returns false

        val enabled = prevHandler.isEnabled()

        enabled shouldBe false
      }

      it("should be disabled when no active editor") {
        every { platformUtils.getActiveTextEditor() } returns null

        val enabled = prevHandler.isEnabled()

        enabled shouldBe false
      }
    }
  }
})
