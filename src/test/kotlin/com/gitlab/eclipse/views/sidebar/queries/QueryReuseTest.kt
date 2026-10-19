package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.views.sidebar.Slot
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

private fun q(id: String, json: String) = CustomQuery(id, JsonParser.parseString(json).asJsonObject)

private val plan = QueryPlan.Run("/issues", mapOf("state" to "opened"), null)
private val otherPlan = QueryPlan.Run("/issues", mapOf("state" to "closed"), null)

private fun items() = QueryOutcome.Issues(emptyList(), truncated = false)

/** A cache holding [outcome] for [query] as last settled with [executed]. */
private fun cacheWith(query: CustomQuery, outcome: QueryOutcome, executed: QueryPlan.Run? = plan) =
  CustomQueryResultCache().apply {
    put(query.id, QueryFingerprints.config(query), executed?.let(QueryFingerprints::request), outcome)
  }

class QueryReuseTest : StringSpec({
  "stage (a): an unchanged config print with a settled previous outcome is settled with that outcome" {
    val old = q("a", """{"name":"A","type":"issues"}""")
    val outcome = items()
    val renamed = q("a", """{"name":"Renamed","type":"issues","noItemText":"none"}""")
    val reuse = cacheWith(old, outcome).reuseFor(listOf(renamed), mapOf("a" to Slot.Settled(outcome)))
    reuse.settled["a"] shouldBeSameInstanceAs outcome
    reuse.isSettled("a") shouldBe true
  }

  "stage (a): a non-list outcome (failure, message) is reused too" {
    val old = q("a", """{"type":"issues"}""")
    val failed = QueryOutcome.Failed(QueryFailureKind.GENERIC)
    val reuse = cacheWith(old, failed, executed = null).reuseFor(listOf(old), mapOf("a" to Slot.Settled(failed)))
    reuse.settled["a"] shouldBeSameInstanceAs failed
  }

  "stage (a): a previous outcome still Pending is not reused (Loading)" {
    val old = q("a", """{"type":"issues"}""")
    val reuse = cacheWith(old, items()).reuseFor(listOf(old), mapOf("a" to Slot.Pending))
    reuse.isSettled("a") shouldBe false
  }

  "stage (a): a changed filter, a changed target or a new id is not reused" {
    val old = q("a", """{"type":"issues","state":"opened"}""")
    val outcome = items()
    val cache = cacheWith(old, outcome)
    val previous = mapOf("a" to Slot.Settled(outcome))
    cache.reuseFor(listOf(q("a", """{"type":"issues","state":"closed"}""")), previous).settled.shouldBeEmpty()
    cache.reuseFor(listOf(q("a", """{"type":"issues","state":"opened","target":"project"}""")), previous)
      .settled.shouldBeEmpty()
    cache.reuseFor(listOf(q("b", """{"type":"issues","state":"opened"}""")), previous).settled.shouldBeEmpty()
  }

  "stage (b): the same request with a cached list outcome settles with that outcome, without fetching" {
    val old = q("a", """{"type":"issues","state":"opened"}""")
    val outcome = items()
    val changed = q("a", """{"type":"issues","state":"opened","unknownKey":1}""")
    val reuse = cacheWith(old, outcome).reuseFor(listOf(changed), mapOf("a" to Slot.Settled(outcome)))
    reuse.isSettled("a") shouldBe false
    reuse.outcomeFor("a", plan) shouldBeSameInstanceAs outcome
    reuse.outcomeFor("a", otherPlan).shouldBeNull()
  }

  "stage (b): a cached failure or a query that sent nothing is fetched again" {
    val old = q("a", """{"type":"issues"}""")
    val failed = cacheWith(old, QueryOutcome.Failed(QueryFailureKind.TIMED_OUT))
      .reuseFor(listOf(old), mapOf("a" to Slot.Pending))
    failed.outcomeFor("a", plan).shouldBeNull()
    val unsent = cacheWith(old, items(), executed = null).reuseFor(listOf(old), mapOf("a" to Slot.Pending))
    unsent.outcomeFor("a", plan).shouldBeNull()
  }

  "the snapshot is detached from the cache: a later clear does not change it" {
    val old = q("a", """{"type":"issues"}""")
    val outcome = items()
    val cache = cacheWith(old, outcome)
    val reuse = cache.reuseFor(listOf(old), mapOf("a" to Slot.Pending))
    cache.clear()
    reuse.outcomeFor("a", plan) shouldBeSameInstanceAs outcome
  }

  "QueryReuse.None reuses nothing" {
    QueryReuse.None.isSettled("a") shouldBe false
    QueryReuse.None.outcomeFor("a", plan).shouldBeNull()
  }

  "carryOverUnchanged keeps the previous instance of a byte-for-byte unchanged query, by id" {
    val keep = q("a", """{"name":"A","type":"issues"}""")
    val renamedBefore = q("b", """{"name":"B","type":"issues"}""")
    val previous = listOf(keep, renamedBefore)
    val next = listOf(
      q("b", """{"name":"B2","type":"issues"}"""),
      q("a", """{"type":"issues","name":"A"}"""),
      q("c", """{"name":"C"}"""),
    )
    val merged = carryOverUnchanged(previous, next)
    merged.map { it.id } shouldContainExactly listOf("b", "a", "c")
    merged[1] shouldBeSameInstanceAs keep
    merged[0] shouldNotBeSameInstanceAs renamedBefore
    merged[2] shouldBeSameInstanceAs next[2]
  }

  "carryOverUnchanged compares invalid (non-object) entries too" {
    val oldInvalid = CustomQuery("entry-0", JsonObject(), JsonPrimitive(5))
    carryOverUnchanged(listOf(oldInvalid), listOf(CustomQuery("entry-0", JsonObject(), JsonPrimitive(5))))
      .single() shouldBeSameInstanceAs oldInvalid
    carryOverUnchanged(listOf(oldInvalid), listOf(CustomQuery("entry-0", JsonObject(), JsonPrimitive(6))))
      .single() shouldNotBeSameInstanceAs oldInvalid
  }
})
