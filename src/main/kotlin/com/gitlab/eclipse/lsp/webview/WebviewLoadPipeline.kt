package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.logger
import java.util.concurrent.CompletableFuture

/**
 * Turns a [WebviewLoadCoordinator.Outcome] into what the surface shows. Design §7.2 / §7.2a /
 * §7.2b / §7.2c / §8.1.
 *
 * Both the branching and the ordering live here; the SWT shell bundles the sinks below and calls
 * [load], and holds neither. The state here is confined to the thread the sinks belong to
 * (design §15) — this class is free of SWT, which is a separate thing.
 */
class WebviewLoadPipeline(
  private val coordinator: WebviewLoadCoordinator,
  private val showUrl: (String) -> Unit,
  private val showMessage: (String) -> Unit,
  private val setTitle: (String) -> Unit,
  /** Whether a url or a message page is already applied. The loading page is not one. Design §7.2a. */
  private val hasStableContent: () -> Boolean,
  /** Shows and hides the loading page. Contracted not to throw. Design §7.2b. */
  private val setLoadingVisible: (Boolean) -> Unit,
  /**
   * Whether the shell is still usable. Design §7.2c, mechanism 2.
   *
   * Answer it from the widget's own disposed state — `{ !container.isDisposed }` — not from a flag
   * the shell sets in its own `dispose`. This is the only thing that stops an application already
   * in flight when the shell goes away without [dispose] being reached, so a flag that is set one
   * line too late, or not at all, silently removes that mechanism.
   */
  private val isAlive: () -> Boolean,
) {
  /** Design §7.2b. */
  sealed interface ApplyResult {
    data class Applied(val session: LanguageServerSession) : ApplyResult

    data object AppliedNoContent : ApplyResult

    data object NotApplied : ApplyResult
  }

  private val logger = logger<WebviewLoadPipeline>()

  /**
   * Design §7.2c, mechanism 1. [dispose] advances it, which expires every application already in
   * flight. Whether a *newer load* has taken over is instead the coordinator's decision, reported
   * as [WebviewLoadCoordinator.Outcome.Superseded]; making this counter answer that question too
   * would stop design §21's A33 from being able to tell the two answers apart.
   */
  private var generation = 0L

  /**
   * Whether [dispose] has run. Separate from [generation] because it answers a different question:
   * [generation] expires the applications that were already in flight, while this one refuses loads
   * that start afterwards, which would otherwise capture the advanced generation and pass its check.
   *
   * It is deliberately checked at [load]'s entry and nowhere else. Checking it at the sink sites
   * would stop the in-flight applications as well, which is [generation]'s job there, and would
   * then leave design §21's A32 mechanism 1 with no mutation that can expose it.
   */
  private var disposed = false

  /** Design §7.2b / §8.1. */
  var displayedSession: LanguageServerSession? = null
    private set

  fun load(id: String, queryParams: Map<String, String>): CompletableFuture<Unit> {
    // §7.2c: a surface that is gone drives no sink and asks the language server for nothing.
    if (disposed) return CompletableFuture.completedFuture(Unit)

    val myGeneration = generation
    val hadStableContent = beginLoad(id, myGeneration)
    return coordinator.load(id, queryParams)
      .handle { outcome, _ -> settle(id, myGeneration, outcome, hadStableContent) }
  }

  /** Design §7.2c, mechanism 1, plus the [disposed] refusal [load] makes. */
  fun dispose() {
    disposed = true
    generation++
  }

  /** Design §7.2a: the captured value, and the loading page only when there is nothing stable. */
  private fun beginLoad(id: String, myGeneration: Long): Boolean {
    if (!isCurrent(myGeneration)) return false

    val stable = try {
      hasStableContent()
    } catch (t: Throwable) {
      // §7.2b: falls to the side that shows the loading page. Recording it cannot fail the load —
      // this runs before `settle`'s finally exists to contain anything, so a platform log that is
      // already gone would otherwise escape `load` and the load would never start at all.
      try {
        logger.info("Webview '$id': stable-content probe failed: type=${t.javaClass.name}")
      } catch (_: Throwable) {
        // There is nowhere left to record this: the log is the thing that failed.
      }
      false
    }

    if (!stable) setLoading(id, myGeneration, true)
    return stable
  }

  private fun settle(
    id: String,
    myGeneration: Long,
    outcome: WebviewLoadCoordinator.Outcome?,
    hadStableContent: Boolean,
  ) {
    // §7.2a: Superseded produces no side effect at all, the loading sink included — a newer load
    // owns the surface and is the one that will settle it.
    if (outcome is WebviewLoadCoordinator.Outcome.Superseded) return

    try {
      val result = apply(id, myGeneration, outcome, hadStableContent)
      // §7.2b
      if (result is ApplyResult.Applied) displayedSession = result.session
    } finally {
      // §7.2b
      setLoading(id, myGeneration, false)
    }
  }

  private fun apply(
    id: String,
    myGeneration: Long,
    outcome: WebviewLoadCoordinator.Outcome?,
    hadStableContent: Boolean,
  ): ApplyResult = when (outcome) {
    is WebviewLoadCoordinator.Outcome.Show -> applyShow(id, myGeneration, outcome)
    is WebviewLoadCoordinator.Outcome.Message -> applyMessage(id, myGeneration, outcome.text)
    // §7.2a
    WebviewLoadCoordinator.Outcome.SessionChanged ->
      if (hadStableContent) ApplyResult.NotApplied else applyMessage(id, myGeneration, MESSAGE_SESSION_CHANGED)
    // Superseded is settled above and never arrives here. A null outcome means the coordinator's
    // future completed exceptionally: there is nothing to apply, and settle's finally still takes
    // the loading page down.
    else -> ApplyResult.NotApplied
  }

  private fun applyShow(id: String, myGeneration: Long, show: WebviewLoadCoordinator.Outcome.Show): ApplyResult {
    if (!isCurrent(myGeneration)) return ApplyResult.NotApplied

    try {
      showUrl(show.url)
    } catch (t: Throwable) {
      // §7.2b / §17: the url that could not be shown is not part of the entry.
      logger.error("Webview '$id': the browser could not show the resolved url: type=${t.javaClass.name}")
      return ApplyResult.NotApplied
    }

    applyTitle(id, myGeneration, show.title)
    return ApplyResult.Applied(show.session)
  }

  /** Design §7.2b: best-effort, and never a reason to undo a url that is already on screen. */
  private fun applyTitle(id: String, myGeneration: Long, title: String?) {
    if (title == null || !isCurrent(myGeneration)) return

    try {
      setTitle(title)
    } catch (t: Throwable) {
      logger.warn("Webview '$id': the tab name could not be set: type=${t.javaClass.name}")
    }
  }

  private fun applyMessage(id: String, myGeneration: Long, text: String): ApplyResult {
    if (!isCurrent(myGeneration)) return ApplyResult.NotApplied

    return try {
      showMessage(text)
      ApplyResult.AppliedNoContent
    } catch (t: Throwable) {
      // §7.2b / §12
      logger.error("Webview '$id': the message page could not be shown: type=${t.javaClass.name}")
      ApplyResult.NotApplied
    }
  }

  private fun setLoading(id: String, myGeneration: Long, visible: Boolean) {
    if (!isCurrent(myGeneration)) return

    try {
      setLoadingVisible(visible)
    } catch (t: Throwable) {
      // §7.2b: contracted not to throw, and swallowed rather than trusted.
      logger.warn("Webview '$id': the loading page could not be toggled: type=${t.javaClass.name}")
    }
  }

  /** Design §7.2a / §7.2c: re-checked immediately before every sink call. */
  private fun isCurrent(myGeneration: Long): Boolean = myGeneration == generation && isAlive()

  companion object {
    /** Design §12's message-page row for a session change with nothing stable on screen. */
    internal const val MESSAGE_SESSION_CHANGED =
      "The GitLab Language Server was switched. Run the command again."
  }
}
