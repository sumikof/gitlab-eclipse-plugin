package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun query(json: String) = CustomQuery("q", JsonParser.parseString(json).asJsonObject)

private fun config(json: String) = QueryFingerprints.config(query(json))

private fun run(path: String = "/issues", params: Map<String, String> = emptyMap(), max: Int? = null) =
  QueryFingerprints.request(QueryPlan.Run(path, params, max))

class CustomQueryResultCacheTest : StringSpec({
  "config print ignores name, noItemText, id and key order" {
    val base = config("""{"name":"A","noItemText":"x","type":"issues","state":"opened"}""")
    config("""{"name":"B","state":"opened","type":"issues"}""") shouldBe base
    config("""{"type":"issues","noItemText":"y","state":"opened","id":"zz"}""") shouldBe base
  }
  "config print differs when a filter changes" {
    config("""{"type":"issues","state":"opened"}""") shouldNotBe config("""{"type":"issues","state":"closed"}""")
  }
  "config print sorts nested object keys but keeps array order" {
    config("""{"a":{"x":1,"y":2},"l":["p","q"]}""") shouldBe config("""{"l":["p","q"],"a":{"y":2,"x":1}}""")
    config("""{"l":["p","q"]}""") shouldNotBe config("""{"l":["q","p"]}""")
  }
  "config print of an invalid entry never matches a valid one" {
    val invalid = QueryFingerprints.config(CustomQuery("q", JsonObject(), JsonPrimitive(5)))
    invalid shouldNotBe config("{}")
  }
  "request print covers path, params and maxItems regardless of param order" {
    run(params = linkedMapOf("a" to "1", "b" to "2")) shouldBe run(params = linkedMapOf("b" to "2", "a" to "1"))
    run(path = "/issues") shouldNotBe run(path = "/merge_requests")
    run(params = mapOf("a" to "1")) shouldNotBe run(params = mapOf("a" to "2"))
    run(max = 5) shouldNotBe run(max = null)
    run(params = mapOf("a" to "1,b=2")) shouldNotBe run(params = mapOf("a" to "1", "b" to "2"))
  }
  "reusableOutcome is null for unknown id or changed print, else the stored outcome" {
    val cache = CustomQueryResultCache()
    val outcome = QueryOutcome.Issues(emptyList(), false)
    cache.reusableOutcome("q", "c").shouldBeNull()
    cache.put("q", "c", "r", outcome)
    cache.reusableOutcome("q", "c") shouldBe outcome
    cache.reusableOutcome("q", "other").shouldBeNull()
    cache.reusableOutcome("x", "c").shouldBeNull()
  }
  "isRequestUnchanged is true only for an equal print on a successful list outcome" {
    val cache = CustomQueryResultCache()
    cache.put("i", "c", "r", QueryOutcome.Issues(emptyList(), false))
    cache.put("m", "c", "r", QueryOutcome.MergeRequests(emptyList(), true))
    cache.put("f", "c", "r", QueryOutcome.Failed(QueryFailureKind.GENERIC))
    cache.put("n", "c", null, QueryOutcome.NeedsProject)
    cache.isRequestUnchanged("i", "r") shouldBe true
    cache.isRequestUnchanged("m", "r") shouldBe true
    cache.isRequestUnchanged("i", "other") shouldBe false
    cache.isRequestUnchanged("f", "r") shouldBe false
    cache.isRequestUnchanged("n", "r") shouldBe false
    cache.isRequestUnchanged("none", "r") shouldBe false
  }
  "put replaces an earlier entry" {
    val cache = CustomQueryResultCache()
    cache.put("q", "c1", "r1", QueryOutcome.Issues(emptyList(), false))
    cache.put("q", "c2", "r2", QueryOutcome.Unsupported)
    cache.reusableOutcome("q", "c1").shouldBeNull()
    cache.reusableOutcome("q", "c2") shouldBe QueryOutcome.Unsupported
  }
  "retainOnly evicts other ids and clear empties the cache" {
    val cache = CustomQueryResultCache()
    cache.put("a", "c", "r", QueryOutcome.Unsupported)
    cache.put("b", "c", "r", QueryOutcome.Unsupported)
    cache.retainOnly(setOf("a"))
    cache.reusableOutcome("a", "c") shouldNotBe null
    cache.reusableOutcome("b", "c").shouldBeNull()
    cache.clear()
    cache.reusableOutcome("a", "c").shouldBeNull()
  }
})
