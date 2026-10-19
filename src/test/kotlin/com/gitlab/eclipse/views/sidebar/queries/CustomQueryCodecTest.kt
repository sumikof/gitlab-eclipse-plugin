package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonParser
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs

class CustomQueryCodecTest : StringSpec({
  fun unreadable(text: String): QueryConfig.Unreadable =
    CustomQueryCodec.decode(text).shouldBeInstanceOf<QueryConfig.Unreadable>()

  fun custom(text: String): QueryConfig.Custom = CustomQueryCodec.decode(text).shouldBeInstanceOf<QueryConfig.Custom>()

  "null and blank values decode to the built-in defaults" {
    CustomQueryCodec.decode(null) shouldBeSameInstanceAs QueryConfig.Defaults
    CustomQueryCodec.decode("  ") shouldBeSameInstanceAs QueryConfig.Defaults
  }

  "a version 1 object decodes to its queries" {
    val config = custom("""{"version":1,"queries":[{"id":"a","name":"A","type":"issues"}]}""")
    config.queries.size shouldBe 1
    config.queries[0].id shouldBe "a"
    config.queries[0].name shouldBe "A"
    config.queries[0].typeWire shouldBe "issues"
  }

  "a bare array is accepted as version 1" {
    val config = custom("""[{"name":"X"}]""")
    config.queries.map { it.id } shouldBe listOf("entry-0")
  }

  "a version above 1 is unreadable and keeps the raw text" {
    val text = """{"version":2,"queries":[]}"""
    val config = unreadable(text)
    config.reason shouldBe UnreadableReason.UNSUPPORTED_VERSION
    config.rawText shouldBe text
    config.queries.shouldBeEmpty()
  }

  "a missing or non-integer version is the wrong shape" {
    unreadable("""{"queries":[]}""").reason shouldBe UnreadableReason.WRONG_SHAPE
    unreadable("""{"version":"1","queries":[]}""").reason shouldBe UnreadableReason.WRONG_SHAPE
  }

  "unparsable JSON is malformed and a scalar top level is the wrong shape" {
    unreadable("{not json").reason shouldBe UnreadableReason.MALFORMED_JSON
    unreadable("42").reason shouldBe UnreadableReason.WRONG_SHAPE
  }

  "an empty query list is a valid custom configuration" {
    custom("""{"version":1,"queries":[]}""").queries.shouldBeEmpty()
  }

  "ids are resolved from storage, by index when missing and with a suffix when duplicated" {
    val config = custom("""[{"name":"a"},{"id":"x","name":"b"},{"id":"x","name":"c"},{"id":" ","name":"d"}]""")
    config.queries.map { it.id } shouldBe listOf("entry-0", "x", "x#2", "entry-3")
  }

  "unknown keys survive a decode and encode round trip" {
    val text = """{"version":1,"queries":[{"id":"a","name":"A","foo":{"b":[1,2]},"reportTypes":["sast"]}]}"""
    val encoded = CustomQueryCodec.encode(custom(text).queries)
    JsonParser.parseString(encoded) shouldBe JsonParser.parseString(text)
  }

  "a non-object element is kept as an invalid entry and written back unchanged" {
    val config = custom("""[{"name":"A"}, 7]""")
    config.queries.size shouldBe 2
    config.queries[1].json.has(INVALID_ELEMENT_FIELD) shouldBe true
    JsonParser.parseString(CustomQueryCodec.encode(config.queries)) shouldBe
      JsonParser.parseString("""{"version":1,"queries":[{"name":"A","id":"entry-0"},7]}""")
  }

  "the model reads strings only from string primitives and maps wire values" {
    val query = custom("""[{"name":"  N  ","type":7,"scope":null}]""").queries[0]
    query.name shouldBe "N"
    query.typeWire shouldBe null
    query.string("scope") shouldBe null
    CustomQueryType.fromWire("merge_requests") shouldBe CustomQueryType.MERGE_REQUESTS
    CustomQueryType.fromWire("epics")?.supported shouldBe false
    CustomQueryType.fromWire("nope") shouldBe null
    QueryTarget.fromWire("instance") shouldBe QueryTarget.INSTANCE
    QueryTarget.fromWire("x") shouldBe null
  }

  "decodeText rejects blank text and accepts an empty array" {
    CustomQueryCodec.decodeText("").isFailure shouldBe true
    CustomQueryCodec.decodeText("[]").getOrThrow().shouldBeEmpty()
  }
})
