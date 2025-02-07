package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.Position
import org.eclipse.jface.text.codemining.ICodeMining
import org.eclipse.jface.text.codemining.ICodeMiningProvider
import org.eclipse.swt.widgets.Display
import java.util.concurrent.CompletableFuture

class CodeSuggestionsMiningProvider : ICodeMiningProvider {
  private val logger = logger<CodeSuggestionsMiningProvider>()

  override fun dispose() {
    // Nothing to dispose
  }

  override fun provideCodeMinings(
    viewer: ITextViewer,
    monitor: IProgressMonitor
  ): CompletableFuture<List<ICodeMining>> {
    return CompletableFuture.supplyAsync {
      try {
        if (monitor.isCanceled) return@supplyAsync emptyList()

        // Get caret position from UI thread
        var currentCaretPosition = 0
        Display.getDefault().syncExec {
          currentCaretPosition = viewer.textWidget.caretOffset
        }

        // Create a Position at the current cursor location
        val position = Position(currentCaretPosition, 1)

        listOf(
          CodeSuggestionMining(
            position = position,
            provider = this
          )
        )
      } catch (e: Exception) {
        logger.error("Unable to provide code minings", e)
        emptyList()
      }
    }
  }
}
