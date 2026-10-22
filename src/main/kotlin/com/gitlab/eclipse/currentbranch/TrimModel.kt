package com.gitlab.eclipse.currentbranch

/** Icon of one status-bar item; the SWT layer maps each value to a themed image. */
enum class TrimIcon {
  SUCCESS, FAILED, RUNNING, PENDING, CANCELED, SKIPPED, MANUAL, UNKNOWN,
  INFO_LOADING, NO_PIPELINE, MR_OPEN, MR_DRAFT, MR_CREATE, ISSUE,
}

/** What clicking a status-bar item does. */
sealed interface TrimAction {
  /** Not actionable. */
  data object None : TrimAction

  /** Opens the CI actions pulldown under the item. */
  data object ShowPipelineMenu : TrimAction

  /** Opens [url] in the browser. */
  data class OpenUrl(val url: String) : TrimAction

  /** Runs the existing Create-MR command. */
  data object CreateMergeRequest : TrimAction
}

/** One rendered status-bar item. */
data class TrimItemModel(val icon: TrimIcon, val text: String, val tooltip: String, val action: TrimAction)

/** The three status-bar items of a window; a `null` item is hidden. */
data class TrimModel(val pipeline: TrimItemModel?, val mr: TrimItemModel?, val issue: TrimItemModel?) {
  companion object {
    val HIDDEN = TrimModel(null, null, null)
  }
}
