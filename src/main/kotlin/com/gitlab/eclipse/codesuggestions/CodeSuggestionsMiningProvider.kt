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

        var currentCaretPosition = 0
        var currentLine = 0
        var lineEndOffset = 0

        Display.getDefault().syncExec {
          currentCaretPosition = viewer.textWidget.caretOffset
          currentLine = viewer.textWidget.getLineAtOffset(currentCaretPosition)
          // Get the offset of the end of the current line
          lineEndOffset = viewer.textWidget.getOffsetAtLine(currentLine) + viewer.textWidget.getLine(currentLine).length
        }

        val document = viewer.document ?: return@supplyAsync emptyList()
        val isAtLineEnd = currentCaretPosition == lineEndOffset

        logger.info("Current Caret Position: $currentCaretPosition")
        logger.info("Current Line: $currentLine")
        logger.info("Line End Offset: $lineEndOffset")
        logger.info("Is At Line End: $isAtLineEnd")

        listOfNotNull(
//          if (isAtLineEnd) {
//            LineEndCodeSuggestionMining.create(
//              document = document,
//              line = currentLine,
//              provider = this
//            )
//          } else {
            CodeSuggestionMining(
              position = Position(currentCaretPosition, 1),
              provider = this
            )
//          }
        )
      } catch (e: Exception) {
        logger.error("Unable to provide code minings", e)
        emptyList()
      }
    }
  }
}
