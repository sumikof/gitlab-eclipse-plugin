package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.messages.StreamWithId
import com.gitlab.eclipse.lsp.messages.StreamingCompletionResponse
import com.gitlab.eclipse.utils.CodeFormatter
import com.gitlab.eclipse.utils.logger

class StreamingCodeSuggestionsManager(
  private val languageServerWrapper: GitLabLanguageServerWrapper,
  private val codeFormatter: CodeFormatter
) {
  private val logger by lazy { logger<StreamingCodeSuggestionsManager>() }
  private val inProgressStreams = mutableMapOf<String, StreamingCodeSuggestionsListener>()

  fun register(streamId: String, listener: StreamingCodeSuggestionsListener) {
    inProgressStreams[streamId] = listener
  }

  fun receive(chunk: StreamingCompletionResponse) {
    val listener = inProgressStreams[chunk.id]
      ?: return

    when {
      !chunk.done -> listener.onSuggestionStreamUpdate(codeFormatter.format(chunk.completion))
      else -> complete(chunk.id)
    }
  }

  fun cancel(streamId: String) {
    try {
      inProgressStreams.remove(streamId)
        ?: return

      languageServerWrapper.languageServer?.cancelStreaming(StreamWithId(streamId))
    } catch (e: Exception) {
      logger.error("Failed to cancel stream $streamId", e)
    }
  }

  private fun complete(id: String) {
    val listener = inProgressStreams.remove(id)
      ?: return

    listener.onSuggestionStreamComplete()
  }
}

interface StreamingCodeSuggestionsListener {
  fun onSuggestionStreamUpdate(text: String)
  fun onSuggestionStreamComplete()
}
