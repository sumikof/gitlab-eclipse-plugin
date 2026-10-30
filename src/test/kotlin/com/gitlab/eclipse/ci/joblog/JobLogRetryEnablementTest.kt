package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** [JobLogRetryEnablement]: tells the log view's Retry command that a session's Retry state changed (L4). */
class JobLogRetryEnablementTest : StringSpec({
  extensions(LoggingKotestExtension)

  val added = mutableListOf<() -> Unit>()

  fun listen(listener: () -> Unit) {
    added += listener
    JobLogRetryEnablement.addListener(listener)
  }

  afterTest {
    added.forEach(JobLogRetryEnablement::removeListener)
    added.clear()
  }

  "every listener hears a change, and a removed one no longer does" {
    var first = 0
    var second = 0
    val firstListener: () -> Unit = { first++ }
    listen(firstListener)
    listen { second += 1 }

    JobLogRetryEnablement.changed()
    JobLogRetryEnablement.removeListener(firstListener)
    JobLogRetryEnablement.changed()

    first shouldBe 1
    second shouldBe 2
  }

  "a failing listener does not stop the others and is logged by exception class only" {
    val log = CapturedLog().apply { install() }
    var heard = 0
    listen { throw IllegalStateException("secret-detail") }
    listen { heard += 1 }

    JobLogRetryEnablement.changed()

    heard shouldBe 1
    log.contains("IllegalStateException") shouldBe true
    log.contains("secret-detail") shouldBe false
  }
})
