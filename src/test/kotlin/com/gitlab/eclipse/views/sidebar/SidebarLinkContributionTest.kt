package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val OPEN_COMMAND = "com.gitlab.eclipse.commands.OpenSidebarItemInGitLab"
private const val COPY_COMMAND = "com.gitlab.eclipse.commands.CopySidebarItemLink"

/**
 * Design A D4/D10: the plugin.xml wiring of the sidebar's Open in GitLab / Copy Link actions. A typo in any of these
 * ids is not seen by the compiler and only shows on real hardware as a menu item that never appears.
 */
class SidebarLinkContributionTest : StringSpec({
  val root: Element = checkNotNull(javaClass.classLoader.getResourceAsStream("plugin.xml")).use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.children(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  fun Element.elementChildren(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

  "both commands are declared once, named and in the GitLab category" {
    val byId = root.children("command").filter { it.parentNode.let { p -> (p as Element).tagName == "extension" } }
      .associateBy { it.getAttribute("id") }
    mapOf(OPEN_COMMAND to "Open in GitLab", COPY_COMMAND to "Copy Link to Clipboard").forEach { (id, name) ->
      val command = checkNotNull(byId[id]) { "command $id" }
      command.getAttribute("name") shouldBe name
      command.getAttribute("categoryId") shouldBe "gitlab-eclipse-plugin"
    }
  }

  "each command has exactly its handler, with no enabledWhen, and no key binding" {
    mapOf(
      OPEN_COMMAND to OpenSidebarItemInGitLabHandler::class.java.name,
      COPY_COMMAND to CopySidebarItemLinkHandler::class.java.name,
    ).forEach { (id, handlerClass) ->
      val handlers = root.children("handler").filter { it.getAttribute("commandId") == id }
      handlers shouldHaveSize 1
      handlers.single().getAttribute("class") shouldBe handlerClass
      handlers.single().children("enabledWhen").shouldBeEmpty()
      root.children("key").filter { it.getAttribute("commandId") == id }.shouldBeEmpty()
    }
  }

  "the hasWebUrl property tester is registered for sidebar nodes" {
    val testers = root.children("propertyTester").filter {
      it.getAttribute("class") == SidebarLinkPropertyTester::class.java.name
    }
    testers shouldHaveSize 1
    val tester = testers.single()
    tester.getAttribute("namespace") shouldBe "com.gitlab.eclipse.node"
    tester.getAttribute("type") shouldBe SidebarNode::class.java.name
    tester.getAttribute("properties") shouldBe HAS_WEB_URL_PROPERTY
  }

  "the sidebar popup starts with Open, Copy and the links separator, gated on hasWebUrl over the menu selection" {
    val contributions = root.children("menuContribution").filter {
      it.getAttribute("locationURI") == "popup:com.gitlab.eclipse.views.GitLabSidebarView"
    }
    contributions shouldHaveSize 1
    val head = contributions.single().elementChildren().take(3)

    head.map { it.tagName } shouldContainExactly listOf("command", "command", "separator")
    head.take(2).map { it.getAttribute("commandId") } shouldContainExactly listOf(OPEN_COMMAND, COPY_COMMAND)
    head[2].getAttribute("name") shouldBe "com.gitlab.eclipse.sidebar.links"
    head[2].getAttribute("visible") shouldBe "true"

    head.take(2).forEach { command ->
      val visibleWhen = command.elementChildren().single()
      visibleWhen.tagName shouldBe "visibleWhen"
      visibleWhen.getAttribute("checkEnabled") shouldBe "false"
      val with = visibleWhen.elementChildren().single()
      with.tagName shouldBe "with"
      with.getAttribute("variable") shouldBe "activeMenuSelection"
      val iterate = with.elementChildren().single()
      iterate.tagName shouldBe "iterate"
      iterate.getAttribute("ifEmpty") shouldBe "false"
      val test = iterate.elementChildren().single()
      test.tagName shouldBe "test"
      test.getAttribute("property") shouldBe "com.gitlab.eclipse.node.$HAS_WEB_URL_PROPERTY"
    }
  }
})
