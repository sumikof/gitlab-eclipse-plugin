package com.gitlab.eclipse.diagnostics

/**
 * The language server's own user-readable text for each feature-state check (design §8.3, U3).
 *
 * **Extracted from the bundled language server, not from memory.** The table lives in
 * `STATE_CHECK_USER_READABLE_LABELS` in `@gitlab-org/core`; these values were read out of the
 * shipped bundle's source map at `build/gitlab-lsp/bin/main.js.map` for the pinned version
 * (`package.json` → `@gitlab-org/gitlab-lsp` 9.3.0), together with the `checkId` literals they are
 * keyed by (`const AUTHENTICATION_REQUIRED = "authentication-required"` and its siblings).
 *
 * The reference extension imports this table from the language server package. Eclipse has no such
 * import, so it is transcribed here and [labelFor] falls back to the raw `checkId` for anything a
 * newer server adds — an unfamiliar check still shows up in diagnostics, just under its wire name.
 */
internal object FeatureStateLabels {

  private val LABELS: Map<String, String> = mapOf(
    "authentication-required" to "User is authenticated",
    "invalid-token" to "Token is valid",
    "code-suggestions-authentication-required" to "User is authenticated",
    "code-suggestions-no-license" to "Valid GitLab license",
    "chat-authentication-required" to "User is authenticated",
    "chat-no-license" to "Valid GitLab license",
    "duo-disabled-for-project" to "GitLab Duo is enabled for the open project(s)",
    "code-suggestions-unsupported-gitlab-version" to
      "The GitLab instance version supports Code Suggestions",
    "code-suggestions-document-unsupported-language" to
      "Code suggestions are supported for the current file's language",
    "code-suggestions-document-disabled-language" to
      "Code suggestions are enabled for the current file's language",
    "code-suggestions-api-error" to "Code Suggestions API connection is working",
    "code-suggestions-no-default-namespace" to "User has selected a default GitLab Duo namespace",
    "code-suggestions-no-credits" to
      "User has enough credits to continue Code Suggestions usage for this billing period",
    "chat-disabled-by-user" to "Non-Agentic Chat is enabled in settings",
    "code-suggestions-disabled-by-user" to "Code Suggestions is enabled in settings",
    "code-suggestions-file-excluded" to "Current file is not excluded by project exclusion rules",
    "chat-include-terminal-context-unavailable" to "Include terminal context is enabled for user",
    "agent-platform-disabled-by-user" to "Agent Platform is enabled in settings",
    "agentic-chat-no-support" to "Agentic Chat is supported for the current project",
    "classic-chat-no-license" to "Classic Chat is available with the current GitLab Duo license",
    "flows-instance-flag-disabled" to "Flows feature flag is enabled on the GitLab instance",
    "sandbox-disabled-by-user" to "Process sandboxing is enabled in settings",
    "sandbox-unsupported-platform" to "Process sandboxing is supported on this platform",
    "sandbox-missing-dependencies" to
      "System dependencies required for process sandboxing are installed",
  )

  /**
   * The label for [checkId], or [checkId] itself when the bundled table does not know it.
   *
   * The reference filters unknown ids out of the report entirely. This keeps them: a check the
   * plugin cannot name is exactly the kind of thing a diagnostics reader needs to see.
   */
  fun labelFor(checkId: String): String = LABELS[checkId] ?: checkId

  /**
   * `code-suggestions-document-unsupported-language` is excluded from the report, matching the
   * reference: it reads as engaged on any file the server has no grammar for — markdown included —
   * so it is noise in a report the user takes a screenshot of.
   */
  const val EXCLUDED_CHECK_ID: String = "code-suggestions-document-unsupported-language"
}
