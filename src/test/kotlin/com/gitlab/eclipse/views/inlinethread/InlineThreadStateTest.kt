package com.gitlab.eclipse.views.inlinethread

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun item(id: String) =
  InlineThreadItem(
    threadId = id,
    title = "Thread $id",
    entries = listOf(InlineThreadEntry("alice", "2026-09-27", "body of $id")),
    resolved = false,
    moreEntriesOnServer = false,
    actions = setOf(InlineThreadAction.REPLY),
    inputPlaceholder = "Reply…",
  )

private fun model(vararg ids: String) = InlineThreadModel(ids.map(::item))

class InlineThreadStateTest : DescribeSpec({
  describe("construction") {
    it("selects the first item") {
      InlineThreadState(model("a", "b")).selectedThreadId shouldBe "a"
    }

    it("starts idle with empty drafts at generation 0") {
      val s = InlineThreadState(model("a"))
      s.busy shouldBe false
      s.draft("a") shouldBe ""
      s.editGeneration("a") shouldBe 0L
    }

    it("rejects an empty model") {
      shouldThrow<IllegalArgumentException> { InlineThreadState(InlineThreadModel(emptyList())) }
    }

    it("rejects duplicate thread ids, since drafts are keyed by id") {
      shouldThrow<IllegalArgumentException> { InlineThreadState(model("a", "a")) }
    }
  }

  describe("canSubmit follows the existing isSubmittable rule") {
    fun submittable(text: String): Boolean {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", text)
      return s.canSubmit()
    }

    it("rejects the empty draft") { submittable("") shouldBe false }
    it("rejects a whitespace-only draft") { submittable(" \n\t ") shouldBe false }
    it("rejects an ideographic-space-only draft (U+3000)") { submittable("　") shouldBe false }

    it("rejects a no-break-space-only draft (U+00A0), pinning isSubmittable as it behaves today") {
      // Kotlin's trim() uses Char.isWhitespace(), which includes Character.isSpaceChar(), so
      // U+00A0 IS trimmed (CommentInputDialogTest asserts the same). This pins the shared rule.
      submittable(" ") shouldBe false
    }

    it("accepts real content") { submittable(" hi ") shouldBe true }
  }

  describe("per-thread drafts (§29 #21)") {
    it("keeps each thread's draft and generation separately across selection changes") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("a", "for a")
      s.select("b") shouldBe true
      s.onEdit("b", "for b")
      s.onEdit("b", "for b!")
      s.select("a") shouldBe true
      s.draft("a") shouldBe "for a"
      s.draft("b") shouldBe "for b!"
      s.editGeneration("a") shouldBe 1L
      s.editGeneration("b") shouldBe 2L
    }

    it("bumps the generation on every change") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "x")
      s.onEdit("a", "")
      s.editGeneration("a") shouldBe 2L
    }

    it("does not bump the generation for an echo of the current text") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "x")
      s.onEdit("a", "x")
      s.editGeneration("a") shouldBe 1L
    }

    it("canSubmit looks only at the selected thread's draft") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("b", "text")
      s.canSubmit() shouldBe false
      s.select("b")
      s.canSubmit() shouldBe true
    }

    it("refuses to select an unknown thread") {
      val s = InlineThreadState(model("a"))
      s.select("zzz") shouldBe false
      s.selectedThreadId shouldBe "a"
    }
  }

  describe("busy (§29 #20)") {
    it("beginSubmit returns null and stays idle when the draft is not submittable") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "   ")
      s.beginSubmit().shouldBeNull()
      s.busy shouldBe false
    }

    it("beginSubmit freezes thread id, body and generation, and sets busy") {
      val s = InlineThreadState(model("a", "b"))
      s.select("b")
      s.onEdit("b", "hello")
      val t = s.beginSubmit().shouldNotBeNull()
      t shouldBe SubmitTicket("b", "hello", 1L)
      s.busy shouldBe true
      s.canSubmit() shouldBe false
      s.beginSubmit().shouldBeNull()
    }

    it("refuses selection changes while busy") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("a", "hello")
      s.beginSubmit()
      s.select("b") shouldBe false
      s.selectedThreadId shouldBe "a"
    }

    it("onLaunchRejected releases busy") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("a", "hello")
      val t = s.beginSubmit()!!
      s.onLaunchRejected(t)
      s.busy shouldBe false
      s.select("b") shouldBe true
      s.draft("a") shouldBe "hello"
    }

    it("onAttemptFinished releases busy") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "hello")
      val t = s.beginSubmit()!!
      s.onAttemptFinished(t)
      s.busy shouldBe false
      s.canSubmit() shouldBe true
    }

    it("a stale ticket does not release a newer submit's busy, even when value-equal") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "hello")
      val first = s.beginSubmit()!!
      s.onAttemptFinished(first)
      val second = s.beginSubmit()!!
      second shouldBe first // same thread, body and generation: only identity tells them apart
      s.onAttemptFinished(first) // e.g. a [Retry] of the first launch finishing late
      s.busy shouldBe true
      s.onLaunchRejected(first)
      s.busy shouldBe true
      s.onAttemptFinished(second)
      s.busy shouldBe false
    }
  }

  describe("onSucceeded (§9.3.1 success terminal, §29 #21)") {
    it("a reply with an unedited popup clears that thread's draft") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "hello")
      val t = s.beginSubmit()!!
      s.onSucceeded(t) shouldBe SuccessEffect.CLEAR_DRAFT
      s.draft("a") shouldBe ""
      s.editGeneration("a") shouldNotBe t.generation
      s.unsentDrafts().shouldBeEmpty()
    }

    it("a new thread closes the popup and leaves no unsent draft behind") {
      val s = InlineThreadState(InlineThreadModel(listOf(item(NEW_THREAD_ID))))
      s.onEdit(NEW_THREAD_ID, "new comment")
      val t = s.beginSubmit()!!
      s.onSucceeded(t) shouldBe SuccessEffect.CLOSE
      s.unsentDrafts().shouldBeEmpty()
    }

    it("is NONE and keeps the draft when the user edited after submitting") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "hello")
      val t = s.beginSubmit()!!
      s.onEdit("a", "hello, and more")
      s.onAttemptFinished(t)
      s.onSucceeded(t) shouldBe SuccessEffect.NONE
      s.draft("a") shouldBe "hello, and more"
    }

    it("is NONE for a new thread edited after submitting (no close)") {
      val s = InlineThreadState(InlineThreadModel(listOf(item(NEW_THREAD_ID))))
      s.onEdit(NEW_THREAD_ID, "c")
      val t = s.beginSubmit()!!
      s.onEdit(NEW_THREAD_ID, "cc")
      s.onSucceeded(t) shouldBe SuccessEffect.NONE
      s.draft(NEW_THREAD_ID) shouldBe "cc"
    }

    it("a [Retry] with a changed body still clears the draft when the popup was not edited") {
      val s = InlineThreadState(model("a"))
      s.onEdit("a", "hello")
      val t = s.beginSubmit()!!
      s.onAttemptFinished(t) // first attempt failed; the launcher's [Retry] dialog edits the body
      val retried = t.copy(body = "hello (edited in the Retry dialog)")
      s.onSucceeded(retried) shouldBe SuccessEffect.CLEAR_DRAFT
      s.draft("a") shouldBe ""
    }

    it("applies to the ticket's thread even when another thread is selected") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("a", "for a")
      val t = s.beginSubmit()!!
      s.onAttemptFinished(t)
      s.select("b")
      s.onEdit("b", "for b")
      s.onSucceeded(t) shouldBe SuccessEffect.CLEAR_DRAFT
      s.draft("a") shouldBe ""
      s.draft("b") shouldBe "for b"
      s.selectedThreadId shouldBe "b"
    }
  }

  describe("unsentDrafts (§29 #22)") {
    it("lists only submittable drafts") {
      val s = InlineThreadState(model("a", "b", "c"))
      s.onEdit("a", "keep me")
      s.onEdit("b", "  ")
      s.onEdit("c", "me too")
      s.unsentDrafts() shouldContainExactlyInAnyOrder listOf("keep me", "me too")
    }

    it("is empty for a fresh state") {
      InlineThreadState(model("a")).unsentDrafts().shouldBeEmpty()
    }
  }

  describe("replaceModel") {
    it("keeps drafts and selection by thread id") {
      val s = InlineThreadState(model("a", "b"))
      s.select("b")
      s.onEdit("b", "draft b")
      s.replaceModel(model("x", "b"))
      s.selectedThreadId shouldBe "b"
      s.draft("b") shouldBe "draft b"
      s.editGeneration("b") shouldBe 1L
      s.canSubmit() shouldBe true
    }

    it("selects the first item when the selected thread vanished") {
      val s = InlineThreadState(model("a", "b"))
      s.select("b")
      s.replaceModel(model("c", "a"))
      s.selectedThreadId shouldBe "c"
    }

    it("still reports a vanished thread's draft as unsent") {
      val s = InlineThreadState(model("a", "b"))
      s.onEdit("b", "orphan")
      s.replaceModel(model("a"))
      s.unsentDrafts() shouldContainExactly listOf("orphan")
    }

    it("rejects an empty replacement") {
      val s = InlineThreadState(model("a"))
      shouldThrow<IllegalArgumentException> { s.replaceModel(InlineThreadModel(emptyList())) }
      s.selectedThreadId shouldBe "a"
    }
  }
})
