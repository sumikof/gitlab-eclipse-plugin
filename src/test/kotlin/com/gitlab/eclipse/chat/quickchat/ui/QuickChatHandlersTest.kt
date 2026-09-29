package com.gitlab.eclipse.chat.quickchat.ui

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.eclipse.ui.IWorkbenchWindow
import org.eclipse.ui.texteditor.ITextEditor

class QuickChatHandlersTest : DescribeSpec({
  describe("openQuickChat (design §9.1 steps 1–2)") {
    val editor = mockk<ITextEditor>()

    class Recorder {
      val notified = mutableListOf<String>()
      val opened = mutableListOf<Pair<ITextEditor, Int>>()
      var editorAsked = false

      fun run(unavailableReason: String?, activeEditor: ITextEditor?, anchor: Int?) = openQuickChat(
        unavailableReason = { unavailableReason },
        notify = { notified += it },
        activeEditor = {
          editorAsked = true
          activeEditor
        },
        anchorLine = { anchor },
        open = { e, line -> opened += e to line },
      )
    }

    it("opens the popup of the active text editor at its anchor line") {
      val r = Recorder()
      r.run(unavailableReason = null, activeEditor = editor, anchor = 7)
      r.opened shouldContainExactly listOf(editor to 7)
      r.notified.shouldBeEmpty()
    }

    it("notifies the reason and opens nothing when Duo Chat became unavailable") {
      val r = Recorder()
      r.run(unavailableReason = "Duo Chat is disabled.", activeEditor = editor, anchor = 7)
      r.notified shouldContainExactly listOf("Duo Chat is disabled.")
      r.opened.shouldBeEmpty()
      r.editorAsked shouldBe false
    }

    it("does nothing without an active text editor") {
      val r = Recorder()
      r.run(unavailableReason = null, activeEditor = null, anchor = 7)
      r.opened.shouldBeEmpty()
      r.notified.shouldBeEmpty()
    }

    it("does nothing when the editor has no line to anchor on") {
      val r = Recorder()
      r.run(unavailableReason = null, activeEditor = editor, anchor = null)
      r.opened.shouldBeEmpty()
      r.notified.shouldBeEmpty()
    }
  }

  describe("closeQuickChatEnabled (design §9.5)") {
    it("is false without an active window") {
      closeQuickChatEnabled(null) { true } shouldBe false
    }

    it("follows whether the active window shows a Quick Chat") {
      val window = mockk<IWorkbenchWindow>()
      closeQuickChatEnabled(window) { it === window } shouldBe true
      closeQuickChatEnabled(window) { false } shouldBe false
    }
  }
})
