package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.messages.StreamWithId
import com.gitlab.eclipse.lsp.messages.StreamingCompletionResponse
import com.gitlab.eclipse.utils.CodeFormatter
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class StreamingCodeSuggestionsManagerTest : DescribeSpec({
  val languageServer = mockk<GitLabLanguageServer>(relaxUnitFun = true)
  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>()

  val unformattedCode = "unformatted-code"
  val formattedCode = "formatted-code"
  val codeFormatter = mockk<CodeFormatter>()

  val listener = mockk<StreamingCodeSuggestionsListener>(relaxUnitFun = true)
  var manager = StreamingCodeSuggestionsManager(languageServerWrapper, codeFormatter)

  extensions(LoggingKotestExtension)

  beforeEach {
    every { codeFormatter.format(unformattedCode) } returns formattedCode
    every { languageServerWrapper.languageServer } returns languageServer

    manager = StreamingCodeSuggestionsManager(languageServerWrapper, codeFormatter)
  }

  afterEach {
    clearAllMocks()
  }

  describe("register") {
    it("should register a listener for a stream") {
      manager.register("streamId", listener)
      manager.receive(StreamingCompletionResponse("streamId", unformattedCode, done = false))

      verify { listener.onSuggestionStreamUpdate("streamId", formattedCode) }
    }
  }

  describe("receive") {
    it("should notify listener of updates with the new formatted code") {
      manager.register("streamId", listener)
      manager.receive(StreamingCompletionResponse("streamId", unformattedCode, done = false))

      verify(exactly = 1) { listener.onSuggestionStreamUpdate("streamId", formattedCode) }
    }

    it("should notify listener when stream is complete") {
      manager.register("streamId", listener)
      manager.receive(StreamingCompletionResponse("streamId", unformattedCode, done = true))

      verify(exactly = 1) { listener.onSuggestionStreamComplete() }
    }

    it("should ignore responses for unknown streams") {
      manager.register("unknownStreamId", listener)
      manager.receive(StreamingCompletionResponse("differentStreamId", unformattedCode, done = false))

      verify(exactly = 0) { listener.onSuggestionStreamUpdate(any(), any()) }
      verify(exactly = 0) { listener.onSuggestionStreamComplete() }
    }
  }

  describe("cancel") {
    it("should cancel stream and notify language server") {
      manager.register("streamId", listener)

      manager.cancel("streamId")

      verify { languageServer.cancelStreaming(StreamWithId("streamId")) }
    }

    it("should not cancel unregistered streams") {
      manager.cancel("unknownStreamId")

      verify(exactly = 0) { languageServer.cancelStreaming(any()) }
    }

    it("should handle exceptions from language server") {
      every { languageServer.cancelStreaming(any()) } throws RuntimeException("Test exception")
      manager.register("streamId", listener)

      manager.cancel("streamId")

      verify { languageServer.cancelStreaming(StreamWithId("streamId")) }
    }
  }
})
