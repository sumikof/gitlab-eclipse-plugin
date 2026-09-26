package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.GitLabLanguageServerWrapper
import com.gitlab.eclipse.lsp.LanguageServerSession
import com.gitlab.eclipse.preferences.openGitLabPreferences
import com.gitlab.eclipse.utils.currentDisplay
import com.gitlab.eclipse.utils.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.jface.dialogs.Dialog
import org.eclipse.jface.notifications.AbstractNotificationPopup
import org.eclipse.jface.resource.JFaceResources
import org.eclipse.swt.SWT
import org.eclipse.swt.graphics.Color
import org.eclipse.swt.graphics.Image
import org.eclipse.swt.widgets.Composite
import org.eclipse.swt.widgets.Link
import java.util.concurrent.atomic.AtomicLong

/**
 * Debounces the language server's `authentication` feature state, shows the "requires
 * authentication" popup, and feeds the debounced state to [AuthenticationSourceProvider]
 * (design §9.1).
 *
 * Concurrency contract:
 * - One lock covers the entrance (connection check, cancel of the pending job, launch of the new
 *   one) and, after the debounce, the generation check together with the three commits (provider
 *   update, `isAuthenticated` update, popup decision). A caller can therefore never cancel a job that
 *   was launched after its own connection check, and a reset can never slip between the check and a
 *   commit.
 * - [generation] advances for every accepted notification and for every reset. A job commits only
 *   while its captured generation is still the current one; `Job.cancel()` cannot stop a job that
 *   has already passed its delay, so the generation is the authoritative discard.
 * - A notification whose session is not the current connection is dropped at the entrance, before
 *   the pending job is cancelled, so a late notification from a dead connection cannot cancel the
 *   live connection's debounce.
 * - The popup runnable carries `(session, settledVersion)` and re-checks, under the same lock and on
 *   the UI thread, that both are still current and the state is still unauthenticated.
 * - The debounce runs on the plugin's shared [scope], whose root is a plain `Job`: one escaping
 *   exception would cancel every other coroutine in the plugin. [commit] can throw — the lazy
 *   provider resolution goes through the workbench, and the UI dispatch throws at teardown — so the
 *   launch body contains everything but cancellation and logs the class name.
 *
 * Everything real-machine dependent is a constructor seam with a production default, so the races
 * above can be pinned down in headless tests.
 *
 * @param sourceProvider resolves the provider lazily: it is the workbench-created instance and is
 *   only available once the workbench is up.
 * @param currentSession reads the session of the current connection, or null when there is none.
 * @param uiDispatch hands a runnable to the UI thread.
 * @param debounce suspends for the debounce delay.
 * @param showPopup displays the authentication popup; runs on the UI thread.
 */
@Suppress("MagicNumber")
class AuthenticationStateService(
  private val scope: CoroutineScope = service<CoroutineScope>(),
  private val notifDelay: Long = 2000L,
  sourceProvider: () -> AuthenticationSourceProvider = { service<AuthenticationSourceProvider>() },
  private val currentSession: () -> LanguageServerSession? = {
    service<GitLabLanguageServerWrapper>().currentSnapshot?.session
  },
  private val uiDispatch: (Runnable) -> Unit = { currentDisplay.asyncExec(it) },
  private val debounce: suspend (Long) -> Unit = { delay(it) },
  private val showPopup: () -> Unit = ::showAuthenticationRequiredPopup,
) {
  private val provider: AuthenticationSourceProvider by lazy(sourceProvider)
  private val logger by lazy { logger<AuthenticationStateService>() }

  /** Guards [isAuthenticated], [showAuthNotifJob] and every generation check-and-commit. */
  private val lock = Any()
  private val generation = AtomicLong(0)

  /**
   * Advances only when [isAuthenticated] is written (a commit or a reset), unlike [generation], which
   * advances at every accepted notification. The popup runnable compares against this one: a
   * same-value notification accepted while the runnable waits for the UI thread must not lose the
   * popup, because its own commit stops at the unchanged-state early return and never queues another.
   */
  private var settledVersion = 0L

  private var isAuthenticated: Boolean? = null
  private var showAuthNotifJob: Job? = null

  /**
   * Accepts a `$/gitlab/featureStateChange` for the `authentication` feature reported by [session].
   *
   * Dropped at once when [session] is not the current connection. Otherwise the pending debounce is
   * cancelled and a new one is launched, all under [lock].
   */
  fun update(featureStateChange: FeatureStateChange, session: LanguageServerSession) {
    synchronized(lock) {
      if (session !== currentSession()) return

      showAuthNotifJob?.cancel()
      val jobGeneration = generation.incrementAndGet()

      // This is a debouncing mechanism needed as the language server fires multiple featureStateChange
      // calls successively. Without this, erroneous notifications may appear as initial
      // featureStateChange events may contain stale auth states.
      showAuthNotifJob = scope.launch {
        debounce(notifDelay)

        // Second connection check, outside the lock: the connection may have changed while waiting.
        if (session !== currentSession()) return@launch
        runCatching { commit(featureStateChange, session, jobGeneration) }
          .onFailure { e ->
            if (e is CancellationException) throw e
            logger.warn("Authentication state commit skipped: ${e.javaClass.name}")
          }
      }
    }
  }

  /** Called when the authentication settings were saved: nothing is known until the next state. */
  fun resetAuthenticatedState() {
    resetLocked()
  }

  /** Called when the language server stopped or exited: nothing is known about the next one. */
  fun resetForConnectionChange() {
    resetLocked()
  }

  private fun resetLocked() {
    synchronized(lock) {
      val resetGeneration = generation.incrementAndGet()
      isAuthenticated = null
      settledVersion++
      provider.reset(currentSession(), resetGeneration)
    }
  }

  /** The generation check and the three commits, as one step under [lock]. */
  private fun commit(featureStateChange: FeatureStateChange, session: LanguageServerSession, jobGeneration: Long) {
    synchronized(lock) {
      if (jobGeneration != generation.get()) return

      // Before the early returns below: after a restart the provider starts over as unknown while
      // `isAuthenticated` keeps its value, so an unchanged state must still reach the provider.
      provider.update(featureStateChange, session, jobGeneration)

      val authenticationCheck = featureStateChange
        .allChecks
        ?.find { it.checkId == "authentication-required" }
        ?: return

      val newAuthenticationState = !authenticationCheck.engaged
      if (isAuthenticated == newAuthenticationState) return

      isAuthenticated = newAuthenticationState
      settledVersion++
      if (!newAuthenticationState) {
        val settled = settledVersion
        uiDispatch { showIfStillUnauthenticated(session, settled) }
      }
    }
  }

  /** On the UI thread: the popup is shown only if nothing settled in between. */
  private fun showIfStillUnauthenticated(session: LanguageServerSession, settled: Long) {
    val stillCurrent = synchronized(lock) {
      settled == settledVersion && session === currentSession() && isAuthenticated == false
    }
    if (stillCurrent) showPopup()
  }
}

@Suppress("MagicNumber")
private fun showAuthenticationRequiredPopup() {
  object : AbstractNotificationPopup(currentDisplay) {
    override fun getPopupShellTitle(): String = "GitLab Duo requires authentication"

    override fun getPopupShellImage(maximumHeight: Int): Image {
      return JFaceResources.getImage(Dialog.DLG_IMG_MESSAGE_WARNING)
    }

    override fun createContentArea(parent: Composite) {
      Link(parent, SWT.WRAP).apply {
        text = "<a>Authenticate with GitLab</a>"

        // Transparent background
        background = Color(currentDisplay, 255, 255, 255, 0)

        addListener(SWT.Selection) { _ ->
          close()
          openGitLabPreferences()
        }
      }
    }
  }.apply {
    isFadingEnabled = false
    delayClose = 3000L
  }.open()
}
