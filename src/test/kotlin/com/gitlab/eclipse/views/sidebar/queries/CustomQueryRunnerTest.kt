package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.ApiRequest
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.CurrentUserService
import com.gitlab.eclipse.api.GitLabApiClient
import com.gitlab.eclipse.api.GitLabApiException
import com.gitlab.eclipse.api.GitLabApiTimeoutException
import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.PagedResult
import com.gitlab.eclipse.api.UnstableConnectionException
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.api.model.GitLabUser
import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val ME = "<current_user>"
private const val SECRET_NAME = "Very Private Query Name"
private const val SECRET_FILTER = "secret-filter-value"
private const val EXCEPTION_TEXT = "boom-exception-text"

private val connection = ConnectionSnapshot("https://gitlab.example.com", "glpat-x", "fp", 1L)
private val project = QueryProject("group%2Fproj")

private fun query(id: String, json: String) = CustomQuery(id, JsonParser.parseString(json).asJsonObject)

/** An instance-wide query whose `search` value [tag] identifies it to the fake API. */
private fun q(id: String, type: String, tag: String, extra: String = "") =
  query(id, """{"name":"$SECRET_NAME","type":"$type","target":"instance","search":"$tag"$extra}""")

private fun issue(id: Long) = GitLabIssue(id, id, "t$id", "https://gitlab.example.com/i/$id", "opened", null)

private fun mr(id: Long) = GitLabMergeRequest(id, id, "t$id", 1L, "https://gitlab.example.com/m/$id", "opened")

private class Settled(val id: String, val outcome: QueryOutcome, val executed: QueryPlan.Run?)

/** A fake API answering `fetchListPages` by the request's `search` param. */
private class FakeApi {
  val client = mockk<GitLabApiClient>()
  val responses = mutableMapOf<String, (ApiRequest<*>, () -> Boolean) -> PagedResult<*>>()
  val requests: MutableList<ApiRequest<*>> = Collections.synchronizedList(mutableListOf())

  init {
    every { client.fetchListPages(any<ApiRequest<Any>>(), any(), any(), any()) } answers {
      val request = firstArg<ApiRequest<*>>()
      requests += request
      val respond = checkNotNull(responses[request.query["search"]]) { "no response for request" }
      @Suppress("UNCHECKED_CAST")
      respond(request, thirdArg()) as PagedResult<Any>
    }
  }

  fun ok(tag: String, result: PagedResult<*>) {
    responses[tag] = { _, _ -> result }
  }

  fun fail(tag: String, error: Throwable) {
    responses[tag] = { _, _ -> throw error }
  }
}

private class Harness(parallelism: Int = 4) {
  val api = FakeApi()
  val users = mockk<CurrentUserService>()
  val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
  val settled: MutableList<Settled> = Collections.synchronizedList(mutableListOf())
  val dispatcher = StandardTestDispatcher()
  val runner = CustomQueryRunner(api.client, users, { warnings += it }, parallelism, dispatcher)

  fun run(
    queries: List<CustomQuery>,
    project: QueryProject? = null,
    isActive: () -> Boolean = { true },
    reuse: QueryReuse = QueryReuse.None,
  ) =
    TestScope(dispatcher).runTest {
      runner.run(queries, project, connection, isActive, reuse) { id, outcome, executed ->
        settled += Settled(id, outcome, executed)
      }
    }

  fun outcome(id: String): QueryOutcome = settled.single { it.id == id }.outcome

  fun executed(id: String): QueryPlan.Run? = settled.single { it.id == id }.executed
}

class CustomQueryRunnerTest : StringSpec({
  extensions(LoggingKotestExtension)

  "each query settles with its own outcome; one failure never affects the others" {
    val h = Harness()
    h.api.ok("ok", PagedResult(listOf(issue(1), issue(2)), truncated = true))
    h.api.fail("forbidden", GitLabApiException(403, "{}", "cid-1"))
    h.api.fail("broken", GitLabApiException(500, "{}", null))
    val queries = listOf(
      q("ok", "issues", "ok"),
      q("forbidden", "merge_requests", "forbidden"),
      q("broken", "issues", "broken"),
      query("invalid", """{"name":"x","type":"issues","target":"instance","state":"nope"}"""),
      query("unsupported", """{"name":"x","type":"epics"}"""),
      query("projectless", """{"name":"x","type":"issues","target":"project"}"""),
    )

    h.run(queries)

    h.settled.map { it.id }.toSet() shouldBe queries.map { it.id }.toSet()
    h.settled shouldHaveSize 6
    val ok = h.outcome("ok").shouldBeInstanceOf<QueryOutcome.Issues>()
    ok.items.map { it.id } shouldContainExactly listOf(1L, 2L)
    ok.truncated shouldBe true
    h.outcome("forbidden").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe
      QueryFailureKind.NOT_FOUND_OR_DENIED
    h.outcome("broken").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.GENERIC
    h.outcome("invalid").shouldBeInstanceOf<QueryOutcome.Invalid>().message shouldNotContain "nope"
    h.outcome("unsupported") shouldBeSameInstanceAs QueryOutcome.Unsupported
    h.outcome("projectless") shouldBeSameInstanceAs QueryOutcome.NeedsProject
    h.executed("ok")?.path shouldBe "/issues"
    h.executed("forbidden")?.path shouldBe "/merge_requests"
    h.executed("broken")?.path shouldBe "/issues"
    listOf("invalid", "unsupported", "projectless").forEach { h.executed(it) shouldBe null }
    h.warnings shouldHaveSize 2
  }

  "Invalid outcome carries the validator problem's message" {
    val h = Harness()
    h.run(listOf(query("invalid", """{"name":"x","type":"issues","target":"instance","state":"nope"}""")))
    val stored = query("invalid", """{"name":"x","type":"issues","target":"instance","state":"nope"}""")
    val problem = CustomQueryValidator.validate(stored).first { it.severity == ProblemSeverity.ERROR }
    h.outcome("invalid").shouldBeInstanceOf<QueryOutcome.Invalid>().message shouldBe problem.message
    h.executed("invalid") shouldBe null
    h.warnings.shouldBeEmpty()
  }

  "project target runs under the project's path when a project is given" {
    val h = Harness()
    h.api.ok("p", PagedResult(listOf(mr(7)), truncated = false))
    h.run(listOf(query("p", """{"name":"x","type":"merge_requests","target":"project","search":"p"}""")), project)
    val outcome = h.outcome("p").shouldBeInstanceOf<QueryOutcome.MergeRequests>()
    outcome.items.map { it.id } shouldContainExactly listOf(7L)
    h.api.requests.single().path shouldBe "/projects/group%2Fproj/merge_requests"
    h.api.requests.single().elementType shouldBe GitLabMergeRequest::class.java
  }

  "classifyFailure maps each failure to its kind" {
    val table = listOf(
      GitLabApiException(400, "", null) to QueryFailureKind.REJECTED,
      GitLabApiException(401, "", null) to QueryFailureKind.UNAUTHORIZED,
      GitLabApiException(403, "", null) to QueryFailureKind.NOT_FOUND_OR_DENIED,
      GitLabApiException(404, "", null) to QueryFailureKind.NOT_FOUND_OR_DENIED,
      GitLabApiException(429, "", null) to QueryFailureKind.RATE_LIMITED,
      GitLabApiException(500, "", null) to QueryFailureKind.GENERIC,
      GitLabApiTimeoutException(3) to QueryFailureKind.TIMED_OUT,
      GitLabConfigurationException("TLS problem") to QueryFailureKind.CONFIG,
      UnstableConnectionException() to QueryFailureKind.GENERIC,
      RuntimeException("x") to QueryFailureKind.GENERIC,
    )
    table.forEach { (error, kind) -> classifyFailure(error).kind shouldBe kind }
    classifyFailure(GitLabConfigurationException("TLS problem")).configMessage shouldBe "TLS problem"
    classifyFailure(GitLabApiException(500, "", null)).configMessage shouldBe null
  }

  "the current user is resolved once for two placeholder queries" {
    val h = Harness()
    every { h.users.getCurrentUser(connection) } returns GitLabUser(1, "alice")
    h.api.ok("a", PagedResult(listOf(issue(1)), truncated = false))
    h.api.ok("b", PagedResult(listOf(mr(2)), truncated = false))
    h.run(
      listOf(
        q("a", "issues", "a", ""","author":"$ME""""),
        q("b", "merge_requests", "b", ""","reviewer":"$ME""""),
      ),
    )
    verify(exactly = 1) { h.users.getCurrentUser(connection) }
    h.api.requests.map { it.query["author_username"] ?: it.query["reviewer_username"] } shouldContainExactly
      listOf("alice", "alice")
    h.executed("a")?.params?.get("author_username") shouldBe "alice"
    h.executed("b")?.params?.get("reviewer_username") shouldBe "alice"
    h.outcome("a").shouldBeInstanceOf<QueryOutcome.Issues>()
    h.outcome("b").shouldBeInstanceOf<QueryOutcome.MergeRequests>()
  }

  "the current user is not resolved when no runnable query uses the placeholder" {
    val h = Harness()
    h.api.ok("a", PagedResult(listOf(issue(1)), truncated = false))
    h.run(
      listOf(
        q("a", "issues", "a"),
        query("projectless", """{"name":"x","type":"issues","target":"project","author":"$ME"}"""),
        query("epic", """{"name":"x","type":"epics","author":"$ME"}"""),
      ),
    )
    verify(exactly = 0) { h.users.getCurrentUser(any()) }
    h.outcome("projectless") shouldBeSameInstanceAs QueryOutcome.NeedsProject
  }

  "a current-user failure fails only the placeholder queries" {
    val h = Harness()
    every { h.users.getCurrentUser(connection) } throws GitLabApiException(401, "{}", "cid-u")
    h.api.ok("plain", PagedResult(listOf(issue(1)), truncated = false))
    h.run(
      listOf(
        q("me1", "issues", "me1", ""","assignee":"$ME""""),
        q("plain", "issues", "plain"),
        q("me2", "merge_requests", "me2", ""","author":"$ME""""),
      ),
    )
    verify(exactly = 1) { h.users.getCurrentUser(connection) }
    h.outcome("me1").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.CURRENT_USER
    h.executed("me1") shouldBe null
    h.executed("me2") shouldBe null
    h.executed("plain")?.params?.get("search") shouldBe "plain"
    h.outcome("me2").shouldBeInstanceOf<QueryOutcome.Failed>().kind shouldBe QueryFailureKind.CURRENT_USER
    h.outcome("plain").shouldBeInstanceOf<QueryOutcome.Issues>()
    h.api.requests.map { it.query["search"] } shouldContainExactly listOf("plain")
    h.warnings shouldHaveSize 2
    h.warnings.forEach { it shouldContain "kind=CURRENT_USER" }
  }

  "items are deduped by id keeping the first occurrence" {
    val h = Harness()
    val first2 = issue(2)
    h.api.ok("d", PagedResult(listOf(issue(1), first2, issue(2).copy(title = "dup"), issue(3)), truncated = false))
    h.run(listOf(q("d", "issues", "d")))
    val items = h.outcome("d").shouldBeInstanceOf<QueryOutcome.Issues>().items
    items.map { it.id } shouldContainExactly listOf(1L, 2L, 3L)
    items[1] shouldBeSameInstanceAs first2
  }

  "the WARN line carries only safe tokens" {
    val h = Harness()
    h.api.fail("x", GitLabApiException(403, "{\"message\":\"$EXCEPTION_TEXT\"}", "cid-9"))
    h.api.fail("y", IllegalStateException(EXCEPTION_TEXT))
    h.run(
      listOf(
        query("invalid", """{"name":"x","type":"issues","target":"instance","state":"nope"}"""),
        q("x", "merge_requests", "x", ""","labels":["$SECRET_FILTER"]"""),
        q("y", "issues", "y", ""","milestone":"$SECRET_FILTER""""),
      ),
    )
    h.warnings.sorted() shouldContainExactly listOf(
      "Sidebar query failed: position=2 type=merge_requests kind=NOT_FOUND_OR_DENIED status=403 " +
        "correlationId=cid-9 exceptionType=com.gitlab.eclipse.api.GitLabApiException",
      "Sidebar query failed: position=3 type=issues kind=GENERIC status=- correlationId=- " +
        "exceptionType=java.lang.IllegalStateException",
    )
    h.warnings.forEach {
      it shouldNotContain SECRET_NAME
      it shouldNotContain SECRET_FILTER
      it shouldNotContain EXCEPTION_TEXT
      it shouldNotContain "gitlab.example.com"
      it shouldNotContain "/merge_requests"
    }
  }

  "at most four queries are in flight at once" {
    val pool = Executors.newFixedThreadPool(8)
    try {
      val api = FakeApi()
      val inFlight = AtomicInteger()
      val maxInFlight = AtomicInteger()
      val arrived = Collections.synchronizedList(mutableListOf<String>())
      val arrivals = (1..6).map { CountDownLatch(it) }
      val gates = (1..6).associate { "g$it" to CountDownLatch(1) }
      gates.keys.forEach { tag ->
        api.responses[tag] = { _, _ ->
          maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
          arrived += tag
          arrivals.forEach { it.countDown() }
          check(gates.getValue(tag).await(10, TimeUnit.SECONDS)) { "gate never opened" }
          inFlight.decrementAndGet()
          PagedResult(emptyList<GitLabIssue>(), truncated = false)
        }
      }
      val runner = CustomQueryRunner(api.client, mockk(), {}, dispatcher = pool.asCoroutineDispatcher())
      val settled = Collections.synchronizedList(mutableListOf<String>())
      runBlocking {
        val job = launch(pool.asCoroutineDispatcher()) {
          runner.run(gates.keys.map { q(it, "issues", it) }, null, connection, { true }) { id, _, _ -> settled += id }
        }
        check(arrivals[3].await(10, TimeUnit.SECONDS))
        arrived shouldHaveSize 4
        // Each release lets exactly one waiting query in.
        for (n in 0 until 6) {
          gates.getValue(synchronized(arrived) { arrived[n] }).countDown()
          if (n + 4 < 6) check(arrivals[n + 4].await(10, TimeUnit.SECONDS))
        }
        job.join()
      }
      maxInFlight.get() shouldBe 4
      settled shouldHaveSize 6
    } finally {
      pool.shutdownNow()
    }
  }

  "cancellation propagates and nothing settles after it" {
    val h = Harness(parallelism = 1)
    var active = true
    h.api.responses["a"] = { _, _ ->
      active = false
      PagedResult(listOf(issue(1)), truncated = false)
    }
    h.api.responses["b"] = { _, isActive ->
      if (!isActive()) throw CancellationException("cancelled")
      PagedResult(listOf(issue(2)), truncated = false)
    }
    shouldThrow<CancellationException> {
      h.run(listOf(q("a", "issues", "a"), q("b", "issues", "b")), isActive = { active })
    }
    h.settled.shouldBeEmpty()
    h.warnings.shouldBeEmpty()
    h.api.requests.map { it.query["search"] } shouldContainExactly listOf("a")
  }
  "a failure seen after the refresh became inactive is not logged" {
    val h = Harness(parallelism = 1)
    var active = true
    h.api.responses["a"] = { _, _ ->
      active = false
      throw GitLabApiException(500, "{}", null)
    }
    shouldThrow<CancellationException> {
      h.run(listOf(q("a", "issues", "a")), isActive = { active })
    }
    h.settled.shouldBeEmpty()
    h.warnings.shouldBeEmpty()
  }

  "a throwing callback is logged and affects neither the other queries nor run" {
    val h = Harness()
    h.api.ok("a", PagedResult(listOf(issue(1)), truncated = false))
    h.api.ok("b", PagedResult(listOf(issue(2)), truncated = false))
    val seen = mutableListOf<String>()
    TestScope(h.dispatcher).runTest {
      h.runner.run(listOf(q("a", "issues", "a"), q("b", "issues", "b")), null, connection, { true }) { id, _, _ ->
        seen += id
        check(id != "a") { EXCEPTION_TEXT }
      }
    }
    seen shouldContainExactly listOf("a", "b")
    h.warnings shouldContainExactly listOf(
      "Sidebar query callback failed: position=1 exceptionType=java.lang.IllegalStateException",
    )
  }

  "the callback runs without holding a permit" {
    val pool = Executors.newFixedThreadPool(4)
    try {
      val api = FakeApi()
      val fetched = mapOf("a" to CountDownLatch(1), "b" to CountDownLatch(1))
      fetched.forEach { (tag, latch) ->
        api.responses[tag] = { _, _ ->
          latch.countDown()
          PagedResult(listOf(issue(1)), truncated = false)
        }
      }
      val dispatcher = pool.asCoroutineDispatcher()
      val runner = CustomQueryRunner(api.client, mockk(), {}, parallelism = 1, dispatcher = dispatcher)
      val callbacks = AtomicInteger()
      var otherFetchedDuringFirstCallback = false
      runBlocking(pool.asCoroutineDispatcher()) {
        runner.run(listOf(q("a", "issues", "a"), q("b", "issues", "b")), null, connection, { true }) { id, _, _ ->
          if (callbacks.getAndIncrement() == 0) {
            val other = fetched.getValue(if (id == "a") "b" else "a")
            otherFetchedDuringFirstCallback = other.await(10, TimeUnit.SECONDS)
          }
        }
      }
      otherFetchedDuringFirstCallback shouldBe true
    } finally {
      pool.shutdownNow()
    }
  }

  "cancelling the caller while a query is in flight settles nothing afterwards" {
    val pool = Executors.newFixedThreadPool(2)
    try {
      val api = FakeApi()
      val arrived = CountDownLatch(1)
      val gate = CountDownLatch(1)
      val finished = CountDownLatch(1)
      api.responses["a"] = { _, _ ->
        arrived.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "gate never opened" }
        finished.countDown()
        PagedResult(listOf(issue(1)), truncated = false)
      }
      val runner = CustomQueryRunner(api.client, mockk(), {}, dispatcher = pool.asCoroutineDispatcher())
      val settled = Collections.synchronizedList(mutableListOf<String>())
      runBlocking {
        val job = launch(pool.asCoroutineDispatcher()) {
          runner.run(listOf(q("a", "issues", "a")), null, connection, { true }) { id, _, _ -> settled += id }
        }
        check(arrived.await(10, TimeUnit.SECONDS))
        job.cancel()
        gate.countDown()
        job.join()
        job.isCancelled shouldBe true
      }
      finished.count shouldBe 0L
      settled.shouldBeEmpty()
    } finally {
      pool.shutdownNow()
    }
  }

  "reuse: a query already settled by stage (a) is neither fetched nor settled again" {
    val h = Harness()
    h.api.ok("b", PagedResult(listOf(issue(2)), truncated = false))
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = queryId == "a"
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = null
    }
    h.run(listOf(q("a", "issues", "a"), q("b", "issues", "b")), reuse = reuse)
    h.settled.map { it.id } shouldContainExactly listOf("b")
    h.api.requests.map { it.query["search"] } shouldContainExactly listOf("b")
  }

  "reuse: an unchanged request settles with the cached outcome and the executed plan, without fetching" {
    val h = Harness()
    val cached = QueryOutcome.Issues(listOf(issue(9)), truncated = false)
    val seen = Collections.synchronizedList(mutableListOf<QueryPlan.Run>())
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = false
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = cached.also { seen += plan }
    }
    h.run(listOf(q("a", "issues", "a")), reuse = reuse)
    h.api.requests.shouldBeEmpty()
    h.outcome("a") shouldBeSameInstanceAs cached
    h.executed("a") shouldBe seen.single()
    h.executed("a")?.params?.get("search") shouldBe "a"
  }

  "reuse: the stage (b) check sees the plan with the current user resolved" {
    val h = Harness()
    every { h.users.getCurrentUser(connection) } returns GitLabUser(1, "alice")
    val seen = Collections.synchronizedList(mutableListOf<QueryPlan.Run>())
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = false
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? {
        seen += plan
        return QueryOutcome.Issues(emptyList(), truncated = false)
      }
    }
    h.run(listOf(q("a", "issues", "a", ""","author":"$ME"""")), reuse = reuse)
    seen.single().params["author_username"] shouldBe "alice"
  }

  "a failure WARN names the config position even when earlier queries were skipped" {
    val h = Harness()
    h.api.fail("b", GitLabApiException(500, "{}", null))
    val reuse = object : QueryReuse {
      override fun isSettled(queryId: String) = queryId == "a"
      override fun outcomeFor(queryId: String, plan: QueryPlan.Run): QueryOutcome? = null
    }
    h.run(listOf(q("a", "issues", "a"), q("b", "issues", "b")), reuse = reuse)
    h.warnings.single() shouldContain "position=2 "
  }
})
