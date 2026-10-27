package com.gitlab.eclipse.ci.joblog

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.commands.ExecutionEvent
import org.eclipse.core.commands.HandlerEvent
import org.eclipse.core.commands.IHandlerListener
import org.eclipse.core.expressions.IEvaluationContext
import org.eclipse.ui.IEditorPart
import org.eclipse.ui.ISources

/**
 * [RetryJobFromLogHandler] (L4): enabled for the active job log exactly when its session can retry,
 * told of changes through [JobLogRetryEnablement], and running the retry for that editor.
 */
class RetryJobFromLogHandlerTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  lateinit var w: FakeWorld
  lateinit var editor: IEditorPart
  lateinit var input: JobLogEditorInput
  val retried = mutableListOf<Pair<IEditorPart, JobLogEditorInput>>()
  val handlers = mutableListOf<RetryJobFromLogHandler>()

  beforeEach {
    w = FakeWorld()
    editor = mockk()
    retried.clear()
  }

  afterEach {
    handlers.forEach(RetryJobFromLogHandler::dispose)
    handlers.clear()
  }

  /** A job log that ended [status] before it started, open in one editor. */
  fun openedOn(status: String): JobLogSession {
    w.open(FakeView())
    w.remote.jobs(job(status))
    w.env.advance(0)
    val session = w.session.shouldNotBeNull()
    input = JobLogEditorInput(FAKE_KEY, session.content, FAKE_URL, FAKE_FP)
    return session
  }

  fun handler(onUiThread: () -> Boolean = { true }) = RetryJobFromLogHandler(
    sessionOf = w.registry::sessionFor,
    inputOf = { if (it === editor) input else null },
    retry = { part, shown -> retried += part to shown },
    onUiThread = onUiThread,
  ).also { handlers += it }

  fun context(activeEditor: Any?): IEvaluationContext = mockk {
    every { getVariable(ISources.ACTIVE_EDITOR_NAME) } returns (activeEditor ?: IEvaluationContext.UNDEFINED_VARIABLE)
  }

  describe("jobLogRetryEnabled") {
    it("is true only for an input whose session can retry") {
      val session = openedOn("failed")

      jobLogRetryEnabled(input, w.registry::sessionFor) shouldBe true
      jobLogRetryEnabled(null, w.registry::sessionFor) shouldBe false
      jobLogRetryEnabled(JobLogEditorInput(FAKE_KEY.copy(jobId = 999L), input.content), w.registry::sessionFor) shouldBe
        false
      session.retryStarted()
      jobLogRetryEnabled(input, w.registry::sessionFor) shouldBe false
    }

    it("is false for a job that cannot be retried") {
      openedOn("skipped")

      jobLogRetryEnabled(input, w.registry::sessionFor) shouldBe false
    }
  }

  describe("enablement") {
    it("follows the active editor's session") {
      openedOn("failed")
      val handler = handler()

      handler.setEnabled(context(editor))
      handler.isEnabled shouldBe true

      handler.setEnabled(context("another part"))
      handler.isEnabled shouldBe false

      handler.setEnabled(context(null))
      handler.isEnabled shouldBe false
    }

    it("keeps its last answer when asked off the UI thread") {
      openedOn("failed")
      var onUi = true
      val handler = handler { onUi }
      handler.setEnabled(context(editor))

      onUi = false
      handler.setEnabled(context(null))

      handler.isEnabled shouldBe true
    }

    it("recomputes and announces a change of the session's Retry state, until disposed") {
      val session = openedOn("failed")
      val handler = handler()
      handler.setEnabled(context(editor))
      val events = mutableListOf<HandlerEvent>()
      handler.addHandlerListener(IHandlerListener { events += it })

      session.retryStarted()
      JobLogRetryEnablement.changed()

      handler.isEnabled shouldBe false
      (events.isNotEmpty() && events.all { it.isEnabledChanged }) shouldBe true

      handler.dispose()
      events.clear()
      JobLogRetryEnablement.changed()
      events.shouldBeEmpty()
    }
  }

  describe("announcing") {
    it("announces a change even when the editor it last saw keeps its answer, so the service asks again") {
      openedOn("failed")
      val handler = handler()
      handler.setEnabled(context(editor))
      val events = mutableListOf<HandlerEvent>()
      handler.addHandlerListener(IHandlerListener { events += it })

      // Another job log may be active now without setEnabled having been called for it yet.
      JobLogRetryEnablement.changed()

      handler.isEnabled shouldBe true
      events.map { it.isEnabledChanged } shouldBe listOf(true)
    }
  }

  describe("execute") {
    it("retries the job of the active job log editor") {
      openedOn("failed")

      handler().execute(ExecutionEvent(null, emptyMap<String, String>(), null, context(editor)))

      retried shouldContainExactly listOf(editor to input)
    }

    it("does nothing without a job log editor") {
      openedOn("failed")
      val other = mockk<IEditorPart>()

      handler().execute(ExecutionEvent(null, emptyMap<String, String>(), null, context(other)))
      handler().execute(ExecutionEvent(null, emptyMap<String, String>(), null, context(null)))

      retried.shouldBeEmpty()
    }
  }
})
