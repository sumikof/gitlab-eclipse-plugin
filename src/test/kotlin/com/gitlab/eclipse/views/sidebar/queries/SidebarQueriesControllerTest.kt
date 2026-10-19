package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.preferences.PreferenceConstants
import com.gitlab.eclipse.views.sidebar.RefreshSlots
import com.gitlab.eclipse.views.sidebar.Slot
import com.gitlab.eclipse.views.sidebar.acceptsApply
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.eclipse.jface.preference.IPreferenceStore
import org.eclipse.jface.util.IPropertyChangeListener
import org.eclipse.jface.util.PropertyChangeEvent
import java.io.File

private const val ONE_QUERY = """{"version":1,"queries":[{"id":"a","name":"A","type":"issues","target":"instance"}]}"""
private const val TWO_QUERIES =
  """{"version":1,"queries":[{"id":"a","name":"A","type":"issues","target":"instance"},""" +
    """{"id":"b","name":"B","type":"issues","target":"instance","state":"closed"}]}"""

private val PLAN = QueryPlan.Run("/issues", mapOf("state" to "opened"), null)

/** One launch of the queries work, as the fake IO port saw it. */
private class WorkCall(
  val queries: List<CustomQuery>,
  val reuse: QueryReuse,
  val onSettled: (String, QueryOutcome, QueryPlan.Run?) -> Unit,
) {
  var job: Job? = null
}

/** A store that really dispatches to its registered listeners (no SWT). */
private class FakeStore {
  val listeners = mutableListOf<IPropertyChangeListener>()
  var stored = ONE_QUERY
  val store = mockk<IPreferenceStore>()

  init {
    every { store.addPropertyChangeListener(any()) } answers { listeners += firstArg<IPropertyChangeListener>() }
    every { store.removePropertyChangeListener(any()) } answers { listeners -= firstArg<IPropertyChangeListener>() }
    every { store.getString(PreferenceConstants.SIDEBAR_CUSTOM_QUERIES) } answers { stored }
  }

  fun change(newValue: String) {
    stored = newValue
    listeners.toList().forEach {
      it.propertyChange(PropertyChangeEvent(store, PreferenceConstants.SIDEBAR_CUSTOM_QUERIES, null, newValue))
    }
  }
}

private class ControllerHarness(val fake: FakeStore = FakeStore()) {
  val scheduler = TestCoroutineScheduler()
  val parent = Job()
  val scope = CoroutineScope(StandardTestDispatcher(scheduler) + parent)
  val calls = mutableListOf<WorkCall>()
  val logs = mutableListOf<String>()
  var slots: RefreshSlots? = null
  var fullRefreshes = 0
  private var nextGeneration = 1L

  val controller = SidebarQueriesController(
    scope = scope,
    store = fake.store,
    work = { queries, reuse, _, _, onSettled ->
      val call = WorkCall(queries, reuse, onSettled)
      call.job = currentCoroutineContext()[Job]
      calls += call
      awaitCancellation()
    },
    resolveProject = { _: File? -> null },
    uiHop = { it() },
    apply = { target, applyScope, update ->
      if (target.acceptsApply({ it == slots?.generation }, applyScope)) update()
    },
    currentSlots = { slots },
    activeEditorFile = { null },
    fullRefresh = { fullRefreshes++ },
    log = { logs += it },
  )

  /** What the view's refresh() does on the query side: a new generation, then the controller. */
  fun fullRefresh(): RefreshSlots {
    val fresh = RefreshSlots(nextGeneration++)
    slots = fresh
    controller.onFullRefresh(fresh, null)
    drain()
    return fresh
  }

  fun drain() = scheduler.advanceUntilIdle()

  fun lastCall() = calls.last()
}

private fun items() = QueryOutcome.Issues(emptyList(), truncated = false)

class SidebarQueriesControllerTest : StringSpec({
  // (a) job structure

  "the queries job is a direct child of the shared scope, so it is never a child of fetchJob" {
    val h = ControllerHarness()
    h.fullRefresh()
    h.parent.children.toList() shouldContain h.lastCall().job!!
  }

  "cancelling the current-branch job (fetchJob stand-in) leaves the queries job running, and vice versa" {
    val h = ControllerHarness()
    val fetchJob = h.scope.launch { awaitCancellation() }
    h.fullRefresh()
    val queriesJob = h.lastCall().job!!
    fetchJob.cancel()
    h.drain()
    queriesJob.isActive shouldBe true
    val fetchJob2 = h.scope.launch { awaitCancellation() }
    h.controller.refreshQueries()
    h.drain()
    queriesJob.isCancelled shouldBe true
    fetchJob2.isActive shouldBe true
    fetchJob2.cancel()
  }

  // (b) listener lifecycle

  "start registers one listener even when called twice; a store change re-runs the queries" {
    val h = ControllerHarness()
    h.controller.start()
    h.controller.start()
    h.fake.listeners shouldHaveSize 1
    h.fullRefresh()
    h.fake.change(TWO_QUERIES)
    h.drain()
    h.calls shouldHaveSize 2
    h.lastCall().queries.map { it.id } shouldContainExactly listOf("a", "b")
  }

  "after dispose the store has no listener, a later change triggers nothing, and the job is cancelled" {
    val h = ControllerHarness()
    h.controller.start()
    h.fullRefresh()
    val job = h.lastCall().job!!
    h.controller.dispose()
    h.drain()
    h.fake.listeners.shouldBeEmpty()
    job.isCancelled shouldBe true
    h.fake.change(TWO_QUERIES)
    h.controller.refreshQueries()
    h.drain()
    h.calls shouldHaveSize 1
  }

  "close and reopen (dispose, then a new controller on the same store) leaves exactly one listener" {
    val fake = FakeStore()
    val first = ControllerHarness(fake)
    first.controller.start()
    first.controller.dispose()
    val second = ControllerHarness(fake)
    second.controller.start()
    fake.listeners shouldHaveSize 1
  }

  // (c) refresh paths

  "refreshQueries bumps only queriesEpoch: generation and currentBranchEpoch are untouched" {
    val h = ControllerHarness()
    val slots = h.fullRefresh()
    slots.currentBranchEpoch = 7
    h.controller.refreshQueries()
    slots.generation shouldBe 1L
    slots.currentBranchEpoch shouldBe 7L
    slots.queriesEpoch shouldBe 1L
    h.slots shouldBeSameInstanceAs slots
  }

  "refreshQueries before any refresh runs the first full refresh instead" {
    val h = ControllerHarness()
    h.controller.refreshQueries()
    h.fullRefreshes shouldBe 1
    h.calls.shouldBeEmpty()
  }

  "a late settle of a superseded queries run is dropped: neither the slot nor the cache changes" {
    val h = ControllerHarness()
    val slots = h.fullRefresh()
    val stale = h.lastCall()
    h.fake.change(TWO_QUERIES)
    h.controller.refreshQueries()
    h.drain()
    stale.onSettled("a", items(), PLAN)
    slots.queries.outcomes["a"] shouldBe Slot.Pending
    h.controller.refreshQueries()
    h.drain()
    h.lastCall().reuse.isSettled("a") shouldBe false
    h.lastCall().reuse.outcomeFor("a", PLAN).shouldBeNull()
  }

  "a settle of the live run is applied to the slot it was launched for" {
    val h = ControllerHarness()
    val slots = h.fullRefresh()
    val outcome = items()
    h.lastCall().onSettled("a", outcome, PLAN)
    (slots.queries.outcomes["a"] as Slot.Settled).value shouldBeSameInstanceAs outcome
  }

  "refreshQueries reuses an unchanged list outcome at once; a full refresh re-runs everything" {
    val h = ControllerHarness()
    val first = h.fullRefresh()
    val outcome = items()
    h.lastCall().onSettled("a", outcome, PLAN)
    h.controller.refreshQueries()
    h.drain()
    h.lastCall().reuse.isSettled("a") shouldBe true
    (first.queries.outcomes["a"] as Slot.Settled).value shouldBeSameInstanceAs outcome

    val second = h.fullRefresh()
    h.lastCall().reuse.isSettled("a") shouldBe false
    h.lastCall().reuse.outcomeFor("a", PLAN).shouldBeNull()
    second.queries.outcomes["a"] shouldBe Slot.Pending
    h.controller.refreshQueries()
    h.drain()
    h.lastCall().reuse.outcomeFor("a", PLAN).shouldBeNull()
  }

  "an unreadable setting logs one WARN per refresh with the reason token only" {
    val h = ControllerHarness()
    h.fake.stored = """{"version":2,"queries":[{"name":"secret-text"}]}"""
    h.fullRefresh()
    h.logs shouldContainExactly listOf("Sidebar queries setting unreadable: reason=UNSUPPORTED_VERSION")
    h.controller.refreshQueries()
    h.logs shouldHaveSize 2
  }
})
