package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.CodeFormatter
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class CodeSuggestionsProviderTest : DescribeSpec({
  val fileUri = "file:///test.kt"
  val cursorLine = 10
  val cursorColumn = 15
  val suggestionText = "val x=5"
  val formattedText = "val x = 5"
  val trackingId = "tracking-123"
  val optionId = 42

  val languageServerWrapper = mockk<GitLabLanguageServerWrapper>()
  val languageServer = mockk<GitLabLanguageServer>()
  val codeFormatter = mockk<CodeFormatter>()

  var codeSuggestionsProvider = CodeSuggestionsProvider(
    gitLabLanguageServerWrapper = languageServerWrapper,
    codeFormatter = codeFormatter
  )

  extensions(LoggingKotestExtension)

  beforeEach {
    every { languageServerWrapper.languageServer } returns languageServer
    every { codeFormatter.format(any()) } returns formattedText

    codeSuggestionsProvider = CodeSuggestionsProvider(
      gitLabLanguageServerWrapper = languageServerWrapper,
      codeFormatter = codeFormatter
    )
  }

  afterEach {
    clearAllMocks()
  }

  fun String.toCompletionItem() = CompletionItem().apply {
    insertText = this@toCompletionItem
    command = Command().apply {
      command = "gitlab.ls.codeSuggestionAccepted"
      arguments = listOf(JsonPrimitive(trackingId), JsonPrimitive(optionId))
    }
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

  describe("provideAutomaticSuggestion") {
    it("returns null when language server is null") {
      every { languageServerWrapper.languageServer } returns null

      codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn).shouldBeNull()
    }

    it("returns null when language server throws exception") {
      coEvery { languageServer.inlineCompletion(any()) } throws RuntimeException("Test exception")

      codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn).shouldBeNull()
    }

    it("returns null when language server returns empty list") {
      emptyList<String>().asLeftResponse()

      codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn).shouldBeNull()
    }

    it("returns a formatted suggestion when language server returns a valid suggestion") {
      listOf(suggestionText).asLeftResponse()

      val result = codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn)

      result?.text shouldBe formattedText
      result?.trackingId shouldBe trackingId
      result?.optionId shouldBe optionId
    }
  }

  describe("provideInvokedSuggestions") {
    it("returns empty list when language server is null") {
      every { languageServerWrapper.languageServer } returns null

      codeSuggestionsProvider.provideInvokedSuggestions(fileUri, cursorLine, cursorColumn).shouldBeEmpty()
    }

    it("returns empty list when language server throws exception") {
      coEvery { languageServer.inlineCompletion(any()) } throws RuntimeException("Test exception")

      codeSuggestionsProvider.provideInvokedSuggestions(fileUri, cursorLine, cursorColumn).shouldBeEmpty()
    }

    it("returns empty list when language server returns empty list") {
      emptyList<String>().asLeftResponse()

      codeSuggestionsProvider.provideInvokedSuggestions(fileUri, cursorLine, cursorColumn).shouldBeEmpty()
    }

    it("returns all suggestions when language server returns a list of CompletionItems") {
      val texts = listOf(suggestionText, "val y=10", "val z=15")
      texts.asLeftResponse()

      every { codeFormatter.format("val y=10") } returns "val y = 10"
      every { codeFormatter.format("val z=15") } returns "val z = 15"

      val results = codeSuggestionsProvider.provideInvokedSuggestions(fileUri, cursorLine, cursorColumn)

      results shouldHaveSize 3
      results[0].text shouldBe formattedText
      results[0].trackingId shouldBe trackingId
      results[0].optionId shouldBe optionId
    }

    it("returns all suggestions when language server returns a CompletionList") {
      val texts = listOf(suggestionText, "val y=10")
      texts.asRightResponse()

      every { codeFormatter.format("val y=10") } returns "val y = 10"

      val results = codeSuggestionsProvider.provideInvokedSuggestions(fileUri, cursorLine, cursorColumn)

      results shouldHaveSize 2
      results[0].text shouldBe formattedText
      results[0].trackingId shouldBe trackingId
      results[0].optionId shouldBe optionId

      results[1].text shouldBe "val y = 10"
      results[1].trackingId shouldBe trackingId
      results[1].optionId shouldBe optionId
    }
  }

  describe("fetchSuggestions") {
    it("cancels ongoing request before making a new one") {
      val firstRequest = CompletableFuture<Either<List<CompletionItem>, CompletionList>>()
      val secondRequest = CompletableFuture.completedFuture(
        Either.forLeft<List<CompletionItem>, CompletionList>(emptyList())
      )
      coEvery { languageServer.inlineCompletion(any()) } returnsMany listOf(firstRequest, secondRequest)

      val job1 = launch { codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn) }
      delay(100)
      val job2 = launch { codeSuggestionsProvider.provideAutomaticSuggestion(fileUri, cursorLine, cursorColumn) }
      job2.join()

      firstRequest.isCancelled shouldBe true
      job1.join()
    }
  }
})
