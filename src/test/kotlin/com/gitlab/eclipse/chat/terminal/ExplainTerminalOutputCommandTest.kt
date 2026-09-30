package com.gitlab.eclipse.chat.terminal

import com.gitlab.eclipse.lsp.NewPromptRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.eclipse.jface.text.Document
import org.eclipse.jface.text.TextSelection
import org.eclipse.jface.viewers.StructuredSelection
import java.io.File

private const val SECRET = "TOKEN=glpat-very-secret-value-123"

class ExplainTerminalOutputCommandTest : DescribeSpec({

  class Harness(var available: Boolean = true, var sendFails: Boolean = false) {
    val sent = mutableListOf<NewPromptRequest>()
    val notified = mutableListOf<String>()
    val logged = mutableListOf<String>()
    val command = ExplainTerminalOutputCommand(
      isAvailable = { available },
      send = {
        check(!sendFails) { "send failed: $SECRET" }
        sent += it
      },
      notify = { notified += it },
      log = { logged += it },
    )
  }

  fun console(text: String) = TextSelection(Document(text), 0, text.length)
  fun terminal(text: String?) = StructuredSelection(FakeTerminalTab(FakeTerminalControl(text)))

  describe("happy path") {
    it("sends the Console selection as an explainTerminalOutput prompt") {
      val h = Harness()

      h.command.run(console("error: boom")) shouldBe ExplainTerminalOutputCommand.Outcome.SENT

      h.sent shouldHaveSize 1
      h.sent.single().prompt shouldBe "explainTerminalOutput"
      h.sent.single().fileContext!!.selectedText shouldBe "error: boom"
      h.notified.shouldBeEmpty()
    }

    it("sends the Terminal selection of either generation") {
      val h = Harness()

      h.command.run(terminal("new gen")) shouldBe ExplainTerminalOutputCommand.Outcome.SENT
      h.command.run(StructuredSelection(FakeTerminalTab(FakeLegacyTerminalControl("old gen")))) shouldBe
        ExplainTerminalOutputCommand.Outcome.SENT

      h.sent.map { it.fileContext!!.selectedText } shouldContainExactly listOf("new gen", "old gen")
    }

    it("sends the tail and tells the user when the selection was too long") {
      val h = Harness()
      val long = "x".repeat(TerminalOutputPrompt.MAX_LENGTH + 10)

      h.command.run(console(long)) shouldBe ExplainTerminalOutputCommand.Outcome.SENT_TRUNCATED

      h.sent.single().fileContext!!.selectedText.length shouldBe TerminalOutputPrompt.MAX_LENGTH
      h.notified shouldContainExactly listOf(ExplainTerminalOutputCommand.TRUNCATED_MESSAGE)
    }
  }

  describe("runtime gate (a visible menu item is not an authorization)") {
    it("sends nothing and does not even read the selection when unavailable") {
      val h = Harness(available = false)
      var read = false
      val command = ExplainTerminalOutputCommand(
        isAvailable = { false },
        readSelection = {
          read = true
          TerminalSelection.Text("x")
        },
        send = { h.sent += it },
        notify = { h.notified += it },
        log = { h.logged += it },
      )

      command.run(console("error")) shouldBe ExplainTerminalOutputCommand.Outcome.NOT_AVAILABLE

      read shouldBe false
      h.sent.shouldBeEmpty()
      h.notified shouldContainExactly listOf(ExplainTerminalOutputCommand.NOT_AVAILABLE_MESSAGE)
    }
  }

  describe("no usable selection") {
    it("tells the user to select output first when nothing is selected") {
      val h = Harness()

      h.command.run(terminal("")) shouldBe ExplainTerminalOutputCommand.Outcome.NO_SELECTION

      h.sent.shouldBeEmpty()
      h.notified shouldContainExactly listOf(ExplainTerminalOutputCommand.NO_SELECTION_MESSAGE)
    }

    it("reports an unreadable view without sending") {
      val h = Harness()

      h.command.run(StructuredSelection(FakeTerminalTab(Any()))) shouldBe
        ExplainTerminalOutputCommand.Outcome.UNREADABLE

      h.sent.shouldBeEmpty()
      h.notified shouldContainExactly listOf(ExplainTerminalOutputCommand.UNREADABLE_MESSAGE)
      h.logged shouldHaveSize 1
    }
  }

  describe("failure keeps the user's text") {
    it("reports a send failure and leaves the selection itself untouched") {
      val h = Harness(sendFails = true)
      val document = Document("before\n$SECRET\nafter")
      val selection = TextSelection(document, 7, SECRET.length)

      h.command.run(selection) shouldBe ExplainTerminalOutputCommand.Outcome.SEND_FAILED

      document.get() shouldBe "before\n$SECRET\nafter"
      selection.text shouldBe SECRET
      h.notified shouldContainExactly listOf(ExplainTerminalOutputCommand.SEND_FAILED_MESSAGE)
    }

    it("can be run again after a failure, sending the same text") {
      val h = Harness(sendFails = true)
      val selection = console("retry me")
      h.command.run(selection)

      h.sendFails = false
      h.command.run(selection) shouldBe ExplainTerminalOutputCommand.Outcome.SENT

      h.sent.single().fileContext!!.selectedText shouldBe "retry me"
    }
  }

  describe("concurrent use") {
    it("keeps no state between runs: each prompt carries only its own selection") {
      val h = Harness()

      h.command.run(console("first output"))
      h.command.run(terminal("second output"))

      h.sent.map { it.fileContext!!.selectedText } shouldContainExactly listOf("first output", "second output")
    }
  }

  describe("log hygiene") {
    it("never logs the selected text, its length, or exception messages") {
      val h = Harness(sendFails = true)
      h.command.run(console(SECRET))
      h.command.run(StructuredSelection(FakeTerminalTab(FakeThrowingControl())))

      h.logged shouldHaveSize 2
      h.logged.forEach { line ->
        line shouldNotContain SECRET
        line shouldNotContain "glpat"
        line shouldNotContain SECRET.length.toString()
        line shouldNotContain "secret-in-message"
      }
      h.notified.forEach { it shouldNotContain SECRET }
    }
  }

  describe("no files") {
    it("writes nothing to the temporary directory") {
      val tmp = File(System.getProperty("java.io.tmpdir"))
      val before = tmp.list()?.toSet().orEmpty()
      val h = Harness()

      h.command.run(console(SECRET))
      h.command.run(terminal(SECRET))

      (tmp.list()?.toSet().orEmpty() - before).shouldBeEmpty()
      h.sent.forEach { File(it.fileContext!!.fileName).exists() shouldBe false }
    }
  }
})
