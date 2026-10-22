package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.model.GitLabBridge
import com.gitlab.eclipse.api.model.GitLabJob
import com.gitlab.eclipse.api.model.GitLabPipeline
import com.gitlab.eclipse.views.sidebar.BridgeNode
import com.gitlab.eclipse.views.sidebar.DownstreamPipelineNode
import com.gitlab.eclipse.views.sidebar.JobNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private const val SRC_URL = "https://gitlab.example.com"
private const val SRC_FP = "fp0"
private const val BRIDGE_NODE = "com.gitlab.eclipse.views.sidebar.BridgeNode"
private const val JOB_NODE = "com.gitlab.eclipse.views.sidebar.JobNode"

/**
 * CI-06 §4.6 / R1-7: [JobActionHandler] writes to a bridge for retry and play only — addressed by
 * the bridge id on the job endpoints, which GitLab resolves for bridges too — and the sidebar menus
 * offer a bridge exactly those two commands (plugin.xml, which neither the compiler nor any other
 * test sees).
 */
class JobActionHandlerTest : StringSpec({
  val jobNode = JobNode(GitLabJob(id = 7, name = "compile", status = "failed"), 42L, SRC_URL, SRC_FP)
  val bridgeNode =
    BridgeNode(GitLabBridge(id = 9, name = "trigger", status = "failed"), 99L, "https://other.example", "fp9")

  "a job node is a write target for every job action" {
    listOf("retry", "cancel", "play").forEach { action ->
      jobWriteTargetOf(jobNode, action) shouldBe JobWriteTarget(7L, 42L, SRC_URL, SRC_FP)
    }
  }

  "a bridge node is a write target for retry and play, addressed by the bridge id and its own tags" {
    listOf("retry", "play").forEach { action ->
      jobWriteTargetOf(bridgeNode, action) shouldBe JobWriteTarget(9L, 99L, "https://other.example", "fp9")
    }
  }

  "a bridge node is never a cancel target (GitLab answers 404)" {
    jobWriteTargetOf(bridgeNode, "cancel").shouldBeNull()
  }

  "other selections are no target" {
    val downstream = DownstreamPipelineNode(
      "docs",
      GitLabPipeline(id = 205, status = "failed", projectId = 42L),
      1,
      setOf(100L),
      SRC_URL,
      SRC_FP,
    )
    listOf("retry", "cancel", "play").forEach { action ->
      jobWriteTargetOf(downstream, action).shouldBeNull()
      jobWriteTargetOf(null, action).shouldBeNull()
    }
  }

  "a job and a bridge write are serialized in the same \"job\" key space, by id and normalized instance" {
    jobWriteKeyOf(jobWriteTargetOf(jobNode, "retry")!!) shouldBe WriteKey(SRC_URL, "job", 7L)
    val bridgeTarget = jobWriteTargetOf(bridgeNode, "play")!!
    jobWriteKeyOf(bridgeTarget) shouldBe WriteKey(normalizeInstanceUrl("https://other.example"), "job", 9L)
    jobWriteKeyOf(bridgeTarget.copy(sourceInstanceUrl = "https://other.example/")) shouldBe
      WriteKey("https://other.example", "job", 9L)
  }

  val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
  }

  fun Element.descendants(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
  }

  /** The `instanceof` types each sidebar menu entry of [commandId] is visible for. */
  fun visibleTypes(commandId: String): Set<String> =
    root.descendants("menuContribution")
      .flatMap { it.descendants("command") }
      .filter { it.getAttribute("commandId") == commandId }
      .flatMap { it.descendants("instanceof") }
      .map { it.getAttribute("value") }
      .toSet()

  "plugin.xml: Retry Job and Play Job are offered on jobs and bridges" {
    visibleTypes("com.gitlab.eclipse.commands.RetryJob") shouldBe setOf(JOB_NODE, BRIDGE_NODE)
    visibleTypes("com.gitlab.eclipse.commands.PlayJob") shouldBe setOf(JOB_NODE, BRIDGE_NODE)
  }

  "plugin.xml: cancel, log and artifacts stay job-only" {
    listOf(
      "com.gitlab.eclipse.commands.CancelJob",
      "com.gitlab.eclipse.commands.DisplayJobLog",
      "com.gitlab.eclipse.commands.DownloadArtifacts",
    ).forEach { visibleTypes(it) shouldBe setOf(JOB_NODE) }
  }

  fun Element.elementChildren(): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()

  /** The `<and>` under `<iterate>` of every sidebar menu entry of [commandId] (at least one). */
  fun visibleWhenAnds(commandId: String): List<Element> {
    val commands = root.descendants("menuContribution")
      .flatMap { it.descendants("command") }
      .filter { it.getAttribute("commandId") == commandId }
    commands.isEmpty() shouldBe false
    return commands.map { command ->
      val iterate = command.descendants("iterate").single()
      iterate.elementChildren().map { it.tagName } shouldBe listOf("and")
      iterate.elementChildren().single()
    }
  }

  "plugin.xml: Retry Job / Play Job are visible for (JobNode or BridgeNode) and their own property" {
    mapOf(
      "com.gitlab.eclipse.commands.RetryJob" to "com.gitlab.eclipse.node.canRetry",
      "com.gitlab.eclipse.commands.PlayJob" to "com.gitlab.eclipse.node.canPlay",
    ).forEach { (commandId, property) ->
      visibleWhenAnds(commandId).forEach { and ->
        val (or, test) = and.elementChildren()
        and.elementChildren().map { it.tagName } shouldBe listOf("or", "test")
        or.elementChildren().map { it.tagName to it.getAttribute("value") } shouldBe
          listOf("instanceof" to JOB_NODE, "instanceof" to BRIDGE_NODE)
        test.getAttribute("property") shouldBe property
      }
    }
  }

  "plugin.xml: Cancel Job is visible for JobNode and canCancel only" {
    visibleWhenAnds("com.gitlab.eclipse.commands.CancelJob").forEach { and ->
      and.elementChildren().map { it.tagName to (it.getAttribute("value") + it.getAttribute("property")) } shouldBe
        listOf("instanceof" to JOB_NODE, "test" to "com.gitlab.eclipse.node.canCancel")
    }
  }

  "plugin.xml: no menu entry targets a downstream node" {
    val all = root.descendants("instanceof").map { it.getAttribute("value") }
    all.none { it.contains("Downstream") } shouldBe true
  }
})
