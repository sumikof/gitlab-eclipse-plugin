package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.views.sidebar.queries.ProblemSeverity.ERROR
import com.gitlab.eclipse.views.sidebar.queries.ProblemSeverity.WARNING
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val SECRET = "zzz-secret-ish-value"

private fun parse(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

private fun query(json: JsonObject) = CustomQuery("q", json)

/** A valid MR query and a valid project issue query to mutate; each row sets or removes exactly one aspect. */
private fun issueBase() = parse("""{"name":"Q","type":"issues","target":"project","scope":"all","state":"opened"}""")

private fun mrBase() = parse("""{"name":"Q","type":"merge_requests","target":"project","scope":"all"}""")

private class Row(
  val title: String,
  val expected: Pair<ProblemSeverity, String>,
  val base: () -> JsonObject = ::issueBase,
  val mutate: JsonObject.() -> Unit,
)

private fun JsonObject.set(field: String, json: String) = add(field, JsonParser.parseString(json))

private val stringFields = listOf(
  "name", "noItemText", "milestone", "excludeMilestone", "author", "excludeAuthor", "assignee", "excludeAssignee",
  "reviewer", "search", "excludeSearch", "createdAfter", "createdBefore", "updatedAfter", "updatedBefore",
)

private val enumFields = listOf("scope", "state", "sort", "orderBy", "searchIn", "excludeSearchIn", "wip", "draft")

private val dateFields = listOf("createdAfter", "createdBefore", "updatedAfter", "updatedBefore")

/** MR-only fields are checked on the MR baseline so that only the malformed-value error is expected. */
private fun baseFor(field: String): () -> JsonObject =
  if (field in setOf("reviewer", "wip", "draft")) ::mrBase else ::issueBase

private val rows: List<Row> = buildList {
  add(Row("blank name", ERROR to "name") { addProperty("name", "   ") })
  add(Row("missing name", ERROR to "name") { remove("name") })
  add(Row("unknown type", ERROR to "type") { addProperty("type", "Issues") })
  add(Row("non-string type", ERROR to "type") { addProperty("type", 1) })
  add(Row("unknown target", ERROR to "target") { addProperty("target", "group") })
  add(Row("non-string target", ERROR to "target") { addProperty("target", true) })
  stringFields.forEach { field ->
    add(Row("non-string $field", ERROR to field, baseFor(field)) { addProperty(field, 3) })
  }
  enumFields.forEach { field ->
    add(Row("$field outside its enum", ERROR to field, baseFor(field)) { addProperty(field, SECRET) })
    add(Row("$field not a string", ERROR to field, baseFor(field)) { addProperty(field, 1) })
  }
  add(Row("scope enum is case-sensitive", ERROR to "scope") { addProperty("scope", "ALL") })
  add(Row("maxResults zero", ERROR to "maxResults") { addProperty("maxResults", 0) })
  add(Row("maxResults above 100", ERROR to "maxResults") { addProperty("maxResults", 101) })
  add(Row("maxResults fraction", ERROR to "maxResults") { set("maxResults", "20.5") })
  add(Row("maxResults string", ERROR to "maxResults") { addProperty("maxResults", "20") })
  dateFields.forEach { field ->
    add(Row("$field not ISO 8601", ERROR to field) { addProperty(field, "01/02/2024") })
    add(Row("$field impossible date", ERROR to field) { addProperty(field, "2024-02-30") })
    add(Row("$field date-time without offset", ERROR to field) { addProperty(field, "2024-01-02T10:00:00") })
  }
  listOf("labels", "excludeLabels").forEach { field ->
    add(Row("$field not an array", ERROR to field) { addProperty(field, "a,b") })
    add(Row("$field with a non-string entry", ERROR to field) { set(field, """["a", 1]""") })
    add(Row("$field entry with a comma", ERROR to field) { set(field, """["a,b"]""") })
  }
  add(Row("confidential not boolean", ERROR to "confidential") { addProperty("confidential", "true") })
  listOf("excludeLabels" to """["a"]""", "confidential" to "true").forEach { (field, json) ->
    add(Row("$field on merge requests", WARNING to field, ::mrBase) { set(field, json) })
  }
  listOf("excludeMilestone", "excludeAuthor", "excludeAssignee", "excludeSearch").forEach { field ->
    add(Row("$field on merge requests", WARNING to field, ::mrBase) { addProperty(field, "x") })
  }
  this += Row("excludeSearchIn on merge requests", WARNING to "excludeSearchIn", ::mrBase) {
    addProperty("excludeSearchIn", "title")
  }
  add(Row("reviewer on issues", WARNING to "reviewer") { addProperty("reviewer", "x") })
  add(Row("wip on issues", WARNING to "wip") { addProperty("wip", "yes") })
  add(Row("draft on issues", WARNING to "draft") { addProperty("draft", "no") })
  listOf("due_date", "relative_position", "weight").forEach { value ->
    this += Row("issue-only orderBy $value on merge requests", WARNING to "orderBy", ::mrBase) {
      addProperty("orderBy", value)
    }
  }
  add(Row("instance issues with scope all", WARNING to "scope") { addProperty("target", "instance") })
  this += Row("instance issues without scope (runtime default all)", WARNING to "scope") {
    addProperty("target", "instance")
    remove("scope")
  }
  this += Row("instance issues with excludeSearch", WARNING to "excludeSearch") {
    addProperty("target", "instance")
    addProperty("scope", "assigned_to_me")
    addProperty("excludeSearch", "x")
  }
  this += Row("instance issues with excludeSearchIn", WARNING to "excludeSearchIn") {
    addProperty("target", "instance")
    addProperty("scope", "assigned_to_me")
    addProperty("excludeSearchIn", "all")
  }
}

private fun problemsOf(json: JsonObject, allNames: List<String?> = emptyList()) =
  CustomQueryValidator.validate(query(json), allNames)

class CustomQueryValidatorTest : StringSpec({
  "the baseline queries are valid" {
    problemsOf(issueBase()).shouldBeEmpty()
    problemsOf(mrBase()).shouldBeEmpty()
  }

  rows.forEach { row ->
    "${row.title} yields ${row.expected.first} on ${row.expected.second}" {
      val json = row.base().apply(row.mutate)
      problemsOf(json).map { it.severity to it.field } shouldBe listOf(row.expected)
    }
  }

  "a fully populated valid query has no problems" {
    val json = parse(
      """{"name":"Q","type":"issues","target":"project","scope":" created_by_me ","state":"closed",
        |"sort":" desc","orderBy":"weight","searchIn":"all","excludeSearchIn":"description","maxResults":20.0,
        |"labels":["a"," b",""],"excludeLabels":[],"confidential":false,"noItemText":"none","milestone":"Any",
        |"excludeMilestone":"m","author":"<current_user>","excludeAuthor":"u","assignee":"None",
        |"excludeAssignee":"u","search":"s","excludeSearch":"t","createdAfter":"2024-01-02",
        |"createdBefore":"2024-01-02T10:00Z","updatedAfter":"2024-01-02T10:00:30.5+09:00",
        |"updatedBefore":"2024-01-02T10:00:30-05:00","unknownField":{"x":1}}
      """.trimMargin(),
    )
    problemsOf(json).shouldBeEmpty()
  }

  "maxResults accepts the bounds 1 and 100" {
    problemsOf(issueBase().apply { addProperty("maxResults", 1) }).shouldBeEmpty()
    problemsOf(issueBase().apply { addProperty("maxResults", 100) }).shouldBeEmpty()
  }

  "empty sort and orderBy count as absent" {
    val json = mrBase().apply {
      addProperty("sort", " ")
      addProperty("orderBy", "")
    }
    problemsOf(json).shouldBeEmpty()
  }

  "explicit nulls count as absent" {
    val json = mrBase().apply {
      set("milestone", "null")
      set("excludeLabels", "null")
    }
    problemsOf(json).shouldBeEmpty()
  }

  "a query without type is a merge request query" {
    val withWip = mrBase().apply {
      remove("type")
      addProperty("wip", "yes")
    }
    val withConfidential = mrBase().apply {
      remove("type")
      addProperty("confidential", true)
    }
    problemsOf(withWip).shouldBeEmpty()
    problemsOf(withConfidential).map { it.severity to it.field } shouldBe listOf(WARNING to "confidential")
  }

  "a non-object stored element is a single query error" {
    val problems = CustomQueryValidator.validate(CustomQuery("entry-0", JsonObject(), JsonPrimitive(SECRET)))
    problems.map { Triple(it.severity, it.field, it.message) } shouldBe
      listOf(Triple(ERROR, "query", "Query entry is not an object."))
  }

  "unsupported types are neither errors nor validated" {
    listOf("epics", "snippets", "vulnerabilities").forEach { type ->
      val json = parse("""{"name":"","type":"$type","scope":1,"maxResults":"x","reportTypes":["SAST"]}""")
      problemsOf(json).shouldBeEmpty()
    }
  }

  "a duplicate trimmed name is a warning; names compare case-sensitively" {
    problemsOf(issueBase(), listOf("Q", " Q ", null)).map { it.severity to it.field } shouldBe
      listOf(WARNING to "name")
    problemsOf(issueBase(), listOf("Q", "q", null)).shouldBeEmpty()
  }

  "errors are listed before warnings" {
    val json = mrBase().apply {
      addProperty("confidential", true)
      addProperty("maxResults", 0)
    }
    problemsOf(json, listOf("Q", "Q")).map { it.severity } shouldBe listOf(ERROR, WARNING, WARNING)
  }

  "no message contains the offending value" {
    val invalidEverywhere = issueBase().apply {
      (enumFields + dateFields + listOf("type", "target", "confidential", "maxResults")).forEach {
        addProperty(it, SECRET)
      }
      set("labels", """["$SECRET,"]""")
      set("excludeLabels", """["$SECRET,"]""")
    }
    val excludesOnMrs = mrBase().apply {
      (stringFields.filter { it.startsWith("exclude") } + "excludeSearchIn").forEach { addProperty(it, SECRET) }
    }
    val reviewerOnIssues = issueBase().apply {
      addProperty("reviewer", SECRET)
      addProperty("search", SECRET)
    }
    val duplicate = issueBase().apply { addProperty("name", SECRET) }
    val problems = listOf(
      problemsOf(invalidEverywhere),
      problemsOf(excludesOnMrs),
      problemsOf(reviewerOnIssues),
      problemsOf(duplicate, listOf(SECRET, " $SECRET")),
    )
    problems.map { list -> list.map { it.field }.toSet() } shouldBe listOf(
      (enumFields + dateFields + listOf("type", "target", "confidential", "maxResults", "labels", "excludeLabels"))
        .toSet(),
      setOf("excludeMilestone", "excludeAuthor", "excludeAssignee", "excludeSearch", "excludeSearchIn"),
      setOf("reviewer"),
      setOf("name"),
    )
    problems.flatten().forEach { it.message shouldNotContain SECRET }
  }

  "effectiveType defaults to merge requests and rejects unknown values" {
    CustomQueryValidator.effectiveType(query(JsonObject())) shouldBe CustomQueryType.MERGE_REQUESTS
    CustomQueryValidator.effectiveType(query(parse("""{"type":"issues"}"""))) shouldBe CustomQueryType.ISSUES
    CustomQueryValidator.effectiveType(query(parse("""{"type":"epics"}"""))) shouldBe CustomQueryType.EPICS
    CustomQueryValidator.effectiveType(query(parse("""{"type":"bugs"}"""))) shouldBe null
    CustomQueryValidator.effectiveType(query(parse("""{"type":2}"""))) shouldBe null
  }

  "effectiveTarget defaults to project and rejects invalid values" {
    CustomQueryValidator.effectiveTarget(query(JsonObject())) shouldBe QueryTarget.PROJECT
    CustomQueryValidator.effectiveTarget(query(parse("""{"target":"instance"}"""))) shouldBe QueryTarget.INSTANCE
    CustomQueryValidator.effectiveTarget(query(parse("""{"target":"group"}"""))) shouldBe null
    CustomQueryValidator.effectiveTarget(query(parse("""{"target":[]}"""))) shouldBe null
  }
})
