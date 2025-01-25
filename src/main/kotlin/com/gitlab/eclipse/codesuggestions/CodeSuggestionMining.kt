package com.gitlab.eclipse.codesuggestions

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.utils.TextEditorProvider
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jface.text.IDocument
import org.eclipse.jface.text.ITextSelection
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.codemining.ICodeMiningProvider
import org.eclipse.jface.text.codemining.LineEndCodeMining
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.ui.IFileEditorInput
import java.util.concurrent.CompletableFuture

class CodeSuggestionMining(
  document: IDocument,
  line: Int,
  provider: ICodeMiningProvider,
  private val gitLabLanguageServerWrapper: GitLabLanguageServerWrapper = service(),
) : LineEndCodeMining(document, line, provider) {
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
    val nullFuture = CompletableFuture.completedFuture<Void>(null)

    return currentDisplay.syncCall<CompletableFuture<Void>, Exception> {
      try {
        val editor = TextEditorProvider().getActiveTextEditor() ?: return@syncCall nullFuture.thenAccept { label = "null from editor" }
        val fileEditorInput = editor.editorInput as? IFileEditorInput ?: return@syncCall nullFuture.thenAccept { label = "null from file editor input" }
        val textSelection = editor.selectionProvider.selection as? ITextSelection ?: return@syncCall nullFuture.thenAccept { label = "null text selection" }

        val filePath = fileEditorInput.file.fullPath.toFile().toURI().toString()
        return@syncCall completionResult(filePath, textSelection).thenAccept {
          label = if (it.isLeft) {
            it.left.firstOrNull()?.insertText ?: "Left was chosen."
          } else {
            it.right.items.firstOrNull()?.insertText ?: "Right was chosen."
          }
        }
      } catch (ex: Throwable) {
        logger.warn("Failed to resolve code suggestions", ex)
      }

      return@syncCall nullFuture.thenAccept { label = "null from sync call end" }
    }
  }

  private fun completionResult(filePath: String, textSelection: ITextSelection) = try {
    gitLabLanguageServerWrapper.languageServer?.textDocumentService?.completion(
      CompletionParams(
        TextDocumentIdentifier(filePath),
        Position(textSelection.endLine, textSelection.offset),
        // TODO: Stop hardcoding triggerCharacter
        CompletionContext(CompletionTriggerKind.TriggerCharacter, ".")
      )
    ) ?: CompletableFuture.completedFuture(Either.forLeft(emptyList()))
  } catch (ex: Throwable) {
    logger.error("Failed to fetch code suggestions from language server", ex)
    CompletableFuture.completedFuture(Either.forLeft(emptyList()))
  }
}
