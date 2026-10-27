package com.gitlab.eclipse

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val CATEGORY_ID = "gitlab-eclipse-plugin"

/** The handler commands that shipped without a declaration, so the Keys preferences never listed them (§7 O6). */
private val PREVIOUSLY_UNDECLARED = mapOf(
  "gitlab-eclipse-plugin.commands.ExplainCode" to "Explain Code",
  "gitlab-eclipse-plugin.commands.FixCode" to "Fix Code",
  "gitlab-eclipse-plugin.commands.GenerateTests" to "Generate Tests",
  "gitlab-eclipse-plugin.commands.RefactorCode" to "Refactor Code",
  "gitlab-eclipse-plugin.commands.showSettings" to "Show Settings",
  "gitlab-eclipse-plugin.commands.showDocumentation" to "Show Documentation",
  "gitlab-eclipse-plugin.commands.showPluginStatus" to "Show Plugin Status",
  "gitlab-eclipse-plugin.commands.chatStatus" to "Duo Chat Status",
  "gitlab-eclipse-plugin.commands.codeSuggestionsStatus" to "Code Suggestions Status",
  "gitlab-eclipse-plugin.commands.toggleCodeSuggestionsForLanguage" to "Toggle Code Suggestions for Language",
)

/** Checks that every command plugin.xml refers to is declared, which neither the compiler nor the workbench reports. */
class PluginCommandDeclarationWiringTest : DescribeSpec({

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

  // Only <command> elements directly under the commands extension declare a command; the <command> elements in
  // menuContributions also carry an id, and counting those would hide a missing declaration.
  val declarations: List<Element> = root.children("extension")
    .filter { it.getAttribute("point") == "org.eclipse.ui.commands" }
    .flatMap { it.children("command") }

  it("declares each command id once") {
    declarations.map { it.getAttribute("id") }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
      .shouldBeEmpty()
  }

  it("declares every command a handler, menu item or key binding refers to") {
    val declared = declarations.map { it.getAttribute("id") }.toSet()
    root.descendants("*").map { it.getAttribute("commandId") }.filter { it.isNotEmpty() }.toSet()
      .filterNot { it in declared }
      .shouldBeEmpty()
  }

  it("declares the previously undeclared handler commands in the GitLab Duo category") {
    declarations.filter { it.getAttribute("id") in PREVIOUSLY_UNDECLARED }
      .associate { it.getAttribute("id") to (it.getAttribute("name") to it.getAttribute("categoryId")) } shouldBe
      PREVIOUSLY_UNDECLARED.mapValues { (_, name) -> name to CATEGORY_ID }
  }

  it("files every declared command under a declared category") {
    val categories = root.children("extension")
      .filter { it.getAttribute("point") == "org.eclipse.ui.commands" }
      .flatMap { it.children("category") }
    categories.filter { it.getAttribute("id") == CATEGORY_ID } shouldHaveSize 1
    val categoryIds = categories.map { it.getAttribute("id") }.toSet()
    declarations.filter { it.getAttribute("categoryId") !in categoryIds }.map { it.getAttribute("id") }
      .shouldBeEmpty()
  }

  it("binds no default key to the previously undeclared commands") {
    root.descendants("key").filter { it.getAttribute("commandId") in PREVIOUSLY_UNDECLARED }.shouldBeEmpty()
  }
})
