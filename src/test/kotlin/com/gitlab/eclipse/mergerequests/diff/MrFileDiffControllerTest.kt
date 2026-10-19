package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import com.gitlab.eclipse.views.sidebar.ChangeType
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private val BASE = "a".repeat(40)
private val HEAD_1 = "b".repeat(40)
private val HEAD_2 = "c".repeat(40)

private val SNAPSHOT = ConnectionSnapshot("https://gl.example", "tok", "fp", 1L)

private fun request(path: String = "src/A.kt", head: String = HEAD_1) = MrFileDiffRequest(
  instanceUrl = "https://gl.example",
  authFingerprint = "fp",
  projectId = 42L,
  mrIid = 7L,
  namespaceWithPath = "g/p",
  baseSha = BASE,
  headSha = head,
  oldPath = path,
  newPath = path,
  changeType = ChangeType.MODIFIED,
  aMode = "100644",
  bMode = "100644",
)

private fun ok(request: MrFileDiffRequest) = MrFileDiffRequest.Validated.Ok(request)

/**
 * Synchronous stand-in for Jobs and the UI thread. Scheduled work and UI hops queue up and run only
 * when the test says so, under a thread tag the loader's collaborators record.
 */
private class FakeWorld(var existingEditors: Set<MrFileDiffKey> = emptySet()) : MrDiffPorts {
  var tag = "ui"
  val loaderTags = mutableListOf<String>()
  val opened = mutableListOf<Pair<MrFileDiffContent, String>>()
  val notified = mutableListOf<String>()
  val findOpenCalls = mutableListOf<MrFileDiffKey>()

  inner class PendingJob(
    val name: String,
    val work: (() -> Boolean) -> Unit,
    val onEndedWithoutRunning: () -> Unit,
  ) {
    var cancelled = false
    var ran = false
    var onCancelled: () -> Unit = {}
  }

  val jobs = mutableListOf<PendingJob>()
  private val uiQueue = ArrayDeque<() -> Unit>()

  override fun findOpen(key: MrFileDiffKey): Boolean {
    findOpenCalls += key
    return key in existingEditors
  }

  override fun schedule(
    name: String,
    work: (isCancelled: () -> Boolean) -> Unit,
    onCancelHandle: (() -> Unit) -> Unit,
    onEndedWithoutRunning: () -> Unit,
  ) {
    val job = PendingJob(name, work, onEndedWithoutRunning)
    jobs += job
    onCancelHandle {
      job.cancelled = true
      job.onCancelled()
    }
  }

  override fun uiHop(block: () -> Unit) {
    uiQueue.addLast(block)
  }

  override fun open(content: MrFileDiffContent) {
    opened += content to tag
  }

  override fun notify(message: String) {
    notified += message
  }

  /** Runs [job] on the "background" tag; whatever escapes the work is recorded, as a Job would swallow it. */
  fun run(job: PendingJob) {
    tag = "background"
    job.ran = true
    try {
      job.work { job.cancelled }
    } catch (e: Throwable) {
      escaped += e
    } finally {
      tag = "ui"
    }
  }

  val escaped = mutableListOf<Throwable>()

  /** The job manager ends [job] without ever running it (cancelled while waiting). */
  fun endWithoutRunning(job: PendingJob) {
    job.ran = true
    job.onEndedWithoutRunning()
  }

  fun drainUi() {
    while (uiQueue.isNotEmpty()) uiQueue.removeFirst()()
  }

  fun runAll() {
    jobs.filterNot { it.ran }.forEach(::run)
    drainUi()
  }
}

class MrFileDiffControllerTest : DescribeSpec({
  extension(LoggingKotestExtension)

  lateinit var world: FakeWorld
  var sourceAnswer: (SideRef) -> SideContent = { SideContent.Bytes(it.sha.toByteArray()) }
  var captureAnswer: () -> ConnectionSnapshot = { SNAPSHOT }

  fun controller(): MrFileDiffController {
    val source = MrFileContentSource { _, side, _ ->
      world.loaderTags += world.tag
      sourceAnswer(side)
    }
    val capture = { _: (String) -> Boolean ->
      world.loaderTags += world.tag
      captureAnswer()
    }
    return MrFileDiffController(MrFileDiffLoader(capture, source), world)
  }

  beforeEach {
    MrFileDiffRegistry.resetForTest()
    world = FakeWorld()
    sourceAnswer = { side -> SideContent.Bytes(side.sha.toByteArray()) }
    captureAnswer = { SNAPSHOT }
  }
  afterSpec { MrFileDiffRegistry.resetForTest() }

  describe("opening") {
    it("an existing editor for the key is activated and nothing is loaded") {
      world.existingEditors = setOf(request().key)

      controller().open(ok(request()))

      world.findOpenCalls shouldBe listOf(request().key)
      world.jobs.shouldBeEmpty()
      world.loaderTags.shouldBeEmpty()
    }

    it("a Ready load is opened once, from the UI hop, with the loader run only in the background") {
      val controller = controller()

      controller.open(ok(request()))
      world.loaderTags.shouldBeEmpty()
      world.runAll()

      world.opened shouldHaveSize 1
      world.opened.single().second shouldBe "ui"
      world.opened.single().first.request shouldBe request()
      world.loaderTags.shouldContainOnly("background")
      world.notified.shouldBeEmpty()
    }

    it("the same key twice before completion schedules one job") {
      val controller = controller()

      controller.open(ok(request()))
      controller.open(ok(request()))
      world.runAll()

      world.jobs shouldHaveSize 1
      world.opened shouldHaveSize 1
    }

    it("the same key again after completion loads again (no editor found)") {
      val controller = controller()

      controller.open(ok(request()))
      world.runAll()
      controller.open(ok(request()))
      world.runAll()

      world.jobs shouldHaveSize 2
      world.opened shouldHaveSize 2
    }

    it("different files are independent") {
      val controller = controller()

      controller.open(ok(request(path = "a.kt")))
      controller.open(ok(request(path = "b.kt")))
      world.runAll()

      world.jobs.map { it.cancelled } shouldBe listOf(false, false)
      world.opened.map { it.first.request.newPath } shouldBe listOf("a.kt", "b.kt")
    }
  }

  describe("latest wins per file") {
    it("a new snapshot cancels the older in-flight job and only the new one opens") {
      val controller = controller()

      controller.open(ok(request(head = HEAD_1)))
      controller.open(ok(request(head = HEAD_2)))
      val (oldJob, newJob) = world.jobs

      oldJob.cancelled shouldBe true
      newJob.cancelled shouldBe false
      world.run(newJob)
      world.drainUi()
      world.opened.map { it.first.request.headSha } shouldBe listOf(HEAD_2)
    }

    it("an old result that completes anyway (cancel too late) is discarded") {
      val controller = controller()

      controller.open(ok(request(head = HEAD_1)))
      val oldJob = world.jobs.single()
      world.run(oldJob.also { it.cancelled = false }) // work finished before the cancel took effect
      controller.open(ok(request(head = HEAD_2)))
      world.drainUi()

      world.opened.shouldBeEmpty()
      world.notified.shouldBeEmpty()
      world.runAll()
      world.opened.map { it.first.request.headSha } shouldBe listOf(HEAD_2)
    }
  }

  describe("cancellation and lifecycle") {
    it("a cancelled job opens nothing and notifies nothing") {
      val controller = controller()

      controller.open(ok(request()))
      world.jobs.single().cancelled = true
      world.runAll()

      world.opened.shouldBeEmpty()
      world.notified.shouldBeEmpty()
    }

    it("deactivation during the load opens nothing and notifies nothing") {
      val controller = controller()

      controller.open(ok(request()))
      MrFileDiffRegistry.onDeactivate()
      world.runAll()

      world.opened.shouldBeEmpty()
      world.notified.shouldBeEmpty()
    }

    it("deactivation after the load but before the UI hop opens nothing") {
      val controller = controller()

      controller.open(ok(request()))
      world.run(world.jobs.single())
      MrFileDiffRegistry.onDeactivate()
      world.drainUi()

      world.opened.shouldBeEmpty()
    }

    it("while inactive nothing is scheduled; after reactivation a fresh open works") {
      val controller = controller()
      MrFileDiffRegistry.onDeactivate()

      controller.open(ok(request()))
      world.jobs.shouldBeEmpty()

      MrFileDiffRegistry.onActivate()
      controller.open(ok(request()))
      world.runAll()
      world.opened shouldHaveSize 1
    }

    it("a load from before a stop/start cycle cannot open after reactivation") {
      val controller = controller()

      controller.open(ok(request()))
      MrFileDiffRegistry.onDeactivate()
      MrFileDiffRegistry.onActivate()
      world.runAll()

      world.opened.shouldBeEmpty()
    }
  }

  describe("notifications") {
    DiffFailure.entries.filter { it != DiffFailure.INVALID && it != DiffFailure.SUBMODULE }.forEach { kind ->
      it("a $kind failure notifies exactly once with its message") {
        sourceAnswer = { SideContent.Failed(kind) }
        val controller = controller()

        controller.open(ok(request()))
        world.runAll()

        world.notified shouldBe listOf(MrDiffMessages.message(kind))
        world.opened.shouldBeEmpty()
      }
    }

    it("an invalid request notifies the INVALID message, schedules nothing and logs only the reason code") {
      val log = CapturedLog().also { it.install() }

      controller().open(MrFileDiffRequest.Validated.Invalid(MrFileDiffRequest.InvalidReason.BAD_PATH))

      world.notified shouldBe listOf(MrDiffMessages.message(DiffFailure.INVALID))
      world.jobs.shouldBeEmpty()
      world.findOpenCalls.shouldBeEmpty()
      log.lines.single() shouldContain "reason=BAD_PATH"
    }

    it("the messages match the design wording") {
      MrDiffMessages.message(DiffFailure.TOO_LARGE) shouldBe
        "This file is too large to compare in Eclipse (over 4 MiB). Open the merge request in the browser."
      MrDiffMessages.message(DiffFailure.SUBMODULE) shouldBe "Submodule changes cannot be compared."
      MrDiffMessages.message(DiffFailure.INVALID) shouldBe
        "This changed file cannot be opened as a diff. Refresh the merge request."
      MrDiffMessages.message(DiffFailure.NOT_FOUND_OR_DENIED) shouldBe
        "The file is not available at this revision, or you do not have access. Refresh the merge request."
      DiffFailure.entries.map(MrDiffMessages::message).forEach { it shouldNotContain "null" }
    }
  }

  describe("a load that never delivers still releases its key (R2)") {
    it("a job that ends without running lets the same key be scheduled again") {
      val controller = controller()

      controller.open(ok(request()))
      world.endWithoutRunning(world.jobs.single())
      world.drainUi()
      controller.open(ok(request()))

      world.jobs shouldHaveSize 2
      world.notified.shouldBeEmpty()
      world.runAll()
      world.opened shouldHaveSize 1
    }

    it("a CancellationException thrown by the loader is a silent Cancelled and the key can be opened again") {
      captureAnswer = { throw java.util.concurrent.CancellationException() }
      val controller = controller()

      controller.open(ok(request()))
      world.runAll()
      captureAnswer = { SNAPSHOT }
      controller.open(ok(request()))
      world.runAll()

      world.escaped.shouldBeEmpty()
      world.notified.shouldBeEmpty()
      world.jobs shouldHaveSize 2
      world.opened shouldHaveSize 1
    }
  }

  describe("controller onDeactivate (R6)") {
    it("cancels every in-flight job once and forgets the handles") {
      val controller = controller()
      controller.open(ok(request(path = "a.kt")))
      controller.open(ok(request(path = "b.kt")))
      var cancels = 0
      world.jobs.forEach { job -> job.onCancelled = { cancels++ } }

      controller.onDeactivate()
      controller.onDeactivate()

      cancels shouldBe 2
      world.jobs.map { it.cancelled } shouldBe listOf(true, true)
    }
  }

  describe("a non-current delivery (F6)") {
    it("still releases its generation in finally") {
      val controller = controller()
      controller.open(ok(request(head = HEAD_1)))
      controller.open(ok(request(head = HEAD_2)))
      val (oldJob, _) = world.jobs
      val tracked = MrFileDiffRegistry.trackedForTest()

      world.run(oldJob)
      world.drainUi()

      world.opened.shouldBeEmpty()
      MrFileDiffRegistry.trackedForTest() shouldBe tracked - 1
    }
  }
})
