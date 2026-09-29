package com.gitlab.eclipse.chat.quickchat.ui

import com.gitlab.eclipse.chat.quickchat.QUICK_CHAT_ITEM_ID
import com.gitlab.eclipse.chat.quickchat.QuickChatConversation
import com.gitlab.eclipse.chat.quickchat.QuickChatSession
import com.gitlab.eclipse.chat.quickchat.toInlineModel
import com.gitlab.eclipse.views.inlinethread.CodeBlockAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.InlineThreadModel
import com.gitlab.eclipse.views.inlinethread.InlineThreadState
import com.gitlab.eclipse.views.inlinethread.InlineThreadSurface
import com.gitlab.eclipse.views.inlinethread.SubmitTicket
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/** The popup as the host sees it: the real state plus a log of what the host asked the widgets to do. */
private class FakeSurface(model: InlineThreadModel) : InlineThreadSurface {
  override val state = InlineThreadState(model)
  override var isOpen = true
  val events = mutableListOf<String>()
  val models = mutableListOf<InlineThreadModel>()

  override fun refresh() {
    events += "refresh(busy=${state.busy}, draft=${state.draft(QUICK_CHAT_ITEM_ID)})"
  }

  override fun update(model: InlineThreadModel) {
    state.replaceModel(model)
    models += model
    events += "update"
  }

  override fun close() {
    isOpen = false
    events += "close"
  }
}

private class Harness {
  val surface = FakeSurface(QuickChatConversation().toInlineModel())
  val session = mockk<QuickChatSession>(relaxed = true)
  val events = mutableListOf<String>()
  val preserved = mutableListOf<String>()
  val codeActions = mutableListOf<Pair<CodeBlockAction, String>>()
  val host = QuickChatHost(
    preserveDraft = { preserved += it },
    onPopupClosed = { events += "popupClosed" },
    codeAction = { action, code -> codeActions += action to code },
  )

  init {
    every { session.end() } answers { events += "end" }
    host.bind(session, surface)
  }

  /** Types [text] and presses Send, as the popup would before calling the host. */
  fun beginSubmit(text: String = "question"): SubmitTicket {
    surface.state.onEdit(QUICK_CHAT_ITEM_ID, text)
    return checkNotNull(surface.state.beginSubmit())
  }
}

class QuickChatHostTest : DescribeSpec({
  describe("onSubmit") {
    it("hands the very ticket object the popup froze to the session") {
      val h = Harness()
      val ticket = h.beginSubmit()
      h.host.onSubmit(h.surface, ticket)
      verify(exactly = 1) { h.session.submit(match { it === ticket }) }
    }

    it("releases busy when no session is bound, so the popup is not stuck") {
      val host = QuickChatHost(preserveDraft = {}, onPopupClosed = {}, codeAction = { _, _ -> })
      val surface = FakeSurface(QuickChatConversation().toInlineModel())
      surface.state.onEdit(QUICK_CHAT_ITEM_ID, "q")
      val ticket = checkNotNull(surface.state.beginSubmit())
      host.onSubmit(surface, ticket)
      surface.state.busy shouldBe false
      surface.state.draft(QUICK_CHAT_ITEM_ID) shouldBe "q"
      surface.events shouldContainExactly listOf("refresh(busy=false, draft=q)")
    }
  }

  describe("render") {
    it("swaps the surface's model") {
      val h = Harness()
      val model = QuickChatConversation().toInlineModel()
      h.host.render(model)
      h.surface.models.single() shouldBeSameInstanceAs model
      h.surface.events shouldContainExactly listOf("update")
    }
  }

  describe("released") {
    it("on success clears the sent draft, releases busy and refreshes") {
      val h = Harness()
      val ticket = h.beginSubmit("q1")
      h.host.released(ticket, succeeded = true)
      h.surface.state.busy shouldBe false
      h.surface.state.draft(QUICK_CHAT_ITEM_ID) shouldBe ""
      h.surface.events shouldContainExactly listOf("refresh(busy=false, draft=)")
      h.surface.isOpen shouldBe true
    }

    it("on failure keeps the draft, releases busy and refreshes") {
      val h = Harness()
      val ticket = h.beginSubmit("q1")
      h.host.released(ticket, succeeded = false)
      h.surface.state.busy shouldBe false
      h.surface.state.draft(QUICK_CHAT_ITEM_ID) shouldBe "q1"
      h.surface.events shouldContainExactly listOf("refresh(busy=false, draft=q1)")
    }

    it("does not clear text the user typed after pressing Send") {
      val h = Harness()
      val ticket = h.beginSubmit("q1")
      h.surface.state.onEdit(QUICK_CHAT_ITEM_ID, "next question")
      h.host.released(ticket, succeeded = true)
      h.surface.state.busy shouldBe false
      h.surface.state.draft(QUICK_CHAT_ITEM_ID) shouldBe "next question"
    }
  }

  describe("onAction") {
    it("does nothing (Quick Chat has no resolve buttons)") {
      val h = Harness()
      h.host.onAction(h.surface, InlineThreadAction.RESOLVE)
      h.surface.events shouldContainExactly emptyList()
      verify(exactly = 0) { h.session.submit(any()) }
    }
  }

  describe("preserveDrafts") {
    it("offers every draft, in order") {
      val h = Harness()
      h.host.preserveDrafts(listOf("a", "b"))
      h.preserved shouldContainExactly listOf("a", "b")
    }
  }

  describe("onClosed") {
    it("ends the session, then drops the popup") {
      val h = Harness()
      h.host.onClosed()
      h.events shouldContainExactly listOf("end", "popupClosed")
    }

    it("still drops the popup when ending the session throws") {
      val h = Harness()
      every { h.session.end() } throws IllegalStateException("boom")
      runCatching { h.host.onClosed() }
      h.events shouldContainExactly listOf("popupClosed")
    }

    it("drops the popup when no session was bound") {
      val events = mutableListOf<String>()
      val host = QuickChatHost(
        preserveDraft = {},
        onPopupClosed = { events += "popupClosed" },
        codeAction = { _, _ -> },
      )
      host.onClosed()
      events shouldContainExactly listOf("popupClosed")
    }
  }

  describe("onCodeAction") {
    it("passes the action and the block's code to the injected handler") {
      val h = Harness()
      h.host.onCodeAction(h.surface, CodeBlockAction.INSERT, "val x = 1")
      h.host.onCodeAction(h.surface, CodeBlockAction.COPY, "y")
      h.codeActions shouldContainExactly listOf(CodeBlockAction.INSERT to "val x = 1", CodeBlockAction.COPY to "y")
    }
  }
})
