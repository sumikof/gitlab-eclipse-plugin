package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.CodeFormatter
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class CodeSuggestionsProviderTest : DescribeSpec({

  lateinit var languageServerWrapper: GitLabLanguageServerWrapper
  lateinit var languageServer: GitLabLanguageServer
  lateinit var codeFormatter: CodeFormatter
  lateinit var codeSuggestionsProvider: CodeSuggestionsProvider

  val fileUri = "file:///test.kt"
  val cursorLine = 10
  val cursorColumn = 15
  val suggestionText = "val x=5"
  val formattedText = "val x = 5"

  extensions(LoggingKotestExtension)

  beforeTest {
    languageServer = mockk()
    languageServerWrapper = mockk()
    codeFormatter = mockk()

    every { languageServerWrapper.languageServer } returns languageServer
    every { codeFormatter.format(any()) } returns formattedText

    codeSuggestionsProvider = CodeSuggestionsProvider(
      gitLabLanguageServerWrapper = languageServerWrapper,
      codeFormatter = codeFormatter
    )
  }

  fun String.toCompletionItem() = CompletionItem().apply {
    insertText = this@toCompletionItem
  }

  fun List<String>.toCompletionItems() = map { it.toCompletionItem() }

  fun List<String>.asLeftResponse() {
    coEvery { languageServer.inlineCompletion(any()) } returns CompletableFuture.completedFuture(
      Either.forLeft(toCompletionItems())
    )
  }

  fun List<String>.asRightResponse() {
    coEvery { languageServer.inlineCompletion(any()) } returns CompletableFuture.completedFuture(
      Either.forRight(CompletionList(toCompletionItems()))
    )
  }

  describe("provide") {
    it("returns formatted suggestion when language server returns items on the left") {
      listOf(suggestionText).asLeftResponse()

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe formattedText
    }

    it("returns formatted suggestion when language server returns items on the right") {
      listOf(suggestionText).asRightResponse()

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe formattedText
    }

    it("returns first non-duplicate suggestion") {
      listOf(suggestionText, suggestionText, "val y=10").asLeftResponse()

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe formattedText
    }

    it("returns null when language server is null") {
      every { languageServerWrapper.languageServer } returns null

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe null
    }

    it("returns null when language server throws exception") {
      coEvery { languageServer.inlineCompletion(any()) } throws RuntimeException("Test exception")

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe null
    }

    it("returns null when language server returns empty list") {
      emptyList<String>().asLeftResponse()

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe null
    }

    it("returns null when language server returns empty completion list") {
      emptyList<String>().asRightResponse()

      codeSuggestionsProvider.provide(fileUri, cursorLine, cursorColumn) shouldBe null
    }
  }
})
