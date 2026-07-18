package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Range;

public record InlineCompletionItem(String insertText, Range range, InlineCompletionCommand command) {}
