package com.gitlab.eclipse.mergerequests.diff

/** User-facing notification text for each [DiffFailure] (REVIEW-01 design §9). */
object MrDiffMessages {
  fun message(kind: DiffFailure): String =
    when (kind) {
      DiffFailure.CONNECTION_CHANGED -> "The GitLab connection changed. Refresh the sidebar and try again."
      DiffFailure.UNSTABLE_CONNECTION ->
        "The GitLab connection settings were changing; open the diff again."
      DiffFailure.CONFIG -> "The GitLab connection settings are invalid. Check the GitLab preferences."
      DiffFailure.UNAUTHORIZED -> "GitLab rejected the credentials. Sign in again."
      DiffFailure.NOT_FOUND_OR_DENIED ->
        "The file is not available at this revision, or you do not have access. Refresh the merge request."
      DiffFailure.RATE_LIMITED -> "GitLab rate limit reached. Try again later."
      DiffFailure.TIMED_OUT -> "Timed out loading the diff."
      DiffFailure.TOO_LARGE ->
        "This file is too large to compare in Eclipse (over 4 MiB). Open the merge request in the browser."
      DiffFailure.SUBMODULE -> "Submodule changes cannot be compared."
      DiffFailure.INVALID -> "This changed file cannot be opened as a diff. Refresh the merge request."
      DiffFailure.GENERIC -> "Could not load the merge request diff."
    }
}
