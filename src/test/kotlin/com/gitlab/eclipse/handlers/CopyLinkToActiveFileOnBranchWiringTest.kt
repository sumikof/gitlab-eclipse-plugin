package com.gitlab.eclipse.handlers

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val NAV02 = "gitlab-eclipse-plugin.commands.CopyLinkToActiveFile"
private const val NAV03 = "gitlab-eclipse-plugin.commands.CopyLinkToActiveFileOnBranch"

/** NAV-03's plugin.xml wiring (declaration, handler, editor menu), which neither compiler nor workbench checks. */
class CopyLinkToActiveFileOnBranchWiringTest : StringSpec({
  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  fun Element.elementChildren(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

  fun extensions(point: String): List<Element> =
    root.elementChildren().filter { it.tagName == "extension" && it.getAttribute("point") == point }

  fun declaration(id: String): Element =
    extensions("org.eclipse.ui.commands").flatMap { it.elementChildren() }
      .single { it.tagName == "command" && it.getAttribute("id") == id }

  fun handlers(commandId: String): List<Element> =
    extensions("org.eclipse.ui.handlers").flatMap { it.elementChildren() }
      .filter { it.getAttribute("commandId") == commandId }

  val navigationMenu: Element =
    root.descendants("menu").single { it.getAttribute("id") == "com.gitlab.eclipse.navigation.menu" }

  "the command is declared in the GitLab category with its Keys name" {
    val command = declaration(NAV03)
    command.getAttribute("name") shouldBe "Copy GitLab Link to Active File on Branch"
    command.getAttribute("categoryId") shouldBe "gitlab-eclipse-plugin"
  }

  "one unconditional handler runs it, like NAV-02's" {
    val handler = handlers(NAV03).single()
    handler.getAttribute("class") shouldBe "com.gitlab.eclipse.handlers.CopyLinkToActiveFileOnBranchHandler"
    handler.elementChildren().shouldBeEmpty()
  }

  "the editor's GitLab menu offers it right after NAV-02's entry" {
    val commands = navigationMenu.elementChildren().filter { it.tagName == "command" }
    val nav02 = commands.indexOfFirst { it.getAttribute("commandId") == NAV02 }
    commands[nav02 + 1].getAttribute("commandId") shouldBe NAV03
    commands[nav02 + 1].getAttribute("label") shouldBe "Copy Link to Active File on Branch"
    root.descendants("command").count { it.getAttribute("commandId") == NAV03 } shouldBe 1
  }

  "no key is bound to it" {
    root.descendants("key").filter { it.getAttribute("commandId") == NAV03 }.shouldBeEmpty()
  }

  "NAV-02's declaration, handler and menu entry stay as they were" {
    declaration(NAV02).getAttribute("name") shouldBe "Copy GitLab Link to Active File"
    handlers(NAV02).single().getAttribute("class") shouldBe "com.gitlab.eclipse.handlers.CopyLinkToActiveFileHandler"
    navigationMenu.elementChildren().single { it.getAttribute("commandId") == NAV02 }.getAttribute("label") shouldBe
      "Copy Link to Active File"
  }
})
