package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.PostResult
import com.gitlab.eclipse.ci.actions.JobWriteTarget
import com.gitlab.eclipse.ci.actions.WriteKey
import com.gitlab.eclipse.ci.actions.WriteOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val PAGE_URL = "https://gitlab.example.com/"
private val TARGET = JobWriteTarget(7L, 42L, PAGE_URL, "fp")
private val KEY = WriteKey("https://gitlab.example.com", "job", 7L)

/** The waiting page's button (Task 12, L-B2-4): [pressPageButton] with the write and the UI hop injected. */
class JobLogPageActionTest : DescribeSpec({
  val events = mutableListOf<String>()
  val ui = ArrayDeque<() -> Unit>()
  val launched = mutableListOf<Pair<WriteKey, () -> Unit>>()

  beforeEach {
    events.clear()
    ui.clear()
    launched.clear()
  }

  fun press(free: Boolean = true, launchFails: Boolean = false): Boolean =
    pressPageButton(
      TARGET,
      launch = { key, onDone ->
        events += "launch"
        if (launchFails) error("no scope")
        launched += key to onDone
      },
      tryAcquire = { key ->
        events += "acquire:${key.targetKind}:${key.targetId}"
        free
      },
      release = { key -> events += "release:${key.targetKind}:${key.targetId}" },
      setBusy = { events += "busy=$it" },
      onFinished = { events += "finished" },
      runOnUi = { ui += it },
    )

  describe("pressPageButton") {
    it("with the guard free: busy, one write, and when it is done, enabled again and finished on the UI") {
      press() shouldBe true

      events shouldContainExactly listOf("acquire:job:7", "busy=true", "launch")

      launched.single().second.invoke()
      events.last() shouldBe "launch"

      ui.removeFirst().invoke()
      events shouldContainExactly listOf("acquire:job:7", "busy=true", "launch", "busy=false", "finished")
      ui.shouldBeEmpty()
    }

    it("with a write to the same job in flight: nothing happens and it returns false") {
      press(free = false) shouldBe false

      events shouldContainExactly listOf("acquire:job:7")
      launched.shouldBeEmpty()
    }

    it("a launch that throws frees the job and brings the button back, then rethrows") {
      shouldThrow<IllegalStateException> { press(launchFails = true) }

      events shouldContainExactly listOf("acquire:job:7", "busy=true", "launch", "release:job:7")
      ui.removeFirst().invoke()
      events shouldContainExactly
        listOf("acquire:job:7", "busy=true", "launch", "release:job:7", "busy=false", "finished")
    }

    it("keys the write like the sidebar's job actions: normalized instance, job, job id") {
      press()

      launched.single().first shouldBe KEY
    }
  }

  describe("jobLogWriteAuditMessage") {
    it("has no instance URL and only the class of an unexpected throwable") {
      val failure = WriteOutcome.Failure(httpStatus = null, correlationId = null, failureKind = "unexpected")

      val line = jobLogWriteAuditMessage("cancel", 42L, 7L, failure, "IllegalStateException")

      line shouldBe
        "ciWriteAction action=cancel projectId=42 jobId=7 outcome=unexpected exceptionClass=IllegalStateException"
      line shouldNotContain "gitlab.example.com"
    }

    it("carries the status and correlation id of a success and of an HTTP failure") {
      jobLogWriteAuditMessage("play", 42L, 7L, WriteOutcome.Success(PostResult(201, "c-1")), null) shouldBe
        "ciWriteAction action=play projectId=42 jobId=7 outcome=success httpStatus=201 correlationId=c-1"
      jobLogWriteAuditMessage("play", 42L, 7L, WriteOutcome.Failure(403, "c-2", "http"), null) shouldBe
        "ciWriteAction action=play projectId=42 jobId=7 outcome=http httpStatus=403 correlationId=c-2"
    }
  }
})
