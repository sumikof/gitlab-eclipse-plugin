package com.gitlab.eclipse.chat.quickchat.fix

import com.gitlab.eclipse.chat.quickchat.fix.ui.FixWithDuoHandler
import com.gitlab.eclipse.chat.quickchat.fix.ui.FixWithDuoMarkerResolutionGenerator
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val COMMAND_ID = "gitlab-eclipse-plugin.commands.FixWithDuo"
private const val OPEN_QUICK_CHAT_ID = "gitlab-eclipse-plugin.commands.OpenQuickChat"

/** Checks the plugin.xml wiring of Fix with GitLab Duo, which neither the compiler nor any other test sees. */
class FixWithDuoWiringTest : DescribeSpec({

  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  fun Element.children(tag: String): List<Element> {
    val nodes = childNodes
    return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }.filter { it.tagName == tag }
  }

  /** The single `<with variable="[variable]"><equals value="true"/></with>` under this element. */
  fun Element.requiresTrue(variable: String) {
    val with = descendants("with").single()
    with.getAttribute("variable") shouldBe variable
    with.descendants("equals").single().getAttribute("value") shouldBe "true"
  }

  it("registers the marker resolution generator for every marker type") {
    val generators = root.descendants("extension")
      .filter { it.getAttribute("point") == "org.eclipse.ui.ide.markerResolution" }
      .flatMap { it.descendants("markerResolutionGenerator") }
      .filter { it.getAttribute("class") == FixWithDuoMarkerResolutionGenerator::class.java.name }
    generators shouldHaveSize 1
    // A markerType would match that exact type only (markerResolution.exsd); hasResolutions narrows instead.
    generators.single().hasAttribute("markerType") shouldBe false
  }

  it("declares the editor command in the GitLab Duo category") {
    val commands = root.children("extension")
      .filter { it.getAttribute("point") == "org.eclipse.ui.commands" }
      .flatMap { it.children("command") }
      .filter { it.getAttribute("id") == COMMAND_ID }
    commands shouldHaveSize 1
    commands.single().getAttribute("name") shouldBe "Fix with GitLab Duo"
    commands.single().getAttribute("categoryId") shouldBe "gitlab-eclipse-plugin"
  }

  it("binds the handler, enabled while Duo Chat is") {
    val handlers = root.descendants("handler").filter { it.getAttribute("commandId") == COMMAND_ID }
    handlers shouldHaveSize 1
    handlers.single().getAttribute("class") shouldBe FixWithDuoHandler::class.java.name
    handlers.single().children("enabledWhen").single().requiresTrue("duo_chat_enabled")
  }

  it("follows Open Quick Chat in the editor's GitLab Duo Chat submenu, shown while Duo Chat is enabled") {
    val items = root.descendants("command").filter { it.getAttribute("commandId") == COMMAND_ID }
    items shouldHaveSize 1
    val item = items.single()
    val menu = item.parentNode as Element
    menu.getAttribute("id") shouldBe "com.gitlab.eclipse.chat.menu"
    (menu.parentNode as Element).getAttribute("locationURI") shouldBe "popup:#AbstractTextEditorContext"
    val siblings = menu.children("command")
    siblings[siblings.indexOf(item) - 1].getAttribute("commandId") shouldBe OPEN_QUICK_CHAT_ID
    item.getAttribute("label") shouldBe "Fix with GitLab Duo"
    val visibleWhen = item.children("visibleWhen").single()
    visibleWhen.getAttribute("checkEnabled") shouldBe "false"
    visibleWhen.requiresTrue("duo_chat_enabled")
  }

  it("binds no default key") {
    root.descendants("key").filter { it.getAttribute("commandId") == COMMAND_ID }.shouldBeEmpty()
  }
})
