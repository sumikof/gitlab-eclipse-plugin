package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.ci.joblog.JobLogEditor
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.joblog.JobLogTarget
import com.gitlab.eclipse.views.sidebar.JobNode
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val NODE_URL = "https://gitlab.example.com"
private const val NODE_FP = "fp0"
private const val PROJECT_ID = 42L
private const val JOB_ID = 7L

/**
 * [displayJobLog] headless (Task 10): the selected sidebar node becomes the job to open, with the
 * connection it came from. Polling, its audit lines and their hygiene are [JobLogSession]'s
 * (`JobLogSessionTest`).
 */
class DisplayJobLogHandlerTest : DescribeSpec({
  fun node(projectId: Long? = PROJECT_ID) =
    JobNode(GitLabJob(id = JOB_ID, name = "build", status = "running"), projectId, NODE_URL, NODE_FP)

  class Recorder {
    val notices = mutableListOf<String>()
    val opened = mutableListOf<JobLogTarget>()

    fun run(node: JobNode?) = displayJobLog(node, { notices += it }, { opened += it })
  }

  describe("selection") {
    it("without a selected job it asks for one and opens nothing") {
      val r = Recorder()

      r.run(null)

      r.notices shouldContainExactly listOf(SELECT_JOB_MESSAGE)
      r.opened.shouldBeEmpty()
    }

    it("a job without a project asks to refresh and opens nothing") {
      val r = Recorder()

      r.run(node(projectId = null))

      r.notices shouldContainExactly listOf(NO_PROJECT_MESSAGE)
      r.opened.shouldBeEmpty()
    }

    it("opens the job's log pinned to the connection the node came from") {
      val r = Recorder()

      r.run(node())

      r.notices.shouldBeEmpty()
      val target = r.opened.single()
      target.key shouldBe JobLogKey.of(NODE_URL, NODE_FP, PROJECT_ID, JOB_ID)
      target.projectId shouldBe PROJECT_ID
      target.jobId shouldBe JOB_ID
      target.instanceUrl shouldBe NODE_URL
      target.authFingerprint shouldBe NODE_FP
    }
  }

  // plugin.xml is seen by neither the compiler nor any other test.
  describe("plugin.xml") {
    val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }
    val editorNodes = root.getElementsByTagName("editor")
    val editors = (0 until editorNodes.length).map { editorNodes.item(it) as Element }

    it("registers JobLogEditor once under its ID, never as a default editor and without file associations") {
      val jobLog = editors.filter { it.getAttribute("id") == JobLogEditor.ID }
      jobLog.size shouldBe 1
      val editor = jobLog.single()
      editor.getAttribute("class") shouldBe JobLogEditor::class.java.name
      editor.getAttribute("default") shouldBe "false"
      editor.getAttribute("extensions") shouldBe ""
      editor.getAttribute("filenames") shouldBe ""
    }
  }
})
