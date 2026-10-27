package com.gitlab.eclipse.chat.quickchat.fix

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class FixWithDuoFlowTest : DescribeSpec({

  describe("fixWithDuo") {
    it("notifies once and does nothing else when unavailable") {
      val notified = mutableListOf<String>()
      var prepared = false
      var sent = false
      fixWithDuo<String>(
        unavailableReason = { "Duo Chat is off" },
        notify = { notified += it },
        prepare = {
          prepared = true
          null
        },
        send = { sent = true },
      )
      notified shouldBe listOf("Duo Chat is off")
      prepared shouldBe false
      sent shouldBe false
    }

    it("sends nothing when prepare yields no request") {
      var sent = false
      val notified = mutableListOf<String>()
      fixWithDuo<String>({ null }, { notified += it }, { null }, { sent = true })
      sent shouldBe false
      notified shouldBe emptyList()
    }

    it("hands the prepared request to send exactly once") {
      val request = FixRequest("editor", 3, "/fix")
      val sent = mutableListOf<FixRequest<String>>()
      fixWithDuo({ null }, { }, { request }, { sent += it })
      sent shouldBe listOf(request)
    }
  }
})
