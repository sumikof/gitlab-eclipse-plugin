package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.PipelineActionService
import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.utils.NotificationUtils
import com.gitlab.eclipse.views.sidebar.autorefresh.immediateScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.eclipse.core.runtime.ILog

private const val URL = "https://gitlab.example.com"
private val CONNECTION = ConnectionSnapshot(URL, "secret-token", "fp-1", 1L)

/**
 * Status bar R13: the pinned pipeline write behind the sidebar's Retry / Cancel commands, extracted so the trim's
 * pulldown can run it without a sidebar selection. Runs inline (Unconfined) so each case is synchronous.
 */
class PinnedPipelineWriteTest : StringSpec({
  fun rig(): Triple<GitLabApiClient, PipelineActionService, ILog> =
    Triple(mockk(), mockk(), mockk(relaxed = true))

  // The failure paths notify; no Display exists headless. A fresh scope per case: one failing case must not cancel
  // the scope (and so strand the guard) for the next.
  beforeSpec {
    mockkObject(NotificationUtils)
    every { NotificationUtils.show(any(), any(), any(), any()) } just Runs
  }
  afterSpec { unmockkObject(NotificationUtils) }

  fun freshScope() = immediateScope()

  "retry pinned to the node's connection: one POST on the pinned snapshot, then onSuccess, then the target is free" {
    val (api, service, log) = rig()
    every { api.captureConnectionIf(any()) } returns CONNECTION
    every { service.retry(CONNECTION, 42, 7) } returns PostResult(201, null)
    var successes = 0

    val started = launchPinnedPipelineWrite(
      PipelineWrite(PipelineWriteAction.RETRY, 42, 7, "$URL/", "fp-1"),
      onSuccess = { successes++ },
      scope = freshScope(),
      log = log,
      apiClient = api,
      actionService = service,
    )

    started shouldBe true
    successes shouldBe 1
    verify(exactly = 1) { service.retry(CONNECTION, 42, 7) }
    InFlightWriteGuard.tryAcquire(WriteKey(URL, "pipeline", 7)) shouldBe true
    InFlightWriteGuard.release(WriteKey(URL, "pipeline", 7))
  }

  "a changed account (fingerprint) issues no write and no onSuccess" {
    val (api, service, log) = rig()
    every { api.captureConnectionIf(any()) } returns CONNECTION.copy(authFingerprint = "fp-other")
    var successes = 0

    val write = PipelineWrite(PipelineWriteAction.CANCEL, 42, 7, URL, "fp-1")
    launchPinnedPipelineWrite(write, { successes++ }, freshScope(), log, api, service)

    successes shouldBe 0
    verify(exactly = 0) { service.cancel(any(), any(), any()) }
  }

  "a write already in flight for the same pipeline starts nothing" {
    val (api, service, log) = rig()
    val key = WriteKey(URL, "pipeline", 7)
    InFlightWriteGuard.tryAcquire(key) shouldBe true
    try {
      launchPinnedPipelineWrite(
        PipelineWrite(PipelineWriteAction.RETRY, 42, 7, URL, "fp-1"),
        {},
        freshScope(),
        log,
        api,
        service,
      ) shouldBe false
      verify(exactly = 0) { api.captureConnectionIf(any()) }
    } finally {
      InFlightWriteGuard.release(key)
    }
  }

  "a failed POST runs no onSuccess and frees the target" {
    val (api, service, log) = rig()
    every { api.captureConnectionIf(any()) } returns CONNECTION
    every { service.cancel(CONNECTION, 42, 7) } throws java.io.IOException("reset")
    var successes = 0

    val write = PipelineWrite(PipelineWriteAction.CANCEL, 42, 7, URL, "fp-1")
    launchPinnedPipelineWrite(write, { successes++ }, freshScope(), log, api, service)

    successes shouldBe 0
    InFlightWriteGuard.tryAcquire(WriteKey(URL, "pipeline", 7)) shouldBe true
    InFlightWriteGuard.release(WriteKey(URL, "pipeline", 7))
  }
})
