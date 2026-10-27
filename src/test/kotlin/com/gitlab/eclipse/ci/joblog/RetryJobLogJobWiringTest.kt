package com.gitlab.eclipse.ci.joblog

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val COMMAND = "com.gitlab.eclipse.commands.RetryJobLogJob"
private const val JOB_LOG_EDITOR = "com.gitlab.eclipse.editors.JobLog"

/** L4's log view command in plugin.xml, which neither the compiler nor the workbench checks. */
class RetryJobLogJobWiringTest : StringSpec({
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

  fun handlers(commandId: String): List<Element> =
    extensions("org.eclipse.ui.handlers").flatMap { it.elementChildren() }
      .filter { it.getAttribute("commandId") == commandId }

  /** The job-log-editor condition (`with activeEditorId equals JobLog`) under [element]. */
  fun Element.requiresJobLogEditor(): Boolean {
    val with = descendants("with").singleOrNull() ?: return false
    return with.getAttribute("variable") == "activeEditorId" &&
      with.elementChildren().map { it.tagName to it.getAttribute("value") } == listOf("equals" to JOB_LOG_EDITOR)
  }

  "the command is declared apart from the sidebar's Retry Job, with its own Keys name" {
    val command = extensions("org.eclipse.ui.commands").flatMap { it.elementChildren() }
      .single { it.tagName == "command" && it.getAttribute("id") == COMMAND }
    command.getAttribute("name") shouldBe "Retry Job from Log"
    command.getAttribute("description") shouldBe "Retry the job shown in the job log"
    command.getAttribute("categoryId") shouldBe "gitlab-eclipse-plugin"
  }

  "one handler runs it while a job log editor is active, and decides its enablement itself" {
    val handler = handlers(COMMAND).single()
    handler.getAttribute("class") shouldBe "com.gitlab.eclipse.ci.joblog.RetryJobFromLogHandler"
    handler.elementChildren().map { it.tagName } shouldBe listOf("activeWhen")
    handler.requiresJobLogEditor() shouldBe true
  }

  "the sidebar's Retry Job keeps its single handler" {
    handlers("com.gitlab.eclipse.commands.RetryJob").map { it.getAttribute("class") } shouldBe
      listOf("com.gitlab.eclipse.ci.actions.JobActionHandler")
  }

  "no property tester is declared for the job log" {
    root.descendants("propertyTester").filter { it.getAttribute("namespace").contains("jobLog", ignoreCase = true) }
      .shouldBeEmpty()
  }

  "the job log's context menu and toolbar offer it, shown for the job log and greyed out when disabled" {
    val entries = root.descendants("menuContribution").flatMap { contribution ->
      contribution.descendants("command").filter { it.getAttribute("commandId") == COMMAND }.map { contribution to it }
    }
    entries.map { (contribution, _) -> contribution.getAttribute("locationURI") } shouldBe listOf(
      "popup:#TextEditorContext?after=additions",
      "toolbar:org.eclipse.ui.main.toolbar?after=additions",
    )
    entries.forEach { (_, command) ->
      command.getAttribute("label") shouldBe "Retry Job from Log"
      val visibleWhen = command.descendants("visibleWhen").single()
      visibleWhen.getAttribute("checkEnabled") shouldBe "false"
      visibleWhen.requiresJobLogEditor() shouldBe true
    }
    val toolbarCommand = entries.last().second
    (toolbarCommand.parentNode as Element).getAttribute("id") shouldBe "com.gitlab.eclipse.toolbar.jobLog"
    toolbarCommand.getAttribute("mode") shouldBe "FORCE_TEXT"
  }
})
