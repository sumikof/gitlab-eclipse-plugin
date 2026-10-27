package com.gitlab.eclipse.chat.quickchat.fix

/** One diagnostic as sent with `/fix`: its text and its 1-based line. */
data class FixDiagnostic(val message: String, val oneBasedLine: Int)

object FixMessage {
  const val LABEL = "Fix with GitLab Duo"

  /** VS Code `#getFixMessage` (fix_with_duo_quick_chat_action_provider.ts:163-176), verbatim. */
  fun of(diagnostics: List<FixDiagnostic>): String {
    if (diagnostics.isEmpty()) return "/fix"
    fun format(d: FixDiagnostic) = "`${d.message}` on line ${d.oneBasedLine}"
    if (diagnostics.size == 1) return "/fix ${format(diagnostics[0])}"
    return "/fix multiple issues: \n\n" + diagnostics.joinToString("\n", transform = ::format)
  }
}
