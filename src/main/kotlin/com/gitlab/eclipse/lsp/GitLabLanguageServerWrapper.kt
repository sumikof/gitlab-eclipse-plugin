package com.gitlab.eclipse.lsp

import java.util.concurrent.atomic.AtomicReference

class GitLabLanguageServerWrapper {
  companion object {
    /**
     * The one piece of state this wrapper holds: the handle of the connection that is current, or
     * null when there is none.
     *
     * It is an [AtomicReference] rather than a plain (or even volatile) field for two reasons.
     * Registration and revocation happen on the language server lifecycle and callback threads
     * while readers run on the UI thread, on lsp4j's dispatch threads and on background coroutines,
     * so the value has to be safely published. And the identity-aware revocation below is a
     * conditional update, which needs the compare and the write to be one step.
     */
    private val snapshot = AtomicReference<LanguageServerHandle?>(null)
  }

  /** The current connection's proxy paired with its identity, or null when no server is current. */
  val currentSnapshot: LanguageServerHandle?
    get() = snapshot.get()

  val languageServer: GitLabLanguageServer?
    get() = snapshot.get()?.proxy

  /** Publishes the proxy and the session identity of a connection as one value. */
  fun registerLanguageServer(handle: LanguageServerHandle) {
    snapshot.set(handle)
  }

  /** Clears the current connection whatever it is. Used by the explicit stop path. */
  fun unregisterLanguageServer() {
    snapshot.set(null)
  }

  /**
   * Clears the current connection only while it is still [captured].
   *
   * [captured] must be the very handle the caller registered, not one rebuilt from the same proxy
   * and session: the comparison is by reference. Returns whether the connection was cleared. False
   * means [captured] was not the current connection and nothing was touched; **why** it was not is
   * not recoverable here, and the causes are not a closed set. A newer connection may have taken
   * over, an explicit stop may have cleared the snapshot, or this same handle may already have been
   * revoked — `GitLabLanguageServerProcessProvider` revokes whatever `handleRef` holds from both
   * the initialize callback and the exit callback, so an initialize rejection followed by the
   * process dying calls this twice with one handle, and the second call's `false` is caused by the
   * first call rather than by either of the other two.
   */
  fun unregisterLanguageServer(captured: LanguageServerHandle): Boolean =
    snapshot.compareAndSet(captured, null)
}
