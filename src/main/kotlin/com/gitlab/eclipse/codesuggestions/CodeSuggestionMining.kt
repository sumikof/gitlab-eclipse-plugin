package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.codemining.ICodeMiningProvider
import org.eclipse.jface.text.codemining.LineContentCodeMining
import java.util.concurrent.CompletableFuture

class CodeSuggestionMining(
  position: Position,
  provider: ICodeMiningProvider,
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper = service(),
) : LineContentCodeMining(position, provider) {
  private val logger = logger<CodeSuggestionMining>()

  override fun doResolve(viewer: ITextViewer?, monitor: IProgressMonitor?): CompletableFuture<Void> {
    return CompletableFuture.runAsync {
      logger.info("Code Suggestion: ${System.currentTimeMillis()}")
      label = "${System.currentTimeMillis()}"
    }
  }
}