package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.ci.actions.WriteKey
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

private const val NEW_JOB = 77L

/**
 * [JobLogRetry.press] (L4, D-3 (b)): one guarded retry per job; the new job's log opens and the
 * editor the retry came from closes, but only once the new editor is really open. Real sessions
 * ([FakeWorld]); the write, the UI hop and the workbench are fakes.
 */
class JobLogRetryTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var w: FakeWorld
  val held = mutableSetOf<WriteKey>()
  val released = mutableListOf<WriteKey>()
  val launches = mutableListOf<Launch>()
  val ui = ArrayDeque<() -> Unit>()
  val opened = mutableListOf<JobLogTarget>()
  val notices = mutableListOf<String>()
  val busy = mutableListOf<Boolean>()
  lateinit var originView: FakeView
  var closes = 0
  var active = true
  var newEditorOpens = true

  beforeEach {
    w = FakeWorld()
    listOf(held, released, launches, opened, notices, busy).forEach { recorded -> recorded.clear() }
    ui.clear()
    closes = 0
    active = true
    newEditorOpens = true
  }

  fun retry(
    launch: (WriteKey, JobLogEditorInput, (Long?) -> Unit, () -> Unit) -> Unit = { key, input, onNewJob, onDone ->
      launches += Launch(key, input, onNewJob, onDone)
    },
  ) = JobLogRetry(
    tryAcquire = { held.add(it) },
    release = {
      released += it
      held.remove(it)
    },
    launch = launch,
    sessionOf = w.registry::sessionFor,
    isActive = { active },
    openNew = { target ->
      opened += target
      w.registry.open(target) { input -> if (newEditorOpens) w.registry.attach(input.key, FakeView()) }
    },
    notify = { notices += it },
    runOnUi = { ui += it },
  )

  fun drainUi() {
    while (ui.isNotEmpty()) ui.removeFirst().invoke()
  }

  /** A job log that ended [status] before it started, open in one editor. */
  fun openedOn(status: String): Pair<JobLogSession, JobLogEditorInput> {
    originView = FakeView()
    w.open(originView)
    w.remote.jobs(job(status))
    w.env.advance(0)
    val session = w.session.shouldNotBeNull()
    return session to JobLogEditorInput(FAKE_KEY, session.content, FAKE_URL, FAKE_FP)
  }

  fun JobLogRetry.pressOn(input: JobLogEditorInput) =
    press(input, closeOrigin = { closes++ }, setBusy = { busy += it })

  /** The write succeeded and named [id]; then the guard is released and onDone runs, as launchCiWrite does. */
  fun Launch.succeed(id: Long?) {
    onNewJob(id)
    held.remove(key)
    onDone()
  }

  fun Launch.fail() {
    held.remove(key)
    onDone()
  }

  describe("a successful retry") {
    it("guards the job, opens the new job's log and closes the editor it came from") {
      val (session, input) = openedOn("failed")

      retry().pressOn(input) shouldBe true
      launches.single().key shouldBe WriteKey(FAKE_URL, "job", FAKE_JOB)
      busy shouldContainExactly listOf(true)
      session.canRetry shouldBe false

      launches.single().succeed(NEW_JOB)
      opened.shouldBeEmpty()
      drainUi()

      val target = opened.single()
      target.key shouldBe JobLogKey.of(FAKE_URL, FAKE_FP, FAKE_PROJECT, NEW_JOB)
      target.jobId shouldBe NEW_JOB
      target.projectId shouldBe FAKE_PROJECT
      closes shouldBe 1
      busy shouldContainExactly listOf(true, false)
      session.canRetry shouldBe false
      notices.shouldBeEmpty()
    }

    it("never retries the same job twice from the same session") {
      val (_, input) = openedOn("canceled")
      val retry = retry()
      retry.pressOn(input)
      launches.single().succeed(NEW_JOB)
      drainUi()

      retry.pressOn(input) shouldBe false
      launches.size shouldBe 1
    }

    it("treats a body without an id as a retry done: says so, opens and closes nothing") {
      listOf(null, 0L).forEach { id ->
        w = FakeWorld()
        val (session, input) = openedOn("failed")
        launches.clear()
        notices.clear()
        closes = 0

        retry().pressOn(input)
        launches.single().succeed(id)
        drainUi()

        opened.shouldBeEmpty()
        closes shouldBe 0
        notices shouldContainExactly listOf(RETRIED_NO_ID_MESSAGE)
        session.canRetry shouldBe false
      }
    }

    it("keeps the origin editor when the new job's editor did not open") {
      val (_, input) = openedOn("failed")
      newEditorOpens = false

      retry().pressOn(input)
      launches.single().succeed(NEW_JOB)
      drainUi()

      opened.single().jobId shouldBe NEW_JOB
      closes shouldBe 0
    }
  }

  describe("a result that arrives too late") {
    it("opens nothing once the editor was closed") {
      val (_, input) = openedOn("failed")
      retry().pressOn(input)
      w.registry.detach(FAKE_KEY, originView)

      launches.single().succeed(NEW_JOB)
      drainUi()

      opened.shouldBeEmpty()
      closes shouldBe 0
    }

    it("opens nothing after the plugin stopped") {
      val (_, input) = openedOn("failed")
      retry().pressOn(input)
      active = false

      launches.single().succeed(NEW_JOB)
      drainUi()

      opened.shouldBeEmpty()
      closes shouldBe 0
    }

    it("opens nothing when the job's session was replaced by a new one") {
      val (_, input) = openedOn("failed")
      retry().pressOn(input)
      w.registry.detach(FAKE_KEY, originView)
      w.open(FakeView())

      launches.single().succeed(NEW_JOB)
      drainUi()

      opened.shouldBeEmpty()
      closes shouldBe 0
    }
  }

  describe("a retry that does not start or fails") {
    it("does nothing while a write to the job is in flight") {
      val (session, input) = openedOn("failed")
      held += WriteKey(FAKE_URL, "job", FAKE_JOB)

      retry().pressOn(input) shouldBe false

      launches.shouldBeEmpty()
      busy.shouldBeEmpty()
      session.canRetry shouldBe true
    }

    it("does nothing for a job that cannot be retried, or without a session") {
      w.open(FakeView())
      w.remote.jobs(job("pending"))
      w.env.advance(0)
      val input = JobLogEditorInput(FAKE_KEY, w.session.shouldNotBeNull().content, FAKE_URL, FAKE_FP)

      retry().pressOn(input) shouldBe false
      retry().pressOn(JobLogEditorInput(FAKE_KEY.copy(jobId = 999L), input.content, FAKE_URL, FAKE_FP)) shouldBe false

      launches.shouldBeEmpty()
      held.shouldBeEmpty()
    }

    it("gives Retry back after a failed write, with the button enabled again") {
      val (session, input) = openedOn("failed")

      retry().pressOn(input)
      launches.single().fail()
      drainUi()

      busy shouldContainExactly listOf(true, false)
      session.canRetry shouldBe true
      opened.shouldBeEmpty()
      closes shouldBe 0
    }

    it("frees the job, the button and Retry when the launch itself throws") {
      val (session, input) = openedOn("failed")
      val throwing = retry { _, _, _, _ -> throw IllegalStateException("no scope") }

      shouldThrow<IllegalStateException> { throwing.pressOn(input) }

      released shouldContainExactly listOf(WriteKey(FAKE_URL, "job", FAKE_JOB))
      held.shouldBeEmpty()
      busy shouldContainExactly listOf(true, false)
      session.canRetry shouldBe true
    }
  }
})

/** One write [JobLogRetry] asked for; the test plays its outcome. */
private class Launch(
  val key: WriteKey,
  val input: JobLogEditorInput,
  val onNewJob: (Long?) -> Unit,
  val onDone: () -> Unit,
)
