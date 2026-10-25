package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.sidebar.autorefresh.immediateScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.eclipse.core.runtime.ILog

private const val WRITE_URL = "https://gitlab.example.com"
private val WRITE_CONNECTION = ConnectionSnapshot(WRITE_URL, "secret-token", "fp-1", 1L)
private val WRITE_KEY = WriteKey(WRITE_URL, "job", 7L)

private class DoneCase(val name: String, val pinned: Boolean, val call: (ConnectionSnapshot) -> PostResult)

private val DONE_CASES = listOf(
  DoneCase("success", pinned = true) { PostResult(201, null) },
  DoneCase("HTTP failure", pinned = true) { throw GitLabApiException(500, "", null) },
  DoneCase("connection changed (no pin)", pinned = false) { error("never called") },
  DoneCase("unexpected exception", pinned = true) { throw IllegalStateException("boom") },
  DoneCase("cancelled", pinned = true) { throw CancellationException("cancelled") },
)

/**
 * [launchCiWrite] (Task 12, L-B2-4): `onDone` once after the guard is released on every path, and
 * the replaceable audit, with the sidebar's defaults unchanged. Runs inline (Unconfined).
 */
class PipelineActionHandlerTest : DescribeSpec({
  beforeSpec {
    mockkObject(NotificationUtils)
    every { NotificationUtils.show(any(), any(), any(), any()) } just Runs
  }
  afterSpec { unmockkObject(NotificationUtils) }

  fun launch(
    log: ILog,
    pinned: Boolean,
    onDone: () -> Unit = {},
    auditMessage: ((WriteOutcome, String?) -> String)? = null,
    attachThrowable: Boolean = true,
    call: (ConnectionSnapshot) -> PostResult,
  ) {
    val api = mockk<GitLabApiClient>()
    every { api.captureConnectionIf(any()) } returns if (pinned) WRITE_CONNECTION else null
    InFlightWriteGuard.tryAcquire(WRITE_KEY) shouldBe true
    if (auditMessage == null) {
      launchCiWrite(immediateScope(), log, WRITE_KEY, "cancel", 42L, api, WRITE_URL, "fp-1", {}, onDone, call = call)
    } else {
      launchCiWrite(
        immediateScope(),
        log,
        WRITE_KEY,
        "cancel",
        42L,
        api,
        WRITE_URL,
        "fp-1",
        {},
        onDone,
        auditMessage,
        attachThrowable,
        call,
      )
    }
  }

  describe("onDone") {
    withData(nameFn = { it.name }, DONE_CASES) { c ->
      val events = mutableListOf<String>()

      launch(mockk(relaxed = true), c.pinned, onDone = {
        // The guard is already free when onDone runs.
        events += "done free=${InFlightWriteGuard.tryAcquire(WRITE_KEY)}"
        InFlightWriteGuard.release(WRITE_KEY)
      }, call = c.call)

      events shouldContainExactly listOf("done free=true")
      InFlightWriteGuard.tryAcquire(WRITE_KEY) shouldBe true
      InFlightWriteGuard.release(WRITE_KEY)
    }

    it("a throwing onDone is audited and does not cancel the shared scope") {
      val log = mockk<ILog>(relaxed = true)
      val scope = immediateScope()
      val api = mockk<GitLabApiClient>()
      every { api.captureConnectionIf(any()) } returns WRITE_CONNECTION
      InFlightWriteGuard.tryAcquire(WRITE_KEY) shouldBe true

      launchCiWrite(scope, log, WRITE_KEY, "cancel", 42L, api, WRITE_URL, "fp-1", {}, { error("onDone") }) {
        PostResult(201, null)
      }

      (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive) shouldBe true
      verify { log.error("ciWriteAction onDone failed exceptionClass=IllegalStateException") }
      InFlightWriteGuard.tryAcquire(WRITE_KEY) shouldBe true
      InFlightWriteGuard.release(WRITE_KEY)
    }
  }

  describe("audit") {
    it("by default keeps the sidebar's line and attaches an unexpected throwable") {
      val log = mockk<ILog>(relaxed = true)

      launch(log, pinned = true) { throw IllegalStateException("boom") }

      verify(exactly = 1) {
        log.error(
          "ciWriteAction action=cancel instanceUrl=$WRITE_URL projectId=42 targetKind=job targetId=7 " +
            "outcome=failure failureKind=unexpected",
          any<IllegalStateException>(),
        )
      }
    }

    it("takes a replacement line, with the exception class, and logs it without the throwable") {
      val log = mockk<ILog>(relaxed = true)
      val lines = mutableListOf<String>()
      every { log.error(capture(lines)) } just Runs

      launch(
        log,
        pinned = true,
        auditMessage = { outcome, exceptionClass ->
          "custom ${(outcome as WriteOutcome.Failure).failureKind} $exceptionClass"
        },
        attachThrowable = false,
      ) { throw IllegalStateException("boom") }

      lines shouldContainExactly listOf("custom unexpected IllegalStateException")
      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
    }

    it("uses the replacement for success and classified failures too, with no exception class") {
      val log = mockk<ILog>(relaxed = true)
      val infos = mutableListOf<String>()
      val errors = mutableListOf<String>()
      every { log.info(capture(infos)) } just Runs
      every { log.error(capture(errors)) } just Runs
      val audit = { outcome: WriteOutcome, exceptionClass: String? -> "${outcome::class.simpleName} $exceptionClass" }

      launch(log, pinned = true, auditMessage = audit, attachThrowable = false) { PostResult(201, null) }
      launch(log, pinned = true, auditMessage = audit, attachThrowable = false) {
        throw GitLabApiException(500, "", null)
      }

      infos shouldContainExactly listOf("Success null")
      errors shouldContainExactly listOf("Failure null")
    }
  }
})
