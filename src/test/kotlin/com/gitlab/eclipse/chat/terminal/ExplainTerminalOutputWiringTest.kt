package com.gitlab.eclipse.chat.terminal

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val COMMAND_ID = "gitlab-eclipse-plugin.commands.ExplainTerminalOutput"

/** Checks the plugin.xml wiring, which neither the compiler nor any other test sees. */
class ExplainTerminalOutputWiringTest : DescribeSpec({

  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  it("declares the command and binds its handler") {
    root.descendants("command").filter { it.getAttribute("id") == COMMAND_ID } shouldHaveSize 1
    root.descendants("handler").filter { it.getAttribute("commandId") == COMMAND_ID }
      .map { it.getAttribute("class") } shouldBe listOf(ExplainTerminalOutputHandler::class.java.name)
  }

  it("registers the source provider for the gate variable") {
    val provider = root.descendants("sourceProvider")
      .single { it.getAttribute("provider") == TerminalContextSourceProvider::class.java.name }
    provider.descendants("variable").map { it.getAttribute("name") } shouldBe
      listOf(TerminalContextSourceProvider.ENABLED_KEY)
  }

  it("contributes to both Terminal generations and the Console pages, gated on the variable") {
    val contributions = root.descendants("menuContribution").filter { contribution ->
      contribution.descendants("command").any { it.getAttribute("commandId") == COMMAND_ID }
    }

    contributions.map { it.getAttribute("locationURI") } shouldContainExactlyInAnyOrder listOf(
      "popup:org.eclipse.terminal.view.ui.TerminalsView?after=additions",
      "popup:org.eclipse.tm.terminal.view.ui.TerminalsView?after=additions",
      "popup:org.eclipse.debug.ui.ProcessConsoleType.#ContextMenu?after=additions",
      "popup:org.eclipse.ui.MessageConsole.#ContextMenu?after=additions",
      "popup:#ContextMenu?after=additions",
    )
    contributions.forEach { contribution ->
      val command = contribution.descendants("command").single { it.getAttribute("commandId") == COMMAND_ID }
      val with = command.descendants("with").single()
      with.getAttribute("variable") shouldBe TerminalContextSourceProvider.ENABLED_KEY
      with.descendants("equals").single().getAttribute("value") shouldBe "true"
    }
  }

  it("uses no contribution id the Terminal view strips from its menu") {
    root.descendants("menuContribution")
      .flatMap { it.descendants("command") }
      .filter { it.getAttribute("commandId") == COMMAND_ID }
      .forEach { command ->
        command.getAttribute("id") shouldNotStartWith "org.eclipse.ui.edit"
        command.getAttribute("id") shouldNotStartWith "org.eclipse.cdt"
      }
  }

  it("binds no key: the Terminal disables workbench key bindings while it has focus") {
    root.descendants("key").filter { it.getAttribute("commandId") == COMMAND_ID }.shouldBeEmpty()
  }
})
