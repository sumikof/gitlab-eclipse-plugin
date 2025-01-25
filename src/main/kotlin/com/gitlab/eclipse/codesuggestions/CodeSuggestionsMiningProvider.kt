package com.gitlab.eclipse.codesuggestions

import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.codemining.ICodeMining
import org.eclipse.jface.text.codemining.ICodeMiningProvider
import java.util.concurrent.CompletableFuture

class CodeSuggestionsMiningProvider : ICodeMiningProvider {
  override fun dispose() {
    TODO("Not yet implemented")
  }

  override fun provideCodeMinings(
    viewer: ITextViewer,
    monitor: IProgressMonitor
  ): CompletableFuture<List<ICodeMining>> {
    if (monitor.isCanceled) return CompletableFuture.completedFuture(mutableListOf())

    // TODO: Validate we don't trigger a bad location error by using sensible protections around the line number.
    return CompletableFuture.completedFuture(
      mutableListOf(
        CodeSuggestionMining(
          document = viewer.document,
          line = 0,
          provider = this
        )
      )
    )
  }
}
