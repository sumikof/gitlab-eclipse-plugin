package com.gitlab.eclipse.ci.actions

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.api.TraceFetch
import com.gitlab.eclipse.ci.joblog.JobLogEditor
import com.gitlab.eclipse.ci.joblog.JobLogKey
import com.gitlab.eclipse.ci.joblog.OMITTED_HEADER
import com.gitlab.eclipse.ci.joblog.RenderResult
import com.gitlab.eclipse.ci.joblog.renderInitial
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.eclipse.core.runtime.ILog
import org.w3c.dom.Element
import java.io.IOException
import java.net.http.HttpTimeoutException
import javax.xml.parsers.DocumentBuilderFactory

private const val NODE_URL = "https://gitlab.example.com"
private const val NODE_FP = "fp0"
private const val PROJECT_ID = 42L
private const val JOB_ID = 7L

/**
 * [launchDisplayJobLog] headless (Task 5): pin, fetch, render and classify in the background,
 * with the two UI hops replaced by recorders. Every audit line is built by `jobLogAuditMessage`
 * (no URL) and no throwable ever reaches the [ILog] (design B8).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DisplayJobLogHandlerTest : DescribeSpec({
  val snapshot = ConnectionSnapshot(
    instanceUrl = NODE_URL,
    token = "token-value",
    authFingerprint = NODE_FP,
    configGeneration = 1L,
  )

  class Fixture {
    val apiClient = mockk<GitLabApiClient>()
    val traceService = mockk<JobTraceService>()
    val log = mockk<ILog>(relaxUnitFun = true)
    val lines = mutableListOf<String>()
    val reflected = mutableListOf<RenderResult>()
    val notices = mutableListOf<String>()

    init {
      every { apiClient.captureConnectionIf(any()) } returns snapshot
      every { log.error(capture(lines)) } just Runs
    }

    fun trace(answer: () -> TraceFetch) {
      every { traceService.getTraceSnapshot(PROJECT_ID, JOB_ID, null, snapshot, any()) } answers { answer() }
    }

    fun run() {
      launchDisplayJobLog(
        CoroutineScope(UnconfinedTestDispatcher()),
        log,
        JobLogKey.of(NODE_URL, NODE_FP, PROJECT_ID, JOB_ID),
        myGen = 1L,
        projectId = PROJECT_ID,
        jobId = JOB_ID,
        apiClient = apiClient,
        traceService = traceService,
        nodeInstanceUrl = NODE_URL,
        nodeAuthFingerprint = NODE_FP,
        reflect = { reflected += it },
        notify = { notices += it },
      )
    }

    fun verifyNoThrowableLogged() {
      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
      verify(exactly = 0) { log.warn(any(), any<Throwable>()) }
      verify(exactly = 0) { log.info(any(), any<Throwable>()) }
      lines.forEach { line ->
        line shouldNotContain "gitlab.example.com"
        line shouldNotContain "https://"
        line shouldNotContain "token-value"
      }
    }
  }

  fun body(text: String, omitted: Long = 0) =
    text.toByteArray().let { TraceFetch.Body(it, etag = null, totalBytes = it.size + omitted, omittedBytes = omitted) }

  describe("connection pin") {
    it("a rejected pin audits connectionRejected, notifies and never fetches") {
      val f = Fixture()
      every { f.apiClient.captureConnectionIf(any()) } returns null

      f.run()

      f.lines shouldContainExactly
        listOf("ciReadAction action=displayJobLog projectId=42 jobId=7 outcome=connectionRejected")
      f.notices shouldContainExactly listOf(CONNECTION_CHANGED_MESSAGE)
      f.reflected.shouldBeEmpty()
      verify(exactly = 0) { f.traceService.getTraceSnapshot(any(), any(), any(), any(), any()) }
      f.verifyNoThrowableLogged()
    }
  }

  describe("loaded trace") {
    it("renders the bytes off the UI thread and reflects the result, without an ETag and without logging") {
      val f = Fixture()
      val raw = "\u001B[32mok\u001B[0m\nsection_start:1:build\r\u001B[0Kbuild\nsection_end:2:build\r\u001B[0K\n"
      f.trace { body(raw) }

      f.run()

      f.reflected shouldContainExactly listOf(renderInitial(raw.toByteArray(), 0).first)
      f.notices.shouldBeEmpty()
      f.lines.shouldBeEmpty()
      verify(exactly = 1) { f.traceService.getTraceSnapshot(PROJECT_ID, JOB_ID, null, snapshot, any()) }
    }

    it("an omitted head puts the fixed header on the first line") {
      val f = Fixture()
      f.trace { body("tail line\n", omitted = 1024) }

      f.run()

      f.reflected.single().text shouldStartWith OMITTED_HEADER + "\n"
      f.reflected.single().omitted shouldBe true
    }
  }

  describe("classified failures") {
    it("404 audits the status and correlation id and stays non-committal") {
      val f = Fixture()
      f.trace { throw GitLabApiException(404, "", "req-1") }

      f.run()

      f.lines shouldContainExactly listOf(
        "ciReadAction action=displayJobLog projectId=42 jobId=7 outcome=http httpStatus=404 correlationId=req-1",
      )
      f.notices shouldContainExactly listOf(LOG_NOT_FOUND_MESSAGE)
      f.reflected.shouldBeEmpty()
      f.verifyNoThrowableLogged()
    }

    it("500 shows the generic message") {
      val f = Fixture()
      f.trace { throw GitLabApiException(500, "", null) }

      f.run()

      f.lines shouldContainExactly listOf("ciReadAction action=displayJobLog projectId=42 jobId=7 outcome=http httpStatus=500")
      f.notices shouldContainExactly listOf(GENERIC_LOG_ERROR_MESSAGE)
      f.verifyNoThrowableLogged()
    }

    it("timeout, I/O and an unexpected 304 each get their own outcome and the generic message") {
      listOf(
        "timeout" to { throw HttpTimeoutException("read timed out for $NODE_URL") },
        "io" to { throw IOException("reset by $NODE_URL") },
        "notModified" to { TraceFetch.NotModified },
      ).forEach { (outcome, answer) ->
        val f = Fixture()
        f.trace(answer)

        f.run()

        f.lines shouldContainExactly listOf("ciReadAction action=displayJobLog projectId=42 jobId=7 outcome=$outcome")
        f.notices shouldContainExactly listOf(GENERIC_LOG_ERROR_MESSAGE)
        f.reflected.shouldBeEmpty()
        f.verifyNoThrowableLogged()
      }
    }
  }

  describe("unclassified failures") {
    it("an unexpected exception logs only its class name through the one-argument error") {
      val f = Fixture()
      f.trace { throw IllegalStateException("boom at $NODE_URL/api/v4/projects/42/jobs/7/trace") }

      f.run()

      f.lines shouldContainExactly listOf(
        "ciReadAction action=displayJobLog projectId=42 jobId=7 outcome=unexpected exceptionClass=IllegalStateException",
      )
      f.notices shouldContainExactly listOf(GENERIC_LOG_ERROR_MESSAGE)
      f.verifyNoThrowableLogged()
    }

    it("a cancellation neither logs, notifies nor reflects") {
      val f = Fixture()
      f.trace { throw CancellationException("cancelled") }

      f.run()

      f.lines.shouldBeEmpty()
      f.notices.shouldBeEmpty()
      f.reflected.shouldBeEmpty()
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
