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
    val window = mockk<IWorkbenchWindow>()
    val other = mockk<IWorkbenchWindow>()

    it("on the UI thread follows whether the active window shows a Quick Chat") {
      closeQuickChatEnabled(onUiThread = true, activeWindow = { window }, openWindows = setOf(window)) shouldBe true
      closeQuickChatEnabled(onUiThread = true, activeWindow = { window }, openWindows = setOf(other)) shouldBe false
      closeQuickChatEnabled(onUiThread = true, activeWindow = { window }, openWindows = emptySet()) shouldBe false
    }

    it("on the UI thread is false without an active window") {
      closeQuickChatEnabled(onUiThread = true, activeWindow = { null }, openWindows = setOf(window)) shouldBe false
    }

    it("off the UI thread never asks for the active window and follows whether any window shows one") {
      var asked = false
      val activeWindow = {
        asked = true
        window
      }
      closeQuickChatEnabled(onUiThread = false, activeWindow = activeWindow, openWindows = setOf(other)) shouldBe true
      closeQuickChatEnabled(onUiThread = false, activeWindow = activeWindow, openWindows = emptySet()) shouldBe false
      asked shouldBe false
    }
  }

  describe("OpenWindows (the thread-safe snapshot of QuickChatPopups)") {
    it("starts empty and publishes an immutable copy") {
      val snapshot = OpenWindows<String>()
      snapshot.current.shouldBeEmpty()
      val source = mutableSetOf("w1")
      snapshot.publish(source)
      source += "w2"
      snapshot.current shouldBe setOf("w1")
    }

    it("tells its listeners when the set changes, and only then") {
      val snapshot = OpenWindows<String>()
      val calls = mutableListOf<Set<String>>()
      val listener = { calls += snapshot.current }
      snapshot.addListener(listener)
      snapshot.publish(setOf("w1"))
      snapshot.publish(setOf("w1"))
      snapshot.publish(emptySet())
      calls shouldContainExactly listOf(setOf("w1"), emptySet())
    }

    it("stops telling a removed listener, and one failing listener does not stop the others") {
      val snapshot = OpenWindows<String>()
      val calls = mutableListOf<String>()
      val removed = { calls += "removed" }
      snapshot.addListener { error("broken") }
      snapshot.addListener(removed)
      snapshot.addListener { calls += "kept" }
      snapshot.removeListener(removed)
      snapshot.publish(setOf("w1"))
      calls shouldContainExactly listOf("kept")
    }
  }
})
