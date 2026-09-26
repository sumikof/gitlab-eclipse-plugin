package com.gitlab.eclipse.codesuggestions.tutorial

import com.gitlab.eclipse.diagnostics.FeatureStateLabels

/** Every user-facing string of the Tutorial command, as fixed by design §9.2 and R7. */
object DuoTutorialMessages {
  const val DIALOG_TITLE: String = "GitLab Duo Tutorial"

  /** The quoted label is `GitLabPreferencePage`'s, verbatim, so the user can find the checkbox. */
  const val PREFERENCES_QUESTION: String =
    "The GitLab Duo Tutorial needs \"Enable Duo features when no GitLab project is detected\" in the " +
      "GitLab preferences. Open the preferences now?"

  const val PROJECT_CLOSED: String =
    "A project named 'GitLab Duo Tutorial' exists but is closed. Open it and run the command again, or rename it."
  const val NOT_OWNED: String =
    "A project named 'GitLab Duo Tutorial' already exists and was not created by GitLab. Rename it to use the tutorial."

  /**
   * The project or the file disappeared between the job's `Ready` and the UI turn (deleted, not
   * replaced — a replacement reads as [NOT_OWNED]). Not a §9.2 row of its own; re-running rebuilds it.
   */
  const val TUTORIAL_CHANGED: String =
    "The GitLab Duo Tutorial project changed before it could be opened. Run the command again."

  const val CREATE_FAILED: String = "Could not create the GitLab Duo Tutorial project. See the Error Log."
  const val OPEN_FAILED: String = "Could not open the GitLab Duo Tutorial. See the Error Log."

  const val CODE_SUGGESTIONS_OFF: String =
    "Code Suggestions is turned off. Turn it on from the GitLab Duo status menu to try the completion steps."

  fun refusal(reason: RefuseReason): String = when (reason) {
    RefuseReason.PROJECT_CLOSED -> PROJECT_CLOSED
    RefuseReason.NOT_OWNED -> NOT_OWNED
  }

  /**
   * `code_suggestions` checks that describe one document rather than the environment (ruling R5).
   *
   * The language checks are one check object in the language server whose id flips between the two
   * language ids; the exclusion check is per file too; and the project Duo-access check is
   * evaluated for the active document's project (`checkIfProjectHasDuoAccess`). All four are
   * evaluated on `didOpen` / set active, so at the moment the Tutorial editor opens they still
   * describe the *previously* active document (or, right after start, no document at all) — and
   * Eclipse's `didOpen` is asynchronous besides. Naming one of them here would tell the user
   * completion is unavailable in a file where it is about to work. Diagnostics hides the language
   * check for the same reason. Ruling R7 added the project check.
   */
  val DOCUMENT_SCOPED_CHECK_IDS: Set<String> = setOf(
    "code-suggestions-document-unsupported-language",
    "code-suggestions-document-disabled-language",
    "code-suggestions-file-excluded",
    "duo-disabled-for-project",
  )

  /**
   * R7's notice when Code Suggestions will not run in the editor just opened, or null when it will.
   *
   * The local setting wins when both apply (Codex round 23 P1): it is the one the user can fix from
   * the status menu. Otherwise the first engaged check that is not document-scoped
   * ([DOCUMENT_SCOPED_CHECK_IDS]) is named through [FeatureStateLabels] and sent to diagnostics,
   * never to the toggle, which would not fix it. Only document-scoped checks engaged ⇒ no notice.
   */
  fun codeSuggestionsNotice(localEnabled: Boolean, engagedCheckIds: List<String>): String? {
    if (!localEnabled) return CODE_SUGGESTIONS_OFF
    val checkId = engagedCheckIds.firstOrNull { it !in DOCUMENT_SCOPED_CHECK_IDS } ?: return null
    return "Code Suggestions is unavailable: ${FeatureStateLabels.labelFor(checkId)}. " +
      "Open GitLab Duo diagnostics for details."
  }
}
