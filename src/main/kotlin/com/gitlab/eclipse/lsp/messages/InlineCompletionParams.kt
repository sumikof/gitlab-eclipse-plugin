package com.gitlab.eclipse.lsp.messages

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionAndWorkDoneProgressAndPartialResultParams

data class InlineCompletionParams(
  val textDocumentIdentifier: TextDocumentIdentifier,
  val cursorPosition: Position,
  val context: InlineCompletionContext
) : TextDocumentPositionAndWorkDoneProgressAndPartialResultParams(textDocumentIdentifier, cursorPosition)

data class InlineCompletionContext private constructor(val triggerKind: Int) {
  constructor(triggerKind: InlineCompletionTriggerKind) : this(triggerKind.ordinal)
}

enum class InlineCompletionTriggerKind {
  INVOKED,
  AUTOMATIC
}
