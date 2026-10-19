package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryValidator
import com.gitlab.eclipse.views.sidebar.queries.ProblemSeverity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class CustomQueryFormModelTest : StringSpec({
  fun stored(json: String, id: String = "q1"): CustomQuery = CustomQuery(id, JsonParser.parseString(json).asJsonObject)

  fun obj(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

  fun jsonArray(vararg items: String): JsonArray = JsonArray().also { array -> items.forEach { array.add(it) } }

  val textFields = listOf(
    "name", "milestone", "excludeMilestone", "author", "excludeAuthor", "assignee", "excludeAssignee", "reviewer",
    "search", "excludeSearch", "createdAfter", "createdBefore", "updatedAfter", "updatedBefore", "noItemText",
  )
  val enumFields = mapOf(
    "type" to "issues",
    "target" to "instance",
    "scope" to "created_by_me",
    "state" to "closed",
    "searchIn" to "title",
    "excludeSearchIn" to "description",
    "orderBy" to "updated_at",
    "sort" to "asc",
  )

  "a new query starts as an opened project merge request query" {
    val model = CustomQueryFormModel(null)
    model.get("type") shouldBe "merge_requests"
    model.get("target") shouldBe "project"
    model.get("state") shouldBe "opened"
    model.get("name") shouldBe ""
    model.toQuery("new").json shouldBe obj("""{"type":"merge_requests","target":"project","state":"opened"}""")
  }

  "text and enum fields round-trip as their wire text" {
    val model = CustomQueryFormModel(null)
    textFields.forEach { model.set(it, "v-$it") }
    enumFields.forEach { (field, value) -> model.set(field, value) }
    val json = model.toQuery("x").json
    textFields.forEach { json.get(it) shouldBe JsonPrimitive("v-$it") }
    enumFields.forEach { (field, value) -> json.get(field) shouldBe JsonPrimitive(value) }
    val reread = CustomQueryFormModel(model.toQuery("x"))
    textFields.forEach { reread.get(it) shouldBe "v-$it" }
    enumFields.forEach { (field, value) -> reread.get(field) shouldBe value }
  }

  "labels are comma-separated text trimmed with empties dropped" {
    val model = CustomQueryFormModel(null)
    model.set("labels", " a , b,, ")
    model.set("excludeLabels", "x")
    val json = model.toQuery("x").json
    json.get("labels") shouldBe jsonArray("a", "b")
    json.get("excludeLabels") shouldBe jsonArray("x")
    CustomQueryFormModel(model.toQuery("x")).get("labels") shouldBe "a, b"
  }

  "labels text with only separators removes the key" {
    val model = CustomQueryFormModel(stored("""{"labels":["a"]}"""))
    model.set("labels", " , ")
    model.toQuery("q1").json.has("labels") shouldBe false
  }

  "max results is integer text and empty means absent" {
    val model = CustomQueryFormModel(stored("""{"maxResults":20}"""))
    model.get("maxResults") shouldBe "20"
    model.set("maxResults", " 50 ")
    model.toQuery("q1").json.get("maxResults") shouldBe JsonPrimitive(50)
    model.set("maxResults", "")
    model.toQuery("q1").json.has("maxResults") shouldBe false
    model.get("maxResults") shouldBe ""
  }

  "max results that is not a whole number is kept as text and reported by validation" {
    val model = CustomQueryFormModel(stored("""{"name":"N","maxResults":20}"""))
    model.set("maxResults", "1.5")
    model.toQuery("q1").json.get("maxResults") shouldBe JsonPrimitive("1.5")
    model.problems(listOf("N")).map { it.field } shouldBe listOf("maxResults")
  }

  "draft is tri-state yes/no text" {
    val model = CustomQueryFormModel(null)
    model.get("draft") shouldBe ""
    model.set("draft", "yes")
    model.toQuery("x").json.get("draft") shouldBe JsonPrimitive("yes")
    model.set("draft", "no")
    model.get("draft") shouldBe "no"
    model.set("draft", "")
    model.toQuery("x").json.has("draft") shouldBe false
  }

  "draft shows a stored wip when only wip is set" {
    CustomQueryFormModel(stored("""{"wip":"yes"}""")).get("draft") shouldBe "yes"
  }

  "draft shows wip when wip and draft conflict, as the request does" {
    CustomQueryFormModel(stored("""{"wip":"no","draft":"yes"}""")).get("draft") shouldBe "no"
  }

  "draft ignores a blank or non-text wip like the request does" {
    CustomQueryFormModel(stored("""{"wip":" ","draft":"yes"}""")).get("draft") shouldBe "yes"
    CustomQueryFormModel(stored("""{"wip":1,"draft":"no"}""")).get("draft") shouldBe "no"
  }

  "changing draft writes draft and removes wip" {
    val model = CustomQueryFormModel(stored("""{"wip":"yes","draft":"yes"}"""))
    model.set("draft", "no")
    model.toQuery("q1").json shouldBe obj("""{"draft":"no"}""")
  }

  "clearing draft removes both draft and wip" {
    val model = CustomQueryFormModel(stored("""{"wip":"yes"}"""))
    model.set("draft", "")
    model.toQuery("q1").json shouldBe obj("""{}""")
  }

  "an untouched draft keeps a stored wip byte for byte" {
    val original = """{"wip":"yes","draft":"no","name":"N"}"""
    val model = CustomQueryFormModel(stored(original))
    model.set("draft", model.get("draft"))
    model.set("name", "M")
    model.toQuery("q1").json shouldBe obj("""{"wip":"yes","draft":"no","name":"M"}""")
  }

  "confidential is tri-state yes/no mapped to a boolean" {
    val model = CustomQueryFormModel(stored("""{"type":"issues","confidential":false}"""))
    model.get("confidential") shouldBe "no"
    model.set("confidential", "yes")
    model.toQuery("q1").json.get("confidential") shouldBe JsonPrimitive(true)
    model.get("confidential") shouldBe "yes"
    model.set("confidential", "")
    model.toQuery("q1").json.has("confidential") shouldBe false
  }

  "blank text removes the field" {
    val model = CustomQueryFormModel(stored("""{"name":"N","search":"x","orderBy":"updated_at"}"""))
    model.set("search", "  ")
    model.set("orderBy", "")
    model.toQuery("q1").json shouldBe obj("""{"name":"N"}""")
  }

  "unknown keys of the original are preserved" {
    val model = CustomQueryFormModel(stored("""{"name":"N","reportTypes":["sast"],"custom":{"a":1}}"""))
    model.set("name", "M")
    model.toQuery("q1").json shouldBe obj("""{"name":"M","reportTypes":["sast"],"custom":{"a":1}}""")
  }

  "setting a field to its shown text leaves the stored value untouched" {
    val original = """{"labels":[" a ","b"],"maxResults":20.0,"confidential":true,"name":" N "}"""
    val model = CustomQueryFormModel(stored(original))
    listOf("labels", "maxResults", "confidential", "name").forEach { model.set(it, model.get(it)) }
    model.toQuery("q1").json shouldBe obj(original)
  }

  "the original query is not mutated" {
    val query = stored("""{"name":"N"}""")
    CustomQueryFormModel(query).set("name", "M")
    query.json shouldBe obj("""{"name":"N"}""")
  }

  "toQuery uses the given id and has no invalid element" {
    val query = CustomQueryFormModel(stored("""{"name":"N"}""")).toQuery("abc")
    query.id shouldBe "abc"
    query.invalidElement.shouldBeNull()
  }

  "an invalid element stays untouched while the form is not edited" {
    val invalid = CustomQuery("bad", JsonObject(), JsonPrimitive(42))
    CustomQueryFormModel(invalid).toQuery("bad").invalidElement shouldBe JsonPrimitive(42)
    val edited = CustomQueryFormModel(invalid).apply { set("name", "N") }.toQuery("bad")
    edited.invalidElement.shouldBeNull()
    edited.json shouldBe obj("""{"name":"N"}""")
  }

  "issue-only fields are disabled for merge requests and keep their values" {
    val model = CustomQueryFormModel(stored("""{"type":"merge_requests","excludeAuthor":"bob"}"""))
    ISSUE_ONLY.forEach { model.isEnabled(it) shouldBe false }
    MR_ONLY.forEach { model.isEnabled(it) shouldBe true }
    model.get("excludeAuthor") shouldBe "bob"
    model.toQuery("q1").json.get("excludeAuthor") shouldBe JsonPrimitive("bob")
  }

  "merge-request-only fields are disabled for issues" {
    val model = CustomQueryFormModel(stored("""{"type":"issues","reviewer":"bob"}"""))
    MR_ONLY.forEach { model.isEnabled(it) shouldBe false }
    ISSUE_ONLY.forEach { model.isEnabled(it) shouldBe true }
    model.get("reviewer") shouldBe "bob"
  }

  "an absent type enables fields like merge requests" {
    val model = CustomQueryFormModel(stored("""{"name":"N"}"""))
    model.isEnabled("reviewer") shouldBe true
    model.isEnabled("confidential") shouldBe false
  }

  "enablement follows a type change" {
    val model = CustomQueryFormModel(null)
    model.isEnabled("confidential") shouldBe false
    model.set("type", "issues")
    model.isEnabled("confidential") shouldBe true
    model.isEnabled("draft") shouldBe false
  }

  "common fields are always enabled" {
    val model = CustomQueryFormModel(stored("""{"type":"issues"}"""))
    listOf("name", "type", "target", "scope", "labels", "search", "maxResults").forEach {
      model.isEnabled(it) shouldBe true
    }
  }

  "the type of an unsupported query is read-only" {
    val model = CustomQueryFormModel(stored("""{"type":"epics"}"""))
    model.get("type") shouldBe "epics"
    model.isEnabled("type") shouldBe false
  }

  "problems delegate to the validator with the current values" {
    val model = CustomQueryFormModel(stored("""{"name":"N","type":"issues","reviewer":"bob"}"""))
    model.set("state", "bogus")
    val expected = CustomQueryValidator.validate(model.toQuery("q1"), listOf("N", "N"))
    val problems = model.problems(listOf("N", "N"))
    problems shouldBe expected
    problems.map { it.severity to it.field } shouldBe listOf(
      ProblemSeverity.ERROR to "state",
      ProblemSeverity.WARNING to "reviewer",
      ProblemSeverity.WARNING to "name",
    )
  }

  "a valid new query has no problems once named" {
    val model = CustomQueryFormModel(null)
    model.set("name", "Mine")
    model.problems(listOf("Mine")).shouldBeEmpty()
  }

  "an unknown field name is rejected" {
    val model = CustomQueryFormModel(null)
    shouldThrow<IllegalArgumentException> { model.get("bogus") }
    shouldThrow<IllegalArgumentException> { model.set("bogus", "x") }
    shouldThrow<IllegalArgumentException> { model.isEnabled("bogus") }
  }

  "the field list names every form field" {
    CustomQueryFormModel.FIELDS.size shouldBe 28
    (ISSUE_ONLY + MR_ONLY).all { it in CustomQueryFormModel.FIELDS } shouldBe true
  }
}) {
  companion object {
    val ISSUE_ONLY = listOf(
      "excludeLabels",
      "excludeMilestone",
      "excludeAuthor",
      "excludeAssignee",
      "excludeSearch",
      "excludeSearchIn",
      "confidential",
    )
    val MR_ONLY = listOf("reviewer", "draft")
  }
}
