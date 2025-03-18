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
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException

class CodeSuggestionsProvider(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper,
  private val codeFormatter: CodeFormatter
) {
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
          // InlineCompletionTriggerKind.INVOKED prevents Streaming Code Suggestions from being triggered.
          // Note that this also cause suggestions to not be cancellable. For development purpose this is ok.
          context = InlineCompletionContext(InlineCompletionTriggerKind.INVOKED)
        )
      ).also { ongoingRequest = it }

      val result = request.asDeferred().await()

      return (result.left ?: result.right?.items)
        ?.distinctBy { it.insertText }
        .orEmpty()
        .map {
          CodeSuggestion(
            trackingId = (it.command?.arguments?.getOrNull(0) as? JsonPrimitive)?.asString,
            optionId = (it.command?.arguments?.getOrNull(1) as? JsonPrimitive)?.asInt,
            text = codeFormatter.format(it.insertText)
          )
        }.firstOrNull()
    } catch (_: CancellationException) {
      ongoingRequest?.cancel(true)
      return null
    } catch (e: Throwable) {
      logger.error("Error providing code suggestions.", e)
      return null
    }
  }
}
