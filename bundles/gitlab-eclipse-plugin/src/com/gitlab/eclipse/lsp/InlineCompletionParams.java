package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;

/** LSP 3.18 textDocument/inlineCompletion request params (subset we need). */
public record InlineCompletionParams(TextDocumentIdentifier textDocument, Position position,
		InlineCompletionContext context) {}
