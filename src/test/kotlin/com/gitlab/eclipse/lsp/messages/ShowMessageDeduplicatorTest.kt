package com.gitlab.eclipse.lsp.messages

import com.gitlab.eclipse.lsp.LanguageServerSession
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.eclipse.lsp4j.MessageType

class ShowMessageDeduplicatorTest : DescribeSpec({
  var now = 0L
  val session = LanguageServerSession()

  beforeEach { now = 1_000L }

  describe("shouldShow") {
    it("shows the first message") {
      val dedupe = ShowMessageDeduplicator(nowMillis = { now })

      dedupe.shouldShow(session, MessageType.Error, "boom") shouldBe true
    }

    it("suppresses the same session, type and text within the window") {
      val dedupe = ShowMessageDeduplicator(nowMillis = { now })
      dedupe.shouldShow(session, MessageType.Error, "boom")

      now += 29_999
      dedupe.shouldShow(session, MessageType.Error, "boom") shouldBe false
    }

    it("shows it again once the window has passed") {
      val dedupe = ShowMessageDeduplicator(nowMillis = { now })
      dedupe.shouldShow(session, MessageType.Error, "boom")

      now += 30_000
      dedupe.shouldShow(session, MessageType.Error, "boom") shouldBe true
    }

    it("measures the window from the last time it was shown, not from a suppressed repeat") {
      val dedupe = ShowMessageDeduplicator(nowMillis = { now })
      dedupe.shouldShow(session, MessageType.Error, "boom")
      now += 20_000
      dedupe.shouldShow(session, MessageType.Error, "boom") shouldBe false

      now += 10_000
      dedupe.shouldShow(session, MessageType.Error, "boom") shouldBe true
    }

    it("honours a custom window") {
      val dedupe = ShowMessageDeduplicator(windowMillis = 100, nowMillis = { now })
      dedupe.shouldShow(session, MessageType.Info, "x")

      now += 100
      dedupe.shouldShow(session, MessageType.Info, "x") shouldBe true
    }

    it("treats a different type, text or session as a different message") {
      val dedupe = ShowMessageDeduplicator(nowMillis = { now })
      dedupe.shouldShow(session, MessageType.Error, "boom")

      dedupe.shouldShow(session, MessageType.Warning, "boom") shouldBe true
      dedupe.shouldShow(session, MessageType.Error, "other") shouldBe true
      dedupe.shouldShow(LanguageServerSession(), MessageType.Error, "boom") shouldBe true
    }
  }
})
