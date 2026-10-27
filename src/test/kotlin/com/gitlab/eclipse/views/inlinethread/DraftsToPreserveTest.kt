package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull

private fun item(id: String) =
  InlineThreadItem(
    threadId = id,
    title = "Thread $id",
    entries = emptyList(),
    resolved = false,
    moreEntriesOnServer = false,
    actions = setOf(InlineThreadAction.REPLY),
    inputPlaceholder = "Reply…",
  )

private val MODEL = InlineThreadModel(listOf(item("d1"), item("d2")))

/** A state with "sending" in flight on d1 and "typed later" on d2. */
private fun sending(): Pair<InlineThreadState, SubmitTicket> {
  val s = InlineThreadState(MODEL)
  s.onEdit("d1", "sending")
  s.onEdit("d2", "typed later")
  return s to s.beginSubmit().shouldNotBeNull()
}

class DraftsToPreserveTest : DescribeSpec({
  describe("default mode (a host whose terminal keeps the in-flight body, the MR layer)") {
    it("leaves the unedited in-flight body out") {
      val (s, ticket) = sending()
      s.draftsToPreserve(ticket) shouldContainExactly listOf("typed later")
      s.draftsToPreserve(ticket, keepInFlight = false) shouldContainExactly listOf("typed later")
    }
  }

  describe("keepInFlight (Quick Chat: nothing else keeps the question)") {
    it("offers the unedited in-flight body once") {
      val (s, ticket) = sending()
      s.draftsToPreserve(ticket, keepInFlight = true) shouldContainExactly listOf("sending", "typed later")
    }

    it("offers the edited draft instead once the thread was edited since the ticket, without duplicates") {
      val (s, ticket) = sending()
      s.onEdit("d1", "sending and more")
      s.draftsToPreserve(ticket, keepInFlight = true) shouldContainExactly listOf("sending and more", "typed later")
    }

    it("offers the plain drafts when nothing is in flight") {
      val (s, ticket) = sending()
      s.onAttemptFinished(ticket)
      s.draftsToPreserve(null, keepInFlight = true) shouldContainExactly listOf("sending", "typed later")
      s.draftsToPreserve(ticket, keepInFlight = true) shouldContainExactly listOf("sending", "typed later")
    }
  }

  describe("an untouched automatic draft (QCHAT-05 /fix) is not offered") {
    it("is left out while it is in flight, even with keepInFlight") {
      val s = InlineThreadState(MODEL)
      s.onAutoEdit("d1", "/fix")
      s.onEdit("d2", "typed later")
      val ticket = s.beginSubmit().shouldNotBeNull()
      s.draftsToPreserve(ticket, keepInFlight = true) shouldContainExactly listOf("typed later")
    }

    it("is left out after a failed attempt (not busy)") {
      val s = InlineThreadState(MODEL)
      s.onAutoEdit("d1", "/fix")
      val ticket = s.beginSubmit().shouldNotBeNull()
      s.onAttemptFinished(ticket)
      s.draftsToPreserve(null, keepInFlight = true).shouldBeEmpty()
      s.draftsToPreserve(ticket).shouldBeEmpty()
    }

    it("is offered in its edited form once the user changed it") {
      val s = InlineThreadState(MODEL)
      s.onAutoEdit("d1", "/fix")
      s.onEdit("d1", "/fix but explain")
      s.draftsToPreserve(null, keepInFlight = true) shouldContainExactly listOf("/fix but explain")
    }
  }
})
