package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.views.sidebar.ChangeType
import com.gitlab.eclipse.views.sidebar.ChangedFileNode
import com.gitlab.eclipse.views.sidebar.MergeRequestNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.eclipse.jface.viewers.StructuredSelection
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val COMMAND_ID = "com.gitlab.eclipse.commands.OpenMrFileDiff"

class OpenMrFileDiffHandlerTest : DescribeSpec({
  val node = ChangedFileNode("a.kt", "a.kt", ChangeType.MODIFIED, diffHeadSha = null)

  describe("changedFileIn") {
    it("takes the first selected changed file") {
      changedFileIn(StructuredSelection(node)) shouldBe node
    }

    it("is null for another node, an empty selection or no selection") {
      changedFileIn(StructuredSelection(mockk<MergeRequestNode>())).shouldBeNull()
      changedFileIn(StructuredSelection.EMPTY).shouldBeNull()
      changedFileIn(null).shouldBeNull()
    }
  }

  describe("handle") {
    it("opens the diff of the selected changed file") {
      val opened = mutableListOf<ChangedFileNode>()
      val shown = mutableListOf<String>()

      OpenMrFileDiffHandler(open = { opened += it }, show = { shown += it }).handle(StructuredSelection(node))

      opened shouldContainExactly listOf(node)
      shown.shouldBeEmpty()
    }

    it("explains when nothing suitable is selected") {
      val opened = mutableListOf<ChangedFileNode>()
      val shown = mutableListOf<String>()

      OpenMrFileDiffHandler(open = { opened += it }, show = { shown += it }).handle(StructuredSelection.EMPTY)

      opened.shouldBeEmpty()
      shown shouldContainExactly listOf(OpenMrFileDiffHandler.SELECT_FILE_MESSAGE)
    }
  }

  describe("plugin.xml") {
    val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }

    fun Element.descendants(tag: String): List<Element> {
      val nodes = getElementsByTagName(tag)
      return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    it("declares the Open Diff command and binds this handler") {
      root.descendants("command").filter { it.getAttribute("id") == COMMAND_ID }
        .map { it.getAttribute("name") } shouldBe listOf("Open Diff")
      root.descendants("handler").filter { it.getAttribute("commandId") == COMMAND_ID }
        .map { it.getAttribute("class") } shouldBe listOf(OpenMrFileDiffHandler::class.java.name)
    }

    it("offers it in the sidebar menu for changed files only") {
      val entries = root.descendants("menuContribution").flatMap { contribution ->
        contribution.descendants("command").filter { it.getAttribute("commandId") == COMMAND_ID }
          .map { contribution.getAttribute("locationURI") to it }
      }

      entries.map { it.first } shouldBe listOf("popup:com.gitlab.eclipse.views.GitLabSidebarView")
      entries.single().second.descendants("instanceof").map { it.getAttribute("value") } shouldBe
        listOf(ChangedFileNode::class.java.name)
    }
  }
})
