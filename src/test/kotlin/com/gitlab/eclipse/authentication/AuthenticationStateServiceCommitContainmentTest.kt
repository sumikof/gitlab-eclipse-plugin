package com.gitlab.eclipse.authentication

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.lsp.FeatureStateChange
import com.gitlab.eclipse.lsp.FeatureStateChangeCheck
import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.core.runtime.ILog
import org.eclipse.core.runtime.IStatus
import org.eclipse.core.runtime.Platform
import org.eclipse.swt.SWT
import org.eclipse.swt.SWTException
import org.osgi.framework.Bundle

private const val WORKBENCH_NOT_UP = "Workbench has not been created yet."
private const val WIDGET_DISPOSED = "Widget is disposed: /some/user/path"

private fun captureLog(): List<String> {
  val recorded = mutableListOf<String>()
  val log = mockk<ILog>()
  val message = slot<String>()
  val status = slot<IStatus>()
  every { log.error(capture(message)) } answers { recorded += message.captured }
  every { log.error(capture(message), any()) } answers { recorded += message.captured }
  every { log.warn(capture(message)) } answers { recorded += message.captured }
  every { log.warn(capture(message), any()) } answers { recorded += message.captured }
  every { log.info(capture(message)) } answers { recorded += message.captured }
  every { log.info(capture(message), any()) } answers { recorded += message.captured }
  every { log.log(capture(status)) } answers { recorded += status.captured.message }
  every { Platform.getLog(any<Bundle>()) } returns log
  return recorded
}

/**
 * The debounce coroutine runs on the plugin's shared scope, whose root is a plain `Job`
 * (`workspaceModule`): an exception escaping the launch body would cancel every other coroutine in
 * the plugin. `commit` can throw in two places that only exist on a real machine — the lazy
 * provider resolution goes through the workbench, and the UI dispatch throws once the display is
 * gone — so both are driven through the seams here, with the same plain-`Job` scope shape as
 * production and an immediate dispatcher so the launch body runs inline.
 */
class AuthenticationStateServiceCommitContainmentTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val unauthenticated = FeatureStateChange(
    featureId = "authentication",
    allChecks = listOf(FeatureStateChangeCheck(checkId = "authentication-required", engaged = true)),
  )

  val session = LanguageServerSession()

  class Harness(
    session: LanguageServerSession,
    sourceProvider: () -> AuthenticationSourceProvider,
    uiDispatch: (Runnable) -> Unit = { it.run() },
    val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
  ) {
    val service = AuthenticationStateService(
      scope = scope,
      notifDelay = 1000L,
      sourceProvider = sourceProvider,
      currentSession = { session },
      uiDispatch = uiDispatch,
      debounce = {},
      showPopup = {},
    )

    /** True when a coroutine launched on the shared scope after the failure actually ran. */
    fun laterCoroutineRuns(): Boolean {
      var ran = false
      scope.launch { ran = true }
      return ran
    }
  }

  describe("a throwing commit is contained inside the debounce coroutine") {
    it("lazy provider resolution throwing (workbench not up) does not cancel the shared scope") {
      val logged = captureLog()
      val h = Harness(session, sourceProvider = { throw IllegalStateException(WORKBENCH_NOT_UP) })

      shouldNotThrowAny { h.service.update(unauthenticated, session) }

      h.scope.isActive shouldBe true
      h.laterCoroutineRuns() shouldBe true
      logged shouldContainExactly listOf(
        "Authentication state commit skipped: ${IllegalStateException::class.java.name}",
      )
      logged.forEach { it shouldNotContain WORKBENCH_NOT_UP }
    }

    it("the UI dispatch throwing (display gone) does not cancel the shared scope") {
      val logged = captureLog()
      val h = Harness(
        session,
        sourceProvider = { AuthenticationSourceProvider({ session }, { it.run() }) },
        uiDispatch = { throw SWTException(SWT.ERROR_WIDGET_DISPOSED, WIDGET_DISPOSED) },
      )

      shouldNotThrowAny { h.service.update(unauthenticated, session) }

      h.scope.isActive shouldBe true
      h.laterCoroutineRuns() shouldBe true
      logged shouldContainExactly listOf(
        "Authentication state commit skipped: ${SWTException::class.java.name}",
      )
      logged.forEach {
        it shouldNotContain WIDGET_DISPOSED
        it shouldNotContain "/some/user/path"
      }
    }

    it("a later notification on the same service still commits after a contained failure") {
      captureLog()
      var fail = true
      val provider = AuthenticationSourceProvider({ session }, { it.run() })
      val h = Harness(session, sourceProvider = {
        if (fail) error(WORKBENCH_NOT_UP) else provider
      })

      h.service.update(unauthenticated, session)
      fail = false
      h.service.update(unauthenticated, session)

      provider.authState shouldBe AuthState.SIGN_IN_REQUIRED
      provider.signInRequired shouldBe true
    }
  }
})
