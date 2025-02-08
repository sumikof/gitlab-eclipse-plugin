package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.BadLocationException
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.codemining.ICodeMiningProvider
import org.eclipse.jface.text.codemining.LineEndCodeMining
import java.util.concurrent.CompletableFuture

class LineEndCodeSuggestionMining(
  document: IDocument,
  line: Int,
  provider: ICodeMiningProvider,
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper = service(),
) : LineEndCodeMining(document, line, provider) {
  private val logger = logger<LineEndCodeSuggestionMining>()

  override fun doResolve(viewer: ITextViewer?, monitor: IProgressMonitor?): CompletableFuture<Void> {
    return CompletableFuture.runAsync {
      logger.info("Code Suggestion: ${System.currentTimeMillis()}")
      label = "  ${System.currentTimeMillis()}  "
    }
  }

  companion object {
    @Throws(BadLocationException::class)
    fun create(
      document: IDocument,
      line: Int,
      provider: ICodeMiningProvider
    ): LineEndCodeSuggestionMining? {
      return try {
        LineEndCodeSuggestionMining(document, line, provider)
      } catch (e: BadLocationException) {
        null
      }
    }
  }
}