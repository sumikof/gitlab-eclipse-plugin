package com.gitlab.eclipse.chat.webview

/**
 * Payload of the Agent Platform webview's `switchToClassicLayout` notification. The language server
 * strips the survey's `feedback` and forwards `{hideSurvey}` only (AGENT-02 design §3.3).
 *
 * [hideSurvey] mirrors the reference contract and is read by nothing: the survey is never opened
 * here (design U-B). Nullable with a default so a payload without it still parses.
 */
data class SwitchToClassicLayoutNotification(val hideSurvey: Boolean? = null)
