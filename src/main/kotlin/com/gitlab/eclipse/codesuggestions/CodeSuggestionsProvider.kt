package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.lsp.GitLabLanguageServer
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.messages.InlineCompletionContext
import com.gitlab.eclipse.lsp.messages.InlineCompletionParams
import com.gitlab.eclipse.lsp.messages.InlineCompletionTriggerKind
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier

class CodeSuggestionsProvider(
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper,
  private val codeFormatter: CodeFormatter
) {
  private val logger by lazy { logger<CodeSuggestionsProvider>() }

  suspend fun provide(
    fileUri: String,
    cursorLine: Int,
    cursorColumn: Int
  ): String? {
    try {
      val languageServer: GitLabLanguageServer = checkNotNull(gitLabLanguageServerWrapper.languageServer)

      val result = languageServer.inlineCompletion(
        InlineCompletionParams(
          textDocumentIdentifier = TextDocumentIdentifier(fileUri),
          cursorPosition = Position(cursorLine, cursorColumn),
          // InlineCompletionTriggerKind.INVOKED prevents Streaming Code suggestions from being triggered.
          context = InlineCompletionContext(InlineCompletionTriggerKind.INVOKED)
        )
      ).await()

      val choices = (result.left ?: result.right?.items)
        ?.distinctBy { it.insertText }
        .orEmpty()

      // TODO: Cycle through all suggestions. Currently we only use the first one.

      val suggestion = choices
        .firstOrNull()
        ?.insertText
        ?.let(codeFormatter::format)

      logger.info("Got suggestion from language server: $suggestion")

      return suggestion
    } catch (e: Throwable) {
      logger.warn("Failed to provide code suggestions", e)
      return null
    }
  }
}
