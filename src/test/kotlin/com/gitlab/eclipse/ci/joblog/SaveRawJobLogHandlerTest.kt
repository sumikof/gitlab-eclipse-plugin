package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.JobTraceService
import com.gitlab.eclipse.ci.actions.CONNECTION_CHANGED_MESSAGE
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.core.runtime.ILog
import org.w3c.dom.Element
import java.io.IOException
import java.io.InputStream
import java.net.http.HttpTimeoutException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

// Test streams that never end stop here, so a broken cancellation fails at once instead of filling the disk.
private const val ENDLESS_CAP_BYTES = 64L * 1024 * 1024

private const val NODE_URL = "https://gitlab.example.com"
private const val NODE_FP = "fp0"
private const val PROJECT_ID = 42L
private const val JOB_ID = 7L
private const val SECRET_DIR = "/home/someone/private-dir"

/**
 * [SaveRawJobLogHandler.run] headless (Task 6): the dialog, pin, download, notification and
 * background launch are all constructor seams. Neither the chosen path nor a throwable may reach
 * a notification or the [ILog] (design B8).
 */
class SaveRawJobLogHandlerTest : DescribeSpec({
  val snapshot = ConnectionSnapshot(
    instanceUrl = NODE_URL,
    token = "token-value",
    authFingerprint = NODE_FP,
    configGeneration = 1L,
  )
  val target: Path = Path.of(SECRET_DIR, "job-7.log")

  class Fixture(chosen: Path?) {
    val log = mockk<ILog>(relaxUnitFun = true)
    val lines = mutableListOf<String>()
    val notices = mutableListOf<String>()
    val suggested = mutableListOf<String>()
    val pins = mutableListOf<Pair<String, String>>()
    val downloads = mutableListOf<List<Any>>()
    var launches = 0
    val launchKeys = mutableListOf<JobLogKey>()
    var pinResult: ConnectionSnapshot? = null
    var failure: Exception? = null

    val handler = SaveRawJobLogHandler(
      chooseTarget = { name ->
        suggested += name
        chosen
      },
      pin = { url, fingerprint ->
        pins += url to fingerprint
        pinResult
      },
      download = { projectId, jobId, connection, path ->
        downloads += listOf(projectId, jobId, connection, path)
        failure?.let { throw it }
      },
      notify = { notices += it },
      background = { key, block ->
        launches++
        launchKeys += key
        runBlocking { block() }
      },
      log = log,
    )

    init {
      every { log.error(capture(lines)) } just Runs
    }

    fun run() {
      val key = JobLogKey.of(NODE_URL, NODE_FP, PROJECT_ID, JOB_ID)
      handler.run(JobLogEditorInput(key, JobLogContent(""), NODE_URL, NODE_FP))
    }

    fun verifyNothingLeaked() {
      verify(exactly = 0) { log.error(any(), any<Throwable>()) }
      verify(exactly = 0) { log.warn(any(), any<Throwable>()) }
      verify(exactly = 0) { log.info(any(), any<Throwable>()) }
      (lines + notices).forEach { text ->
        text shouldNotContain SECRET_DIR
        text shouldNotContain "job-7.log"
        text shouldNotContain "gitlab.example.com"
        text shouldNotContain "token-value"
      }
    }
  }

  describe("dialog") {
    it("a cancelled dialog does nothing: no pin, no download, no notice, no log") {
      val f = Fixture(chosen = null)

      f.run()

      f.suggested shouldContainExactly listOf("job-7.log")
      f.launches shouldBe 0
      f.pins.shouldBeEmpty()
      f.downloads.shouldBeEmpty()
      f.notices.shouldBeEmpty()
      f.lines.shouldBeEmpty()
    }

    it("without a job log input the dialog is not even opened") {
      val f = Fixture(chosen = target)

      f.handler.run(null)

      f.suggested.shouldBeEmpty()
      f.launches shouldBe 0
      f.notices.shouldBeEmpty()
    }
  }

  describe("connection pin") {
    it("a rejected pin audits connectionRejected, notifies and never downloads") {
      val f = Fixture(chosen = target)

      f.run()

      f.pins shouldContainExactly listOf(NODE_URL to NODE_FP)
      f.downloads.shouldBeEmpty()
      f.lines shouldContainExactly
        listOf("ciReadAction action=saveRawJobLog projectId=42 jobId=7 outcome=connectionRejected")
      f.notices shouldContainExactly listOf(CONNECTION_CHANGED_MESSAGE)
      f.verifyNothingLeaked()
    }
  }

  describe("download") {
    it("saves to the chosen path over the pinned connection, in the background, and says so") {
      val f = Fixture(chosen = target)
      f.pinResult = snapshot

      f.run()

      f.launches shouldBe 1
      f.launchKeys shouldContainExactly listOf(JobLogKey.of(NODE_URL, NODE_FP, PROJECT_ID, JOB_ID))
      f.downloads shouldContainExactly listOf(listOf(PROJECT_ID, JOB_ID, snapshot, target))
      f.notices shouldContainExactly listOf(RAW_LOG_SAVED_MESSAGE)
      f.lines.shouldBeEmpty()
      f.verifyNothingLeaked()
    }

    listOf(
      GitLabApiException(403, "body", "cid-1") to
        "outcome=http httpStatus=403 correlationId=cid-1 exceptionClass=GitLabApiException",
      HttpTimeoutException("timed out") to "outcome=timeout exceptionClass=HttpTimeoutException",
      AccessDeniedException("$SECRET_DIR/job-7.log") to "outcome=io exceptionClass=AccessDeniedException",
      IllegalStateException("$SECRET_DIR/job-7.log") to "outcome=unexpected exceptionClass=IllegalStateException",
    ).forEach { (error, tail) ->
      it("a ${error.javaClass.simpleName} audits '$tail' and notifies the failure without the path") {
        val f = Fixture(chosen = target)
        f.pinResult = snapshot
        f.failure = error

        f.run()

        f.lines shouldContainExactly listOf("ciReadAction action=saveRawJobLog projectId=42 jobId=7 $tail")
        f.notices shouldContainExactly listOf(RAW_LOG_SAVE_FAILED_MESSAGE)
        f.verifyNothingLeaked()
      }
    }

    it("cancellation propagates without a notice or an audit line") {
      val f = Fixture(chosen = target)
      f.pinResult = snapshot
      f.failure = CancellationException("stopped")

      shouldThrow<CancellationException> { f.run() }

      f.notices.shouldBeEmpty()
      f.lines.shouldBeEmpty()
      f.verifyNothingLeaked()
    }
  }

  // Review M-4: the default download seam stops when the shared scope cancels the coroutine.
  describe("downloadRawTraceCancellably") {
    it("stops at the next chunk once the coroutine is cancelled and leaves no file behind") {
      val own = tempdir().toPath()
      val apiClient = mockk<GitLabApiClient>()
      var reads = 0
      lateinit var job: Job
      val endless = object : InputStream() {
        private var total = 0L

        override fun read(): Int = 0.also { count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          reads++
          if (reads == 3) job.cancel()
          count(len)
          return len
        }

        private fun count(n: Int) {
          total += n
          check(total <= ENDLESS_CAP_BYTES) { "endless test stream passed 64 MiB: cancellation did not stop the copy" }
        }
      }
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(InputStream) -> Unit>(3).invoke(endless)
      }

      runBlocking {
        // Not started until join, so job is assigned before the first read.
        job = launch {
          downloadRawTraceCancellably(JobTraceService(apiClient), PROJECT_ID, JOB_ID, snapshot, own.resolve("x.log"))
        }
        withTimeout(5_000) { job.join() }
      }

      job.isCancelled shouldBe true
      reads shouldBe 3
      Files.list(own).use { it.count() } shouldBe 0L
    }

    // B1 review N-5: without the close hook this read would block until the 10 minute deadline.
    it("ends a read blocked on a stalled connection as soon as the coroutine is cancelled") {
      val own = tempdir().toPath()
      val apiClient = mockk<GitLabApiClient>()
      val reading = CountDownLatch(1)
      val closed = CountDownLatch(1)
      val stalled = object : InputStream() {
        override fun read(): Int = -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          reading.countDown()
          // Bounded: a broken close hook fails the test instead of hanging it.
          check(closed.await(5, TimeUnit.SECONDS)) { "the body was never closed" }
          throw IOException("Stream closed")
        }

        override fun close() {
          closed.countDown()
        }
      }
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(() -> Unit) -> Unit>(2).invoke { stalled.close() }
        arg<(InputStream) -> Unit>(3).invoke(stalled)
      }

      lateinit var job: Job
      runBlocking {
        job = launch {
          downloadRawTraceCancellably(JobTraceService(apiClient), PROJECT_ID, JOB_ID, snapshot, own.resolve("x.log"))
        }
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { waiter ->
          withContext(waiter) { reading.await(5, TimeUnit.SECONDS) } shouldBe true
        }
        job.cancel()
        withTimeout(5_000) { job.join() }
      }

      job.isCancelled shouldBe true
      closed.count shouldBe 0L
      Files.list(own).use { it.count() } shouldBe 0L
    }
  }

  // B2 review I-2: a save outlives the job log's editors, not the plugin.
  describe("lifetime with the session registry") {
    fun handlerOn(
      w: FakeWorld,
      saveTo: Path,
      notices: MutableList<String>,
      download: suspend (Long, Long, ConnectionSnapshot, Path) -> Unit,
    ) = SaveRawJobLogHandler(
      chooseTarget = { saveTo },
      pin = { _, _ -> snapshot },
      download = download,
      notify = { notices += it },
      background = w.registry::launchSave,
      log = mockk(relaxUnitFun = true),
    )

    fun input() = JobLogEditorInput(FAKE_KEY, JobLogContent(""), FAKE_URL, FAKE_FP)

    it("closing the last tab lets a running save finish and say so") {
      val w = FakeWorld()
      val view = FakeView()
      w.open(view)
      val finish = CompletableDeferred<Unit>()
      val notices = mutableListOf<String>()
      handlerOn(w, target, notices) { _, _, _, _ -> finish.await() }.run(input())

      w.registry.detach(FAKE_KEY, view)

      w.session shouldBe null
      notices.shouldBeEmpty()
      finish.complete(Unit)
      notices shouldContainExactly listOf(RAW_LOG_SAVED_MESSAGE)
    }

    it("a save started after the plugin stopped and started again runs to the end and says so (r2 N-1)") {
      val w = FakeWorld()
      w.open(FakeView())
      w.registry.disposeAll()
      w.open(FakeView())
      val finish = CompletableDeferred<Unit>()
      val notices = mutableListOf<String>()
      handlerOn(w, target, notices) { _, _, _, _ -> finish.await() }.run(input())
      val save = w.env.launched.last()

      save.isActive shouldBe true
      finish.complete(Unit)

      notices shouldContainExactly listOf(RAW_LOG_SAVED_MESSAGE)
      save.isCompleted shouldBe true
      save.isCancelled shouldBe false
    }

    it("stopping the plugin ends a stalled save at once, says nothing and leaves no file") {
      val own = tempdir().toPath()
      val apiClient = mockk<GitLabApiClient>()
      val reading = CountDownLatch(1)
      val closed = CountDownLatch(1)
      val stalled = object : InputStream() {
        override fun read(): Int = -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          reading.countDown()
          check(closed.await(5, TimeUnit.SECONDS)) { "the body was never closed" }
          throw IOException("Stream closed")
        }

        override fun close() {
          closed.countDown()
        }
      }
      every { apiClient.downloadTo(any(), any(), any(), any()) } answers {
        arg<(() -> Unit) -> Unit>(2).invoke { stalled.close() }
        arg<(InputStream) -> Unit>(3).invoke(stalled)
      }
      val w = FakeWorld()
      w.open(FakeView())
      val notices = mutableListOf<String>()
      handlerOn(w, own.resolve("x.log"), notices) { projectId, jobId, connection, path ->
        downloadRawTraceCancellably(JobTraceService(apiClient), projectId, jobId, connection, path)
      }.run(input())
      reading.await(5, TimeUnit.SECONDS) shouldBe true
      val save = w.env.launched.last()

      w.registry.disposeAll()
      runBlocking { withTimeout(5_000) { save.join() } }

      save.isCancelled shouldBe true
      closed.count shouldBe 0L
      notices.shouldBeEmpty()
      Files.list(own).use { it.count() } shouldBe 0L
    }
  }

  // plugin.xml is seen by neither the compiler nor any other test.
  describe("plugin.xml") {
    val root: Element = javaClass.classLoader.getResourceAsStream("plugin.xml")!!.use {
      DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it).documentElement
    }

    fun elements(parent: Element, tag: String): List<Element> =
      parent.getElementsByTagName(tag).let { list -> (0 until list.length).map { list.item(it) as Element } }

    fun onlyInJobLogEditor(element: Element): Boolean =
      elements(element, "with").any { with ->
        with.getAttribute("variable") == "activeEditorId" &&
          elements(with, "equals").any { it.getAttribute("value") == JobLogEditor.ID }
      }

    val commands = mapOf(
      "com.gitlab.eclipse.commands.SaveRawJobLog" to SaveRawJobLogHandler::class.java.name,
      "com.gitlab.eclipse.commands.ScrollJobLogToBottom" to ScrollJobLogToBottomHandler::class.java.name,
    )

    it("declares both commands once, each with one handler active only in the job log editor") {
      commands.forEach { (id, handlerClass) ->
        elements(root, "command").count { it.getAttribute("id") == id } shouldBe 1
        val handlers = elements(root, "handler").filter { it.getAttribute("commandId") == id }
        handlers.map { it.getAttribute("class") } shouldContainExactly listOf(handlerClass)
        onlyInJobLogEditor(handlers.single()) shouldBe true
      }
    }

    it("shows both commands in the text editor context menu and the main toolbar, only for the job log editor") {
      val contributions = elements(root, "menuContribution")
      listOf("popup:#TextEditorContext", "toolbar:org.eclipse.ui.main.toolbar").forEach { location ->
        val items = contributions
          .filter { it.getAttribute("locationURI").startsWith(location) }
          .flatMap { elements(it, "command") }
          .filter { it.getAttribute("commandId") in commands }
        items.map { it.getAttribute("commandId") } shouldContainExactly commands.keys.toList()
        items.forEach { onlyInJobLogEditor(it) shouldBe true }
      }
    }
  }
})
