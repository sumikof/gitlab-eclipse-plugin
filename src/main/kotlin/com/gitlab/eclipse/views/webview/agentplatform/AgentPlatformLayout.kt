package com.gitlab.eclipse.views.webview.agentplatform

// Top-level because an enum entry cannot reference its own companion constants during initialization.
private const val NEW_ID = "root/duoAgentPlatform"
private const val CLASSIC_ID = "agentic-tabs"

/**
 * The two Agent Platform layouts the VS Code extension can show: the new `root/duoAgentPlatform`
 * webview and the classic `agentic-tabs` one.
 *
 * [preferenceValue] is what is persisted; [webviewId] is the id the language server resolves
 * `$/gitlab/webview-metadata` for. Both are fixed by the reference extension and the persisted
 * value, so neither may be renamed.
 */
enum class AgentPlatformLayout(val preferenceValue: String, val webviewId: String) {
  NEW("new", NEW_ID),
  CLASSIC("classic", CLASSIC_ID);

  companion object {
    const val NEW_WEBVIEW_ID = NEW_ID
    const val CLASSIC_WEBVIEW_ID = CLASSIC_ID

    /**
     * Exact match on [preferenceValue]; unknown, blank or null falls back to [NEW], so a corrupted
     * or future value never leaves the user without a layout.
     */
    fun fromPreference(value: String?): AgentPlatformLayout = entries.firstOrNull { it.preferenceValue == value } ?: NEW
  }
}
