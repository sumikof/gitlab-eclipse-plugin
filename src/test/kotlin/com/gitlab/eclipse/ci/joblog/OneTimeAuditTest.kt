package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.eclipse.core.runtime.ILog

/** Review M-1: the `foldingUnavailable` line of [JobLogDecorations] is logged once, without a throwable. */
class OneTimeAuditTest : DescribeSpec({
  it("logs the first message only, as a plain line") {
    val log = mockk<ILog>(relaxUnitFun = true)
    val lines = mutableListOf<String>()
    every { log.error(capture(lines)) } just Runs
    var built = 0
    val audit = OneTimeAudit { log }
    val message = {
      built++
      jobLogAuditMessage("displayJobLog", 1L, 9L, "foldingUnavailable")
    }

    repeat(3) { audit.error(message) }

    lines shouldContainExactly listOf("ciReadAction action=displayJobLog projectId=1 jobId=9 outcome=foldingUnavailable")
    built shouldBe 1
    verify(exactly = 0) { log.error(any(), any<Throwable>()) }
  }

  it("does not touch the log until it has something to say") {
    var looked = 0
    OneTimeAudit {
      looked++
      mockk<ILog>()
    }

    looked shouldBe 0
  }
})
