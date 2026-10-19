package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.preferences.storage.CapturedLog
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.eclipse.core.runtime.jobs.Job
import org.eclipse.ui.IWorkbenchPage
import org.eclipse.ui.IWorkbenchWindow

/** The production ports with every platform seam faked: no Display, no workbench, no real Job. */
class EclipseMrDiffPortsTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val content = compareContent()
  val key = content.request.key

  class World(var page: IWorkbenchPage? = mockk()) {
    val opened = mutableListOf<Pair<MrFileDiffContent, IWorkbenchPage>>()
    val activated = mutableListOf<MrFileDiffKey>()
    val shown = mutableListOf<String>()
    var existing = false
    var openThrows: Exception? = null
    val job = mockk<Job>(relaxed = true)
    var createdWith: (() -> Unit)? = null

    val ports = EclipseMrDiffPorts(
      activePage = { page },
      hop = { it() },
      activateExisting = { k, _ ->
        activated += k
        existing
      },
      openOn = { c, p ->
        openThrows?.let { throw it }
        opened += c to p
      },
      show = { shown += it },
      newJob = { _, _, onEndedWithoutRunning ->
        createdWith = onEndedWithoutRunning
        job
      },
    )
  }

  describe("schedule") {
    it("hands out the cancel handle before scheduling, and the handle cancels the job") {
      val world = World()
      val calls = mutableListOf<String>()
      every { world.job.schedule() } answers { calls += "schedule" }
      var handle: (() -> Unit)? = null

      world.ports.schedule(
        "n",
        {},
        {
          calls += "handle"
          handle = it
        },
      ) {}

      calls shouldContainExactly listOf("handle", "schedule")
      verifyOrder {
        world.job.isUser = false
        world.job.schedule()
      }
      verify(exactly = 0) { world.job.cancel() }
      handle!!.invoke()
      verify(exactly = 1) { world.job.cancel() }
    }

    it("passes the ended-without-running callback to the job") {
      val world = World()
      var endedWithoutRunning = 0

      world.ports.schedule("n", {}, {}) { endedWithoutRunning++ }
      world.createdWith!!.invoke()

      endedWithoutRunning shouldBe 1
    }
  }

  describe("findOpen") {
    it("asks the active page for an existing editor") {
      val world = World().apply { existing = true }

      world.ports.findOpen(key) shouldBe true
      world.activated shouldContainExactly listOf(key)
    }

    it("is false without an active page") {
      val world = World(page = null)

      world.ports.findOpen(key) shouldBe false
      world.activated.shouldBeEmpty()
    }
  }

  describe("open") {
    it("opens on the active page") {
      val world = World()

      world.ports.open(content)

      world.opened.single().first shouldBe content
    }

    it("notifies instead of dropping the result silently when no window can show it (R2-5)") {
      val world = World(page = null)

      world.ports.open(content)

      world.opened.shouldBeEmpty()
      world.shown shouldContainExactly listOf(MrDiffMessages.NO_WINDOW_MESSAGE)
    }

    it("turns a failing open into one generic notification and an exception-type log line") {
      val log = CapturedLog().also { it.install() }
      val world = World().apply { openThrows = IllegalStateException("src/Secret.kt") }

      shouldNotThrowAny { world.ports.open(content) }

      world.shown shouldContainExactly listOf(MrDiffMessages.message(DiffFailure.GENERIC))
      log.lines.single() shouldContain "exceptionType=java.lang.IllegalStateException"
      log.lines.single().contains("Secret") shouldBe false
    }
  }

  it("notify shows the message") {
    val world = World()

    world.ports.notify("hello")

    world.shown shouldContainExactly listOf("hello")
  }

  it("uiHop goes through the hop") {
    val world = World()
    var ran = false

    world.ports.uiHop { ran = true }

    ran shouldBe true
  }

  describe("pageFor (R2-5)") {
    fun window(page: IWorkbenchPage?): IWorkbenchWindow = mockk<IWorkbenchWindow>().also {
      every { it.activePage } returns page
    }

    it("uses the active window's page") {
      val page = mockk<IWorkbenchPage>()

      pageFor(window(page), listOf(window(mockk()))) shouldBe page
    }

    it("falls back to the first window that has a page when no window is active") {
      val page = mockk<IWorkbenchPage>()

      pageFor(null, listOf(window(null), window(page), window(mockk()))) shouldBe page
    }

    it("falls back as well when the active window has no page") {
      val page = mockk<IWorkbenchPage>()

      pageFor(window(null), listOf(window(page))) shouldBe page
    }

    it("is null without any window") {
      pageFor(null, emptyList()).shouldBeNull()
    }
  }
})
