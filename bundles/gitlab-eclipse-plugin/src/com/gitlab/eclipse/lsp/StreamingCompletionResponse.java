package com.gitlab.eclipse.lsp;

/** gitlab-lsp 'streamingCompletionResponse' notification. {@code completion} is cumulative. */
public record StreamingCompletionResponse(String id, String completion, boolean done) {}
