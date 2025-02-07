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

  /**
   * org.eclipse.jface.text.BadLocationException
   * 	at org.eclipse.jface.text.ListLineTracker.getLineOffset(ListLineTracker.java:197)
   * 	at org.eclipse.jface.text.AbstractLineTracker.getLineOffset(AbstractLineTracker.java:252)
   * 	at org.eclipse.jface.text.AbstractDocument.getLineOffset(AbstractDocument.java:877)
   * 	at org.eclipse.core.internal.filebuffers.SynchronizableDocument.getLineOffset(SynchronizableDocument.java:323)
   * 	at org.eclipse.jface.text.codemining.LineEndCodeMining.getLineEndPosition(LineEndCodeMining.java:29)
   * 	at org.eclipse.jface.text.codemining.LineEndCodeMining.<init>(LineEndCodeMining.java:25)
   * 	at com.gitlab.eclipse.codesuggestions.CodeSuggestionMining.<init>(CodeSuggestionMining.kt:24)
   * 	at com.gitlab.eclipse.codesuggestions.CodeSuggestionMining.<init>(CodeSuggestionMining.kt:19)
   * 	at com.gitlab.eclipse.codesuggestions.CodeSuggestionsMiningProvider.provideCodeMinings(CodeSuggestionsMiningProvider.kt:22)
   */
  override fun doResolve(viewer: ITextViewer?, monitor: IProgressMonitor?): CompletableFuture<Void> {
    logger.info("${System.currentTimeMillis()}")

    return CompletableFuture.runAsync {
//      Thread.sleep(1000)
      logger.info("Inside runAsync: ${System.currentTimeMillis()}")
      label = "  ${System.currentTimeMillis()}  "
    }
  }

//  private fun completionResult(filePath: String, textSelection: ITextSelection) = try {
//    gitLabLanguageServerWrapper.languageServer?.textDocumentService?.completion(
//      CompletionParams(
//        TextDocumentIdentifier(filePath),
//        Position(textSelection.endLine, textSelection.offset),
//        // TODO: Stop hardcoding triggerCharacter
//        CompletionContext(CompletionTriggerKind.TriggerCharacter, ".")
//      )
//    ) ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
//  } catch (ex: Throwable) {
//    logger.error("Failed to fetch code suggestions from language server", ex)
//    CompletableFuture.completedFuture(Either.forLeft(emptyList()))
//  }
}
