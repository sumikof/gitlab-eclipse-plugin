package com.gitlab.eclipse.preferences.queries

import com.gitlab.eclipse.views.sidebar.queries.CustomQuery
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryCodec
import com.gitlab.eclipse.views.sidebar.queries.CustomQueryDefaults
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The pure texts behind the SWT page and dialogs: table columns, combo bindings, "Edit as JSON…". */
class CustomQueryPageTextsTest : StringSpec({
  fun row(json: String, id: String = "q1") = CustomQuery(id, JsonParser.parseString(json).asJsonObject)

  "name: trimmed, blank shows unnamed, an ERROR adds the invalid suffix" {
    CustomQueryRowLabels.name(row("""{"name":" Mine ","type":"issues"}"""), emptyList()) shouldBe "Mine"
    CustomQueryRowLabels.name(row("""{"name":"  "}"""), emptyList()) shouldBe "(unnamed query) (invalid)"
    CustomQueryRowLabels.name(row("""{"name":"A","maxResults":0}"""), emptyList()) shouldBe "A (invalid)"
    // A duplicate name is only a WARNING.
    CustomQueryRowLabels.name(row("""{"name":"A"}"""), listOf("A", "A")) shouldBe "A"
  }

  "type and search in: absent defaults, unsupported, invalid, non-object entry" {
    CustomQueryRowLabels.type(row("""{"name":"A"}""")) shouldBe "Merge requests"
    CustomQueryRowLabels.type(row("""{"type":"issues"}""")) shouldBe "Issues"
    CustomQueryRowLabels.type(row("""{"type":"epics"}""")) shouldBe "epics (not supported)"
    CustomQueryRowLabels.type(row("""{"type":"foo"}""")) shouldBe "(invalid)"
    CustomQueryRowLabels.target(row("""{}""")) shouldBe "Current project"
    CustomQueryRowLabels.target(row("""{"target":"instance"}""")) shouldBe "All projects"
    CustomQueryRowLabels.target(row("""{"target":1}""")) shouldBe "(invalid)"
    val entry = CustomQuery("e", JsonObject(), JsonPrimitive(3))
    CustomQueryRowLabels.type(entry) shouldBe "(invalid)"
    CustomQueryRowLabels.target(entry) shouldBe "(invalid)"
    CustomQueryRowLabels.summary(entry) shouldBe ""
    CustomQueryRowLabels.name(entry, emptyList()) shouldBe "(unnamed query) (invalid)"
  }

  "summary: scope and state with runtime defaults and a filter count" {
    CustomQueryRowLabels.summary(row("""{}""")) shouldBe "all, opened"
    CustomQueryRowLabels.summary(row("""{"scope":"assigned_to_me","state":"closed","labels":["a"]}""")) shouldBe
      "assigned_to_me, closed, 1 filter"
    CustomQueryRowLabels.summary(
      row("""{"labels":[],"search":" ","author":"x","wip":"yes","draft":"no","confidential":false}"""),
    ) shouldBe "all, opened, 3 filters"
  }

  "combo: absent type shows merge requests and selecting it writes nothing" {
    val type = CustomQueryFieldSpecs.combo("type")!!
    type.items("") shouldBe listOf("Issues", "Merge requests")
    type.selectedIndex("") shouldBe 1
    type.textFor(1, "", "").shouldBeNull()
    type.textFor(0, "", "") shouldBe "issues"
    // Back to merge requests after picking issues writes it.
    type.textFor(1, "", "issues") shouldBe "merge_requests"
  }

  "combo: an unknown value is kept as an extra item; the default entry clears the field" {
    val type = CustomQueryFieldSpecs.combo("type")!!
    type.items("epics") shouldBe listOf("Issues", "Merge requests", "epics (not supported)")
    type.selectedIndex("epics") shouldBe 2
    type.textFor(2, "epics", "epics").shouldBeNull()
    val scope = CustomQueryFieldSpecs.combo("scope")!!
    scope.items("bogus").last() shouldBe "bogus"
    scope.textFor(0, "bogus", "bogus") shouldBe ""
    scope.textFor(scope.items("bogus").lastIndex, "bogus", "") shouldBe "bogus"
    val sort = CustomQueryFieldSpecs.combo("sort")!!
    // VS Code's " desc" schema default matches "desc" without being rewritten.
    sort.items(" desc") shouldBe listOf("(default)", "asc", "desc")
    sort.selectedIndex(" desc") shouldBe 2
    sort.textFor(2, " desc", " desc").shouldBeNull()
  }

  "combo: draft and confidential use Any / Yes / No; text fields have no combo" {
    val draft = CustomQueryFieldSpecs.combo("draft")!!
    draft.items("") shouldBe listOf("Any", "Yes", "No")
    draft.textFor(2, "", "") shouldBe "no"
    CustomQueryFieldSpecs.combo("confidential")!!.selectedIndex("yes") shouldBe 1
    CustomQueryFieldSpecs.combo("labels").shouldBeNull()
    CustomQueryFormModel.FIELDS.forEach { CustomQueryFieldSpecs.label(it).isNotBlank() shouldBe true }
    CustomQueryFieldSpecs.label("target") shouldBe "Search in"
  }

  "json: formatted rows parse back to the same stored value" {
    val text = CustomQueriesJsonText.format(CustomQueryDefaults.queries)
    text shouldContain "\n"
    text shouldContain "<current_user>"
    val parsed = CustomQueriesJsonText.parse(text).getOrThrow()
    CustomQueryCodec.encode(parsed) shouldBe
      CustomQueryCodec.encode(CustomQueryDefaults.queries)
  }

  "json: a bare array is accepted and failures have user-facing reasons" {
    CustomQueriesJsonText.parse("""[{"name":"A"}]""").getOrThrow().size shouldBe 1
    CustomQueriesJsonText.parse("{").exceptionOrNull()?.message shouldBe "The text is not valid JSON."
    CustomQueriesJsonText.parse("3").exceptionOrNull()?.message shouldContain "JSON array of queries"
    CustomQueriesJsonText.parse("""{"version":2,"queries":[]}""").exceptionOrNull()?.message shouldBe
      "This version of the queries format is not supported."
    CustomQueriesJsonText.parse(" ").exceptionOrNull()?.message shouldBe "Enter the queries as JSON, for example []."
  }
})
