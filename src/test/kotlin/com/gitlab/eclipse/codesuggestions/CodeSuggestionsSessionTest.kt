package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.codesuggestions.status.CodeSuggestionsStateService
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.CodeSuggestionsApiStatusService
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.eclipse.swt.custom.StyledText
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class CodeSuggestionsSessionTest : DescribeSpec({
  val textWidget = mockk<StyledText>(relaxed = true)
  val codeSuggestionsProvider = mockk<CodeSuggestionsProvider>(relaxed = true)

  val codeSuggestionsStateService = mockk<CodeSuggestionsStateService>()
  val codeSuggestionsApiStatusService = mockk<CodeSuggestionsApiStatusService>()

  var session = CodeSuggestionsSession(textWidget, codeSuggestionsProvider)

  extensions(LoggingKotestExtension)

  beforeSpec {
    mockkConstructor(CodeSuggestionsRenderer::class)

    startKoin {
      modules(
        module {
          single<CodeSuggestionsStateService> { codeSuggestionsStateService }
          single<CodeSuggestionsApiStatusService> { codeSuggestionsApiStatusService }
        }
      )
    }
  }

  beforeEach {
    every { codeSuggestionsStateService.isEnabled } returns true
    every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Recovery

    session = CodeSuggestionsSession(textWidget, codeSuggestionsProvider)
  }

  afterEach {
    clearAllMocks()
    session.dispose()
  }

  afterSpec {
    unmockkAll()
    stopKoin()
  }

  describe("requestCodeSuggestion") {
    it("should not request code suggestions when enabled and API status is in error") {
      every { codeSuggestionsApiStatusService.apiStatus.value } returns CodeSuggestionsApiStatusService.ApiStatus.Error

      session.requestCodeSuggestion()

      verify(exactly = 0) { codeSuggestionsProvider.provide() }
    }

    it("should not request code suggestions when the feature state is disabled") {
      every { codeSuggestionsStateService.isEnabled } returns false

      session.requestCodeSuggestion()

      verify(exactly = 0) { codeSuggestionsProvider.provide() }
    }

    it("should request code suggestions when the feature is enabled and not in error") {
      every { codeSuggestionsProvider.provide() } returns "Hello"

      session.requestCodeSuggestion()

      verify(exactly = 1) { codeSuggestionsProvider.provide() }
    }
  }
})
