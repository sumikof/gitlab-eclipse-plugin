package com.gitlab.eclipse.views.webview.agentplatform

import com.gitlab.eclipse.chat.commands.SelectChatWebviewCommandHandler
import com.gitlab.eclipse.views.webview.AgenticTabsView
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val USE_CLASSIC = "com.gitlab.eclipse.commands.UseClassicAgentPlatformLayout"
private const val USE_NEW = "com.gitlab.eclipse.commands.UseNewAgentPlatformLayout"
private const val RELOAD = "com.gitlab.eclipse.commands.ReloadAgentPlatformView"
private const val MCP = "gitlab-eclipse-plugin.commands.ShowMcpDashboard"
private const val FLOW_BUILDER = "gitlab-eclipse-plugin.commands.OpenFlowBuilder"
private const val SELECT_CHAT = "gitlab-eclipse-plugin.commands.SelectChatWebview"

/** Checks the plugin.xml wiring of design AGENT-02 §6, which neither the compiler nor any other test sees. */
class AgentPlatformMenuWiringTest : DescribeSpec({

  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

  val declared = root.descendants("extension")
    .filter { it.getAttribute("point") == "org.eclipse.ui.commands" }
    .flatMap { it.children("command") }
    .associateBy { it.getAttribute("id") }

  fun contribution(uri: String): Element =
    root.descendants("menuContribution").single { it.getAttribute("locationURI") == uri }

  fun visibleValue(command: Element): String? {
    val with = command.descendants("with").singleOrNull() ?: return null
    with.getAttribute("variable") shouldBe AgentPlatformLayoutSourceProvider.LAYOUT_KEY
    return with.descendants("equals").single().getAttribute("value")
  }

  describe("commands") {
    it("declares the three new commands with their labels") {
      declared[USE_CLASSIC]?.getAttribute("name") shouldBe "Use Classic Layout"
      declared[USE_NEW]?.getAttribute("name") shouldBe "Use New Layout"
      declared[RELOAD]?.getAttribute("name") shouldBe "Reload Agent Platform View"
    }

    it("binds each new command to exactly its handler class") {
      mapOf(
        USE_CLASSIC to UseClassicAgentPlatformLayoutHandler::class.java,
        USE_NEW to UseNewAgentPlatformLayoutHandler::class.java,
        RELOAD to ReloadAgentPlatformViewHandler::class.java,
      ).forEach { (id, handler) ->
        root.descendants("handler").filter { it.getAttribute("commandId") == id }
          .map { it.getAttribute("class") } shouldContainExactly listOf(handler.name)
        // The workbench instantiates handlers reflectively: a no-arg constructor must exist.
        handler.getConstructor() shouldNotBe null
      }
    }
  }

  describe("source provider") {
    it("registers the layout variable at workbench priority") {
      val provider = root.descendants("sourceProvider")
        .single { it.getAttribute("provider") == AgentPlatformLayoutSourceProvider::class.java.name }
      val variable = provider.descendants("variable").single()
      variable.getAttribute("name") shouldBe AgentPlatformLayoutSourceProvider.LAYOUT_KEY
      variable.getAttribute("priorityLevel") shouldBe "workbench"
      AgentPlatformLayoutSourceProvider::class.java.getConstructor() shouldNotBe null
    }
  }

  describe("view toolbar") {
    it("offers only the toggle away from the current layout") {
      val commands = contribution("toolbar:${AgenticTabsView.VIEW_ID}").descendants("command")
      commands.map { it.getAttribute("commandId") to visibleValue(it) } shouldContainExactly listOf(
        USE_CLASSIC to "new",
        USE_NEW to "classic",
      )
    }
  }

  describe("view pulldown") {
    val menu = contribution("menu:${AgenticTabsView.VIEW_ID}")
    val commands = menu.descendants("command")

    it("lists navigation, a separator, the toggles and Reload, in that order") {
      commands.map { it.getAttribute("commandId") to it.getAttribute("label") } shouldContainExactly listOf(
        MCP to "Open MCP Dashboard",
        FLOW_BUILDER to "Open Flow Builder",
        SELECT_CHAT to "Open Agentic Chat",
        USE_CLASSIC to "Use Classic Layout",
        USE_NEW to "Use New Layout",
        RELOAD to "Reload",
      )
      val elements = menu.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) } }
        .filterIsInstance<Element>().map { it.tagName }
      elements.indexOf("separator") shouldBe 3
      menu.children("separator") shouldHaveSize 1
    }

    it("gates the toggles on the layout and leaves the rest ungated") {
      commands.map { visibleValue(it) } shouldContainExactly listOf(null, null, null, "new", "classic", null)
    }

    it("opens the agentic Duo Chat through the selector's parameter") {
      val parameter = commands.single { it.getAttribute("commandId") == SELECT_CHAT }.descendants("parameter").single()
      parameter.getAttribute("name") shouldBe SelectChatWebviewCommandHandler.WEBVIEW_ID_PARAMETER
      parameter.getAttribute("value") shouldBe "agentic-duo-chat"
    }

    it("references only declared commands, with a declared parameter") {
      (contribution("toolbar:${AgenticTabsView.VIEW_ID}").descendants("command") + commands).forEach { command ->
        val declaration = declared[command.getAttribute("commandId")]
        declaration shouldNotBe null
        command.descendants("parameter").forEach { parameter ->
          declaration!!.children("commandParameter").map { it.getAttribute("id") } shouldContainExactly
            listOf(parameter.getAttribute("name"))
        }
      }
    }
  }
})
