package com.gitlab.eclipse.lsp;

public record InlineCompletionContext(int triggerKind, SelectedCompletionInfo selectedCompletionInfo) {
	public static final int TRIGGER_INVOKED = 1;
	public static final int TRIGGER_AUTOMATIC = 2;
}
