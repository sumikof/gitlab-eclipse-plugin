package com.gitlab.eclipse.chat.quickchat

import com.gitlab.eclipse.chat.quickchat.QuickChatConversation.Entry
import com.gitlab.eclipse.views.inlinethread.InlineThreadAction
import com.gitlab.eclipse.views.inlinethread.NEW_THREAD_ID
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith

class QuickChatConversationTest : DescribeSpec({
  describe("in-flight send") {
    it("begin records the ticket, the current generation, the deadline and a fresh open gate") {
      val conversation = QuickChatConversation()
      val t = ticket()
      val send = conversation.begin(t, deadlineNanos = 42)
      send.ticket shouldBe t
      send.generation shouldBe conversation.generation
      send.deadlineNanos shouldBe 42
      send.gate.state shouldBe SendGate.State.Open
      conversation.inFlight shouldBe send
    }

    it("isCurrent matches only the same ticket object and generation while in flight") {
      val conversation = QuickChatConversation()
      val t = ticket()
      val gen = conversation.begin(t, 0).generation
      conversation.isCurrent(t, gen) shouldBe true
      conversation.isCurrent(ticket(), gen) shouldBe false // an equal but different ticket is stale
      conversation.isCurrent(t, gen + 1) shouldBe false
      conversation.clearInFlight().shouldNotBeNull()
      conversation.isCurrent(t, gen) shouldBe false
      conversation.clearInFlight().shouldBeNull()
    }

    it("advancing the generation makes the send stale") {
      val conversation = QuickChatConversation()
      val t = ticket()
      val gen = conversation.begin(t, 0).generation
      conversation.advanceGeneration()
      conversation.isCurrent(t, gen) shouldBe false
    }
  }

  describe("entries") {
    it("resolvePending replaces the pending entry, or appends when there is none") {
      val conversation = QuickChatConversation()
      conversation.add(Entry.Question("q"))
      conversation.add(Entry.Pending)
      conversation.resolvePending(Entry.Failure("f"))
      conversation.entries shouldContainExactly listOf(Entry.Question("q"), Entry.Failure("f"))
      conversation.resolvePending(Entry.Failure("g"))
      conversation.entries shouldContainExactly listOf(Entry.Question("q"), Entry.Failure("f"), Entry.Failure("g"))
    }

    it("inserts the separator right before the pending send's question (A26)") {
      val conversation = QuickChatConversation()
      conversation.add(Entry.Question("q1"))
      conversation.add(Entry.Answer("a1"))
      conversation.add(Entry.Question("q2"))
      conversation.add(Entry.Pending)
      conversation.insertSeparatorBeforePendingQuestion()
      conversation.entries shouldContainExactly listOf(
        Entry.Question("q1"),
        Entry.Answer("a1"),
        Entry.Separator,
        Entry.Question("q2"),
        Entry.Pending,
      )
    }

    it("clearEntries empties the pane and forgets the removal marker") {
      val conversation = QuickChatConversation()
      repeat(QuickChatConversation.MAX_ENTRIES + 1) { conversation.add(Entry.Question("q$it")) }
      conversation.clearEntries()
      conversation.entries shouldBe emptyList()
      conversation.toInlineModel().items.single().entries shouldBe emptyList()
    }
  }

  describe("limits (A27, conversation part)") {
    it("keeps the latest 40 entries and shows the removal marker first after the 41st") {
      val conversation = QuickChatConversation()
      repeat(QuickChatConversation.MAX_ENTRIES) { conversation.add(Entry.Question("q$it")) }
      conversation.earlierRemoved shouldBe false
      conversation.add(Entry.Question("q40"))
      conversation.entries.size shouldBe QuickChatConversation.MAX_ENTRIES
      conversation.entries.first() shouldBe Entry.Question("q1")
      conversation.entries.last() shouldBe Entry.Question("q40")
      conversation.earlierRemoved shouldBe true
      val shown = conversation.toInlineModel().items.single().entries
      shown.size shouldBe QuickChatConversation.MAX_ENTRIES + 1
      shown.first().body shouldBe QuickChatTexts.EARLIER_REMOVED
    }

    it("keeps the binding when old entries are removed") {
      val conversation = QuickChatConversation()
      val binding = ConversationBinding(INSTANCE, PROJECT_PREFLIGHT, "thread")
      conversation.binding = binding
      repeat(QuickChatConversation.MAX_ENTRIES + 5) { conversation.add(Entry.Question("q$it")) }
      conversation.binding shouldBe binding
    }

    it("stores an answer up to 256 KiB unchanged") {
      val conversation = QuickChatConversation()
      val exact = "a".repeat(QuickChatConversation.MAX_ANSWER_BYTES)
      conversation.storeAnswer(exact)
      conversation.entries.single() shouldBe Entry.Answer(exact)
    }

    it("truncates a longer answer on a code point boundary and marks it") {
      val conversation = QuickChatConversation()
      // 3-byte characters: 256 KiB is not a multiple of 3, so a naive byte cut would split one.
      val long = "あ".repeat(QuickChatConversation.MAX_ANSWER_BYTES / 3 + 10)
      conversation.storeAnswer(long)
      val stored = (conversation.entries.single() as Entry.Answer).markdown
      stored shouldEndWith QuickChatTexts.ANSWER_TRUNCATED
      val kept = stored.removeSuffix("\n\n" + QuickChatTexts.ANSWER_TRUNCATED)
      kept shouldBe "あ".repeat(QuickChatConversation.MAX_ANSWER_BYTES / 3)
    }
  }

  describe("toInlineModel") {
    it("is one reply-only item that is not the new-thread item, with authors per entry kind") {
      val conversation = QuickChatConversation()
      conversation.add(Entry.Question("q"))
      conversation.add(Entry.Answer("a"))
      conversation.add(Entry.Separator)
      conversation.add(Entry.Failure("f"))
      conversation.add(Entry.Pending)
      val item = conversation.toInlineModel().items.single()
      item.threadId shouldBe QUICK_CHAT_ITEM_ID
      item.threadId shouldNotBe NEW_THREAD_ID
      item.actions shouldBe setOf(InlineThreadAction.REPLY)
      item.resolved.shouldBeNull()
      item.entries.map { it.author } shouldContainExactly listOf(
        QuickChatTexts.AUTHOR_YOU,
        QuickChatTexts.AUTHOR_DUO,
        "",
        QuickChatTexts.AUTHOR_DUO,
        QuickChatTexts.AUTHOR_DUO,
      )
      item.entries.map { it.body } shouldContainExactly listOf(
        "q",
        "a",
        QuickChatTexts.NEW_CHAT,
        "f",
        QuickChatTexts.WAITING,
      )
      item.entries.map { it.createdAt }.toSet() shouldBe setOf("")
    }
  }
})
