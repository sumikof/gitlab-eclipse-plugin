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
   * R7's notice when Code Suggestions will not run in the editor just opened, or null when it will.
   *
   * The local setting wins when both apply (Codex round 23 P1): it is the one the user can fix from
   * the status menu. An engaged check is named through [FeatureStateLabels] and sent to
   * diagnostics, never to the toggle, which would not fix it.
   */
  fun codeSuggestionsNotice(localEnabled: Boolean, firstEngagedCheckId: String?): String? = when {
    !localEnabled -> CODE_SUGGESTIONS_OFF
    firstEngagedCheckId != null ->
      "Code Suggestions is unavailable: ${FeatureStateLabels.labelFor(firstEngagedCheckId)}. " +
        "Open GitLab Duo diagnostics for details."
    else -> null
  }
}
