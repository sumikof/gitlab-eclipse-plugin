package com.gitlab.eclipse.lsp;

import org.eclipse.lsp4j.Range;

public record SelectedCompletionInfo(Range range, String text) {}
