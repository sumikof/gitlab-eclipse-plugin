package com.gitlab.eclipse.views.webview

/**
 * What [WebviewBrowserHost] asks before its content page navigates. The decision is SWT-free and
 * confined to the UI thread, like the `Browser` whose listeners call it; the host owns every side
 * effect (the refusal, the reload hop, the external launch) so a policy stays a pure judgement that
 * headless tests can drive.
 */
interface WebviewNavigationPolicy {
  /** Called immediately before the host's own `Browser.setUrl(url)`. */
  fun expectLoad(url: String)

  /** Called on the content page's `completed` progress event. */
  fun loadCompleted()

  /** Called inside `changing` after `doit = false` is already set; must name no location in any log. */
  fun decide(location: String?): NavigationDecision

  /** The line the host records for the [NavigationDecision.Refuse] that [decide] just returned. */
  fun refusalNote(): RefusalNote

  /** Whether the engine's own context menu is suppressed (its link entries bypass [decide]). */
  val suppressContextMenu: Boolean

  /** Whether `window.open`, `target=_blank` and ctrl-/middle-clicks are refused. */
  val refuseNewWindows: Boolean
}

/** One answer of [WebviewNavigationPolicy.decide]. No `toString` names a location: it may hold a CSRF token. */
sealed interface NavigationDecision {
  /** Let the engine navigate. */
  data object Allow : NavigationDecision

  /** Refuse; the host records [WebviewNavigationPolicy.refusalNote]. */
  data object Refuse : NavigationDecision

  /** Refuse, and have the host re-resolve the page through the language server (never `Browser.refresh()`). */
  data object Reload : NavigationDecision

  /** Refuse, and have the host open [url] in the external browser. */
  data class External(val url: String) : NavigationDecision {
    override fun toString(): String = "External"
  }
}

/**
 * A refusal line for the host to log. [message] is fixed text chosen by the policy and never
 * contains the location; [isWarning] selects WARN over INFO.
 */
class RefusalNote(val message: String, val isWarning: Boolean = false) {
  override fun toString(): String = "RefusalNote(isWarning=$isWarning)"
}

/**
 * The security details page's [TopLevelNavigationGuard] as a [WebviewNavigationPolicy], with the
 * behaviour the host gave it before the policy existed: only the requested load passes, the
 * context menu is suppressed, new windows are refused, and a refusal while the expected url did
 * not parse is recorded apart from an ordinary one.
 */
class TopLevelNavigationPolicy(private val guard: TopLevelNavigationGuard) : WebviewNavigationPolicy {
  override fun expectLoad(url: String) = guard.expectLoad(url)

  override fun loadCompleted() = guard.loadCompleted()

  override fun decide(location: String?): NavigationDecision =
    if (guard.allows(location)) NavigationDecision.Allow else NavigationDecision.Refuse

  override fun refusalNote(): RefusalNote = if (guard.expectedLoadUnparseable) UNPARSEABLE else BLOCKED

  override val suppressContextMenu: Boolean = true

  override val refuseNewWindows: Boolean = true

  private companion object {
    val UNPARSEABLE = RefusalNote("Blocked a webview navigation: the expected webview url could not be parsed.")
    val BLOCKED = RefusalNote("Blocked a top-level navigation away from the webview.")
  }
}
