package com.gitlab.eclipse.mergerequests.discussions

/**
 * Terminal outcome of one [DiscussionsLoader.loadDiscussions] invocation, delivered to the
 * caller's `onOutcome` callback on the UI thread (see [DiscussionsLoader]'s completion
 * contract). A later PR waits on this callback to decide whether to show the user a dialog
 * holding text they typed, so every started load must settle in exactly one of these — the
 * only exception is lifecycle termination, where the callback is deliberately abandoned.
 */
sealed interface LoadOutcome {
  /** The fetch succeeded and its result was written into the sidebar tree. */
  object Applied : LoadOutcome

  /**
   * A newer load for the same [DiscussionKey] was started before this one finished; this
   * result was discarded without touching the node — the newer load will settle the tree.
   */
  object Superseded : LoadOutcome

  /** The fetch threw [cause]; the node now shows its failure children in state `FAILED`. */
  data class Failed(val cause: Throwable) : LoadOutcome

  /**
   * The connection gate rejected the load (instance URL or credential changed, or the
   * settings were mid-change): no HTTP was issued and the node shows its failure children.
   */
  object GateRejected : LoadOutcome

  /**
   * No fetch was started. Two producers: the node was already `LOADED` and `force` was false, or
   * the post-write reload found nothing to reload on a live session (the sidebar view is gone, or a
   * refresh mid-write left the merge request with no expanded discussions section). Both mean the
   * caller has NOT been shown the server's current state, which is why this is not `Applied`.
   */
  object Skipped : LoadOutcome
}
