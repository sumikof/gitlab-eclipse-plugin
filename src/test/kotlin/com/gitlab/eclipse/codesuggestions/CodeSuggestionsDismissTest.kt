package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.PlatformUtils
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.ui.texteditor.ITextEditor
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class CodeSuggestionsDismissTest : DescribeSpec({
  val platformUtils = mockk<PlatformUtils>()
  val codeSuggestionsManager = mockk<CodeSuggestionsManager>()
  val session = mockk<CodeSuggestionsSession>(relaxUnitFun = true)
  val editor = mockk<ITextEditor>()

  beforeSpec {
    startKoin {
      modules(
        module {
          single { platformUtils }
          single { codeSuggestionsManager }
        },
      )
    }
  }

  afterEach { clearAllMocks() }
  afterSpec {
    stopKoin()
    unmockkAll()
  }

  describe("dismissActiveCodeSuggestion") {
    it("cancels the code suggestion for the active editor's session") {
      every { platformUtils.getActiveTextEditor() } returns editor
      every { codeSuggestionsManager.getOrCreateSession(editor) } returns session

      dismissActiveCodeSuggestion()

      verify { session.cancelCodeSuggestion() }
    }

    it("does nothing when there is no active text editor") {
      every { platformUtils.getActiveTextEditor() } returns null

      dismissActiveCodeSuggestion()

      verify(exactly = 0) { codeSuggestionsManager.getOrCreateSession(any()) }
    }
  }
})
