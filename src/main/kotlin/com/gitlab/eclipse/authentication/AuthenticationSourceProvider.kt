package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.utils.currentDisplay
import org.eclipse.ui.AbstractSourceProvider
import org.eclipse.ui.ISources
import java.util.concurrent.atomic.AtomicReference

/**
 * Source provider for the `gitlab_sign_in_required` variable behind the "Sign in to GitLab" status
 * menu item (design §8.1 / §12.1).
 *
 * The internal state is three-valued (unknown / authenticated / sign in required) so that "nothing
 * has arrived yet" is never mistaken for "authenticated". The published value is a single
 * **`Boolean`**: `true` only when the stored state was reported by the connection that is current
 * *at publication time* and says sign in is required; unknown, authenticated and a stale connection
 * all publish `false`. It is a `Boolean` and not a `String` because the menu's
 * `<equals value="true"/>` converts its literal to `Boolean.TRUE` before comparing.
 *
 * Writes are compare-and-set. An `update` is dropped when its session is no longer the current
 * connection at the moment of writing, or when the same connection already holds a value of an
 * equal or newer generation (a tombstone written by [reset] counts). The provider never creates or
 * re-reads a generation: `AuthenticationStateService` settles it under its own lock and passes it in.
 *
 * Instantiated by the workbench from `plugin.xml`, hence the no-argument constructor that the
 * default arguments provide. The two constructor parameters are seams for headless tests only.
 *
 * @param currentSession reads the session of the current connection, or null when there is none.
 * @param uiDispatch hands a runnable to the UI thread; `fireSourceChanged` must run there.
 */
class AuthenticationSourceProvider(
  private val currentSession: () -> LanguageServerSession? = {
    service<GitLabLanguageServerWrapper>().currentSnapshot?.session
  },
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
) : AbstractSourceProvider() {
  companion object {
    const val SIGN_IN_REQUIRED_KEY = "gitlab_sign_in_required"

    /** Check ids of the language server's `authentication` feature that mean "sign in required". */
    private val SIGN_IN_REQUIRED_CHECKS = setOf("authentication-required", "invalid-token")
  }

  private val storedRef = AtomicReference<SessionAuthState?>(null)

  /** The stored triple, for tests of the write rules. Null means no value has ever been stored. */
  internal val stored: SessionAuthState?
    get() = storedRef.get()

  /** The raw three-valued state, without the connection check that the published value applies. */
  val authState: AuthState
    get() = stored?.state ?: AuthState.UNKNOWN

  /** The published value: sign in is required *for the connection that is current right now*. */
  val signInRequired: Boolean
    get() {
      val current = stored ?: return false
      return current.session === currentSession() && current.state == AuthState.SIGN_IN_REQUIRED
    }

  /**
   * Stores the state derived from the debounced [featureStateChange] as reported by [session], unless
   * the write is stale. A change without `allChecks` says nothing and leaves the state untouched.
   */
  fun update(featureStateChange: FeatureStateChange, session: LanguageServerSession, generation: Long) {
    val checks = featureStateChange.allChecks ?: return
    val state = if (checks.any { it.engaged && it.checkId in SIGN_IN_REQUIRED_CHECKS }) {
      AuthState.SIGN_IN_REQUIRED
    } else {
      AuthState.AUTHENTICATED
    }
    val next = SessionAuthState(session, generation, state)

    while (true) {
      // Read first, check second: a reset or a newer write that lands between the check and the
      // compare-and-set makes the compare-and-set fail, and the re-check then sees it.
      val current = storedRef.get()
      if (session !== currentSession()) return
      if (current != null && current.session === session && current.generation >= generation) return
      if (storedRef.compareAndSet(current, next)) break
    }
    fire()
  }

  /**
   * Writes the tombstone `(session, generation, UNKNOWN)`, or clears the value when [session] is
   * null (no connection at reset time). The tombstone keeps [generation] as the floor that a stale
   * `update` of the same connection is rejected against.
   */
  fun reset(session: LanguageServerSession?, generation: Long) {
    storedRef.set(session?.let { SessionAuthState(it, generation, AuthState.UNKNOWN) })
    fire()
  }

  override fun getCurrentState(): Map<Any?, Any?> = mapOf(SIGN_IN_REQUIRED_KEY to signInRequired)

  override fun getProvidedSourceNames(): Array<String> = arrayOf(SIGN_IN_REQUIRED_KEY)

  override fun dispose() = Unit

  /** The value is computed on the UI thread, at firing time, with the connection check applied. */
  private fun fire() {
    uiDispatch { fireSourceChanged(ISources.WORKBENCH, SIGN_IN_REQUIRED_KEY, signInRequired) }
  }
}
