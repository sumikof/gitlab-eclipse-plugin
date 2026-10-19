package com.gitlab.eclipse.views.sidebar.queries

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private const val ISSUES = "issues"
private const val MRS = "merge_requests"
private const val ME = "<current_user>"

private fun parse(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

private fun base(type: String) = parse("""{"name":"Q","type":"$type","target":"instance"}""")

private fun JsonObject.set(field: String, json: String) = add(field, JsonParser.parseString(json))

private fun plan(json: JsonObject, project: QueryProject? = null, user: String? = "alice") =
  CustomQueryRequestBuilder.plan(CustomQuery("q", json), project, user)

private fun paramsOf(json: JsonObject): Map<String, String> {
  val plan = plan(json)
  plan.shouldBeInstanceOf<QueryPlan.Run>()
  return plan.params
}

/** One row of design §3: [expected] maps a param key to its value, or to null when the param must be absent. */
private class MappingRow(
  val title: String,
  val type: String,
  val expected: Map<String, String?>,
  val mutate: JsonObject.() -> Unit,
)

private fun bothTypes(title: String, expected: Map<String, String?>, mutate: JsonObject.() -> Unit) =
  listOf(MappingRow("$title (issues)", ISSUES, expected, mutate), MappingRow("$title (MRs)", MRS, expected, mutate))

private val rows: List<MappingRow> = buildList {
  addAll(bothTypes("state default", mapOf("state" to "opened")) {})
  addAll(bothTypes("blank state", mapOf("state" to "opened")) { addProperty("state", "  ") })
  addAll(bothTypes("state trimmed", mapOf("state" to "closed")) { addProperty("state", " closed ") })
  addAll(bothTypes("scope default", mapOf("scope" to "all")) {})
  addAll(bothTypes("blank scope", mapOf("scope" to "all")) { addProperty("scope", " ") })
  addAll(bothTypes("scope dismissed", mapOf("scope" to "all")) { addProperty("scope", "dismissed") })
  addAll(bothTypes("scope created_by_me", mapOf("scope" to "created_by_me")) { addProperty("scope", "created_by_me") })
  addAll(bothTypes("scope trimmed", mapOf("scope" to "assigned_to_me")) { addProperty("scope", " assigned_to_me ") })
  addAll(bothTypes("orderBy", mapOf("order_by" to "updated_at")) { addProperty("orderBy", " updated_at") })
  addAll(bothTypes("blank orderBy", mapOf("order_by" to null)) { addProperty("orderBy", "") })
  addAll(bothTypes("sort", mapOf("sort" to "desc")) { addProperty("sort", " desc") })
  addAll(bothTypes("blank sort", mapOf("sort" to null)) { addProperty("sort", " ") })
  addAll(bothTypes("labels", mapOf("labels" to "a,b c")) { set("labels", """[" a ", "", "  ", "b c"]""") })
  addAll(bothTypes("empty labels", mapOf("labels" to null)) { set("labels", """["", " "]""") })
  addAll(bothTypes("milestone", mapOf("milestone" to "v1")) { addProperty("milestone", " v1 ") })
  addAll(bothTypes("blank milestone", mapOf("milestone" to null)) { addProperty("milestone", " ") })
  addAll(bothTypes("author", mapOf("author_username" to "bob")) { addProperty("author", "bob") })
  addAll(bothTypes("author current user", mapOf("author_username" to "alice")) { addProperty("author", " $ME ") })
  addAll(bothTypes("blank author", mapOf("author_username" to null)) { addProperty("author", "") })
  addAll(bothTypes("search", mapOf("search" to "x y")) { addProperty("search", " x y ") })
  addAll(bothTypes("searchIn all", mapOf("in" to "title,description")) { addProperty("searchIn", "all") })
  addAll(bothTypes("searchIn title", mapOf("in" to "title")) { addProperty("searchIn", "title") })
  addAll(bothTypes("no searchIn", mapOf("in" to null)) {})
  this += bothTypes("createdAfter", mapOf("created_after" to "2024-01-02")) {
    addProperty("createdAfter", "2024-01-02")
  }
  this += bothTypes("createdBefore", mapOf("created_before" to "2024-01-02T10:00:00Z")) {
    addProperty("createdBefore", "2024-01-02T10:00:00Z")
  }
  this += bothTypes("updatedAfter", mapOf("updated_after" to "2024-01-03")) {
    addProperty("updatedAfter", "2024-01-03")
  }
  this += bothTypes("updatedBefore", mapOf("updated_before" to "2024-01-04")) {
    addProperty("updatedBefore", "2024-01-04")
  }
  addAll(bothTypes("maxResults is not a param", mapOf("per_page" to null)) { addProperty("maxResults", 5) })
  this += bothTypes("vulnerability fields ignored", mapOf("report_type" to null, "severity" to null)) {
    set("reportTypes", """["sast"]""")
    set("severityLevels", """["high"]""")
  }
  listOf("Any" to "Any", "any" to "Any", " NONE " to "None", "None" to "None").forEach { (value, normalised) ->
    this += bothTypes("assignee $value", mapOf("assignee_id" to normalised, "assignee_username" to null)) {
      addProperty("assignee", value)
    }
  }
  this += MappingRow("assignee (issues)", ISSUES, mapOf("assignee_username" to "bob", "assignee_id" to null)) {
    addProperty("assignee", " bob ")
  }
  this += MappingRow("assignee (MRs)", MRS, mapOf("assignee_username[]" to "bob", "assignee_username" to null)) {
    addProperty("assignee", "bob")
  }
  this += MappingRow("assignee current user (MRs)", MRS, mapOf("assignee_username[]" to "alice")) {
    addProperty("assignee", ME)
  }
  this += MappingRow("assignee current user (issues)", ISSUES, mapOf("assignee_username" to "alice")) {
    addProperty("assignee", ME)
  }
  add(MappingRow("reviewer (MRs)", MRS, mapOf("reviewer_username" to "bob")) { addProperty("reviewer", " bob ") })
  this += MappingRow("reviewer current user (MRs)", MRS, mapOf("reviewer_username" to "alice")) {
    addProperty("reviewer", ME)
  }
  this += MappingRow("reviewer any (MRs)", MRS, mapOf("reviewer_id" to "Any", "reviewer_username" to null)) {
    addProperty("reviewer", "aNy")
  }
  add(MappingRow("reviewer none (MRs)", MRS, mapOf("reviewer_id" to "None")) { addProperty("reviewer", "none") })
  this += MappingRow("reviewer (issues)", ISSUES, mapOf("reviewer_username" to null, "reviewer_id" to null)) {
    addProperty("reviewer", "bob")
  }
  add(MappingRow("wip (MRs)", MRS, mapOf("wip" to "yes", "draft" to null)) { addProperty("wip", " yes") })
  add(MappingRow("draft (MRs)", MRS, mapOf("wip" to "no", "draft" to null)) { addProperty("draft", "no") })
  this += MappingRow("wip wins over draft (MRs)", MRS, mapOf("wip" to "yes", "draft" to null)) {
    addProperty("wip", "yes")
    addProperty("draft", "no")
  }
  this += MappingRow("blank wip falls back to draft (MRs)", MRS, mapOf("wip" to "no")) {
    addProperty("wip", " ")
    addProperty("draft", "no")
  }
  add(MappingRow("wip (issues)", ISSUES, mapOf("wip" to null, "draft" to null)) { addProperty("wip", "yes") })
  add(MappingRow("draft (issues)", ISSUES, mapOf("wip" to null, "draft" to null)) { addProperty("draft", "yes") })
  this += MappingRow("confidential true (issues)", ISSUES, mapOf("confidential" to "true")) {
    addProperty("confidential", true)
  }
  this += MappingRow("confidential false (issues)", ISSUES, mapOf("confidential" to "false")) {
    addProperty("confidential", false)
  }
  add(MappingRow("confidential (MRs)", MRS, mapOf("confidential" to null)) { addProperty("confidential", true) })
  val issueOnly = listOf(
    Triple("excludeMilestone", " m1 ", "not[milestone]" to "m1"),
    Triple("excludeAuthor", "bob", "not[author_username]" to "bob"),
    Triple("excludeAuthor", ME, "not[author_username]" to "alice"),
    Triple("excludeAssignee", "carol", "not[assignee_username]" to "carol"),
    Triple("excludeAssignee", " $ME", "not[assignee_username]" to "alice"),
    Triple("excludeSearch", "s t", "not[search]" to "s t"),
    Triple("excludeSearchIn", "all", "not[in]" to "title,description"),
    Triple("excludeSearchIn", "description", "not[in]" to "description"),
  )
  issueOnly.forEach { (field, value, expected) ->
    add(MappingRow("$field=$value (issues)", ISSUES, mapOf(expected)) { addProperty(field, value) })
    add(MappingRow("$field=$value (MRs)", MRS, mapOf(expected.first to null)) { addProperty(field, value) })
    add(MappingRow("blank $field (issues)", ISSUES, mapOf(expected.first to null)) { addProperty(field, " ") })
  }
  this += MappingRow("excludeLabels (issues)", ISSUES, mapOf("not[labels]" to "a,b")) {
    set("excludeLabels", """["a", " b"]""")
  }
  add(MappingRow("excludeLabels (MRs)", MRS, mapOf("not[labels]" to null)) { set("excludeLabels", """["a"]""") })
  add(MappingRow("empty excludeLabels (issues)", ISSUES, mapOf("not[labels]" to null)) { set("excludeLabels", "[]") })
}

private fun default(id: String) = CustomQueryDefaults.queries.single { it.id == id }

class CustomQueryRequestBuilderTest : StringSpec({
  rows.forEach { row ->
    "§3 ${row.title}" {
      val params = paramsOf(base(row.type).apply(row.mutate))
      row.expected.forEach { (key, value) ->
        if (value == null) params shouldNotContainKey key else params[key] shouldBe value
      }
    }
  }

  "params start with state and scope, then author, then the rest" {
    val json = base(MRS).apply {
      addProperty("search", "s")
      addProperty("author", "bob")
      addProperty("scope", "all")
      addProperty("state", "all")
    }
    paramsOf(json).keys.toList() shouldBe listOf("state", "scope", "author_username", "search")
  }

  "default #1 is exactly the old issues-assigned request" {
    CustomQueryRequestBuilder.plan(default("default-issues-assigned"), null, null) shouldBe
      QueryPlan.Run("/issues", mapOf("state" to "opened", "scope" to "assigned_to_me"), null)
  }

  "default #3 is exactly the old MRs-assigned request" {
    CustomQueryRequestBuilder.plan(default("default-mrs-assigned"), null, null) shouldBe
      QueryPlan.Run("/merge_requests", mapOf("state" to "opened", "scope" to "assigned_to_me"), null)
  }

  "default #4 reviews for the current user with scope all" {
    CustomQueryRequestBuilder.plan(default("default-mrs-reviewing"), null, "alice") shouldBe
      QueryPlan.Run(
        "/merge_requests",
        mapOf("state" to "opened", "scope" to "all", "reviewer_username" to "alice"),
        null,
      )
  }

  "default #6 targets the project with 20 items" {
    CustomQueryRequestBuilder.plan(default("default-project-mrs"), QueryProject("42"), null) shouldBe
      QueryPlan.Run("/projects/42/merge_requests", mapOf("state" to "opened", "scope" to "all"), 20)
  }

  "project issues path uses the encoded id verbatim" {
    val json = base(ISSUES).apply { addProperty("target", "project") }
    val plan = plan(json, QueryProject("group%2Fsub"))
    plan.shouldBeInstanceOf<QueryPlan.Run>()
    plan.path shouldBe "/projects/group%2Fsub/issues"
  }

  "absent type and target mean project merge requests" {
    val plan = plan(parse("""{"name":"Q"}"""), QueryProject("7"))
    plan.shouldBeInstanceOf<QueryPlan.Run>()
    plan.path shouldBe "/projects/7/merge_requests"
  }

  "blank type and target mean project merge requests" {
    val plan = plan(parse("""{"name":"Q","type":" ","target":""}"""), QueryProject("7"))
    plan.shouldBeInstanceOf<QueryPlan.Run>()
    plan.path shouldBe "/projects/7/merge_requests"
  }

  "instance issues path" {
    val plan = plan(base(ISSUES))
    plan.shouldBeInstanceOf<QueryPlan.Run>()
    plan.path shouldBe "/issues"
  }

  "maxResults becomes maxItems, also when written as 20.0" {
    (plan(base(MRS).apply { addProperty("maxResults", 5) }) as QueryPlan.Run).maxItems shouldBe 5
    (plan(base(MRS).apply { set("maxResults", "20.0") }) as QueryPlan.Run).maxItems shouldBe 20
    (plan(base(MRS)) as QueryPlan.Run).maxItems shouldBe null
  }

  "unsupported types are not run" {
    listOf("snippets", "epics", "vulnerabilities").forEach { type ->
      plan(base(type)) shouldBe QueryPlan.Unsupported
    }
  }

  "a project query without a project needs one" {
    plan(base(MRS).apply { addProperty("target", "project") }, project = null) shouldBe QueryPlan.NeedsProject
    plan(parse("""{"name":"Q"}"""), project = null) shouldBe QueryPlan.NeedsProject
  }

  "an invalid query yields its first error" {
    val json = base(MRS).apply {
      addProperty("name", " ")
      addProperty("scope", "nope")
    }
    plan(json) shouldBe QueryPlan.Invalid(CustomQueryValidator.validate(CustomQuery("q", json)).first())
    (plan(json) as QueryPlan.Invalid).problem.field shouldBe "name"
  }

  "an invalid element yields Invalid" {
    val query = CustomQuery("q", JsonObject(), JsonParser.parseString("3"))
    CustomQueryRequestBuilder.plan(query, null, null).shouldBeInstanceOf<QueryPlan.Invalid>()
  }

  "warnings do not make a query invalid" {
    plan(base(MRS).apply { addProperty("confidential", true) }).shouldBeInstanceOf<QueryPlan.Run>()
  }

  "Unicode and reserved characters are kept verbatim" {
    val value = "ä & b#c+d e%20/f 日本"
    val params = paramsOf(
      base(ISSUES).apply {
        addProperty("search", value)
        set("labels", """["x&y", "日本"]""")
      },
    )
    params["search"] shouldBe value
    params["labels"] shouldBe "x&y,日本"
  }

  "usesCurrentUser looks at the five user fields, trimmed" {
    fun uses(field: String, value: String) =
      CustomQueryRequestBuilder.usesCurrentUser(CustomQuery("q", base(MRS).apply { addProperty(field, value) }))
    listOf("author", "assignee", "reviewer", "excludeAuthor", "excludeAssignee").forEach { field ->
      uses(field, " $ME ") shouldBe true
    }
    uses("search", ME) shouldBe false
    uses("author", "bob") shouldBe false
  }

  "a current-user query without a resolved username is a caller error" {
    shouldThrow<IllegalStateException> { plan(base(MRS).apply { addProperty("reviewer", ME) }, user = null) }
  }
})
