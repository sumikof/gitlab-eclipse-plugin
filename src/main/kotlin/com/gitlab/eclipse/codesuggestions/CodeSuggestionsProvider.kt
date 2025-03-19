package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.messages.InlineCompletionContext
import com.gitlab.eclipse.lsp.messages.InlineCompletionParams
import com.gitlab.eclipse.lsp.messages.InlineCompletionTriggerKind
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.logger
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.future.asDeferred
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

class CodeSuggestionsProvider(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper,
  private val codeFormatter: CodeFormatter
) {
  companion object {
    private const val SUGGESTION_ACCEPTED_COMMAND = "gitlab.ls.codeSuggestionAccepted"
    private const val START_STREAMING_COMMAND = "gitlab.ls.startStreaming"
  }

  private val logger by lazy { logger<CodeSuggestionsProvider>() }

  private var ongoingRequest: CompletableFuture<*>? = null

  suspend fun provide(
    fileUri: String,
    cursorLine: Int,
    cursorColumn: Int
  ): CodeSuggestion? {
    try {
      val languageServer: GitLabLanguageServer = checkNotNull(gitLabLanguageServerWrapper.languageServer)

      ongoingRequest?.cancel(true)

      val request = languageServer.inlineCompletion(
        InlineCompletionParams(
          textDocumentIdentifier = TextDocumentIdentifier(fileUri),
          cursorPosition = Position(cursorLine, cursorColumn),
          context = InlineCompletionContext(InlineCompletionTriggerKind.AUTOMATIC)
        )
      ).also { ongoingRequest = it }

      val result = request.asDeferred().await()

      return (result.left ?: result.right?.items)
        ?.map { it.createCodeSuggestion() }
        ?.firstOrNull()
    } catch (_: CancellationException) {
      ongoingRequest?.cancel(true)
      return null
    } catch (e: Throwable) {
      logger.error("Error providing code suggestions.", e)
      return null
    }
  }

  private fun CompletionItem.createCodeSuggestion(): CodeSuggestion {
    val streamId: String? = when (command?.command) {
      START_STREAMING_COMMAND -> (command.arguments?.getOrNull(0) as? JsonPrimitive)?.asString
      else -> null
    }

    val uniqueTrackingId: String? = when (command?.command) {
      SUGGESTION_ACCEPTED_COMMAND -> (command.arguments?.getOrNull(0) as? JsonPrimitive)?.asString
      START_STREAMING_COMMAND -> (command.arguments?.getOrNull(1) as? JsonPrimitive)?.asString
      else -> null
    }

    val optionId: Int? = when (command?.command) {
      SUGGESTION_ACCEPTED_COMMAND -> (command.arguments?.getOrNull(1) as? JsonPrimitive)?.asInt
      else -> null
    }

    return CodeSuggestion(
      streamId = streamId,
      trackingId = uniqueTrackingId,
      optionId = optionId,
      text = codeFormatter.format(insertText)
    )
  }
}
