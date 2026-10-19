package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.ApiRequest
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.PagedResult
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

private val snapshot = ConnectionSnapshot("https://gitlab.example.com", "glpat-x", "fp", 1L)
private const val SECRET_TEXT = "secret-exception-text"

private fun query(id: String, json: String) = CustomQuery(id, JsonParser.parseString(json).asJsonObject)

private val instanceQuery = query("inst", """{"name":"I","type":"issues","target":"instance","search":"inst"}""")
private val projectQuery = query("proj", """{"name":"P","type":"merge_requests","target":"project","search":"proj"}""")
private val invalidProjectQuery = query("bad", """{"name":"B","type":"issues","target":"project","state":"nope"}""")
private val unsupportedQuery = query("epic", """{"name":"E","type":"epics"}""")

private class RefreshHarness {
  val api = mockk<GitLabApiClient>()
  val users = mockk<CurrentUserService>()
  val dispatcher = StandardTestDispatcher()
  val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val settled: MutableList<Pair<String, QueryOutcome>> = Collections.synchronizedList(mutableListOf())
  val resolveCalls = AtomicInteger()
  var capture: () -> ConnectionSnapshot = { snapshot }
  val runner = CustomQueryRunner(api, users, { warnings += it }, dispatcher = dispatcher)
  val refresh = CustomQueriesRefresh(runner, { capture() }) { warnings += it }

  init {
    every { api.fetchListPages(any<ApiRequest<Any>>(), any(), any(), any()) } returns
      PagedResult(emptyList<Any>(), truncated = false)
  }

  fun run(
    queries: List<CustomQuery>,
    reuse: QueryReuse = QueryReuse.None,
    project: QueryProject? = QueryProject("g%2Fp"),
    isActive: () -> Boolean = { true },
  ) = TestScope(dispatcher).runTest {
    refresh.run(queries, reuse, { project.also { resolveCalls.incrementAndGet() } }, isActive) { id, outcome, _ ->
      settled += id to outcome
    }
  }

  fun outcome(id: String) = settled.single { it.first == id }.second
}

class CustomQueriesRefreshTest : StringSpec({
  extensions(LoggingKotestExtension)

  "the repository is not resolved when no runnable query targets the project" {
    val h = RefreshHarness()
    h.run(listOf(instanceQuery, invalidProjectQuery, unsupportedQuery))
    h.resolveCalls.get() shouldBe 0
    h.settled.map { it.first } shouldContainExactlyInAnyOrder listOf("inst", "bad", "epic")
  }

  "the repository is resolved once when a runnable query targets the project" {
    val h = RefreshHarness()
    h.run(listOf(instanceQuery, projectQuery))
    h.resolveCalls.get() shouldBe 1
    h.outcome("proj").shouldBeInstanceOf<QueryOutcome.MergeRequests>()
  }

  "no repository: a project query settles NeedsProject and the others still run" {
    val h = RefreshHarness()
    h.run(listOf(instanceQuery, projectQuery), project = null)
    h.outcome("proj") shouldBeSameInstanceAs QueryOutcome.NeedsProject
    h.outcome("inst").shouldBeInstanceOf<QueryOutcome.Issues>()
  }

  "a repository resolution failure runs on without a project and logs the class only" {
    val h = RefreshHarness()
    val failingResolve: () -> QueryProject? = { error(SECRET_TEXT) }
    TestScope(h.dispatcher).runTest {
      h.refresh.run(listOf(instanceQuery, projectQuery), QueryReuse.None, failingResolve, { true }) { id, o, _ ->
        h.settled += id to o
      }
    }
    h.outcome("proj") shouldBeSameInstanceAs QueryOutcome.NeedsProject
    h.outcome("inst").shouldBeInstanceOf<QueryOutcome.Issues>()
    h.warnings shouldContainExactly listOf(
      "Sidebar queries could not resolve the repository: exceptionType=java.lang.IllegalStateException",
    )
  }

  "connection capture failure: only runnable queries get the failure; the others keep their own outcome" {
    val h = RefreshHarness()
    h.capture = { throw GitLabConfigurationException("TLS problem $SECRET_TEXT") }
    val projectless = query("pl", """{"name":"X","type":"issues","target":"project"}""")
    h.run(listOf(instanceQuery, invalidProjectQuery, unsupportedQuery, projectless), project = null)
    h.outcome("inst").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.CONFIG
    h.outcome("bad").shouldBeInstanceOf<QueryOutcome.Invalid>()
    h.outcome("epic") shouldBeSameInstanceAs QueryOutcome.Unsupported
    h.outcome("pl") shouldBeSameInstanceAs QueryOutcome.NeedsProject
    h.warnings shouldContainExactly listOf(
      "Sidebar queries could not capture the connection: " +
        "exceptionType=com.gitlab.eclipse.api.GitLabConfigurationException",
    )
  }

  "connection capture failure skips queries already settled by stage (a)" {
    val h = RefreshHarness()
    h.capture = { throw IllegalStateException(SECRET_TEXT) }
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = queryId == "inst"
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = null
    }
    h.run(listOf(instanceQuery, projectQuery), reuse)
    h.settled.map { it.first } shouldContainExactly listOf("proj")
    h.outcome("proj").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.GENERIC
  }

  "everything settled by stage (a): no repository, no connection, nothing settled" {
    val h = RefreshHarness()
    var captured = false
    h.capture = {
      captured = true
      snapshot
    }
    val all = object : QueryReuse {
      override fun isSettled(queryId: String) = true
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = null
    }
    h.run(listOf(instanceQuery, projectQuery), all)
    h.resolveCalls.get() shouldBe 0
    captured shouldBe false
    h.settled.shouldBeEmpty()
  }

  "an unexpected failure settles every not-yet-settled query with GENERIC, one WARN with the class only" {
    val h = RefreshHarness()
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = queryId == "skip"
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = error(SECRET_TEXT)
    }
    val skipped = query("skip", """{"name":"S","type":"issues","target":"instance"}""")
    h.run(listOf(skipped, unsupportedQuery, instanceQuery, projectQuery), reuse)
    h.outcome("epic") shouldBeSameInstanceAs QueryOutcome.Unsupported
    h.outcome("inst").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.GENERIC
    h.outcome("proj").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.GENERIC
    h.settled.map { it.first }.filter { it == "skip" }.shouldBeEmpty()
    h.settled.map { it.first }.toSet().size shouldBe h.settled.size
    h.warnings shouldContainExactly listOf("Sidebar queries failed: exceptionType=java.lang.IllegalStateException")
    h.warnings.forEach { it shouldNotContain SECRET_TEXT }
  }

  "an unexpected failure after the run went inactive settles nothing" {
    val h = RefreshHarness()
    var active = true
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = false
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? {
        active = false
        error(SECRET_TEXT)
      }
    }
    h.run(listOf(instanceQuery), reuse, isActive = { active })
    h.settled.shouldBeEmpty()
  }

  "an Error is handled like any other unexpected failure: pending queries settle GENERIC" {
    val h = RefreshHarness()
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = false
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? =
        throw StackOverflowError(SECRET_TEXT)
    }
    h.run(listOf(instanceQuery), reuse)
    h.outcome("inst").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.GENERIC
    h.warnings shouldContainExactly listOf("Sidebar queries failed: exceptionType=java.lang.StackOverflowError")
  }
})
