package com.gitlab.eclipse.views.sidebar.queries

import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.api.model.GitLabIssue
import com.gitlab.eclipse.api.model.GitLabMergeRequest
import com.gitlab.eclipse.views.issues.configErrorMessage
import com.gitlab.eclipse.views.sidebar.IssueNode
import com.gitlab.eclipse.views.sidebar.MergeRequestNode
import com.gitlab.eclipse.views.sidebar.MessageNode
import com.gitlab.eclipse.views.sidebar.ProjectGroupNode
import com.gitlab.eclipse.views.sidebar.QueriesSlot
import com.gitlab.eclipse.views.sidebar.QueryRootNode
import com.gitlab.eclipse.views.sidebar.SidebarNode
import com.gitlab.eclipse.views.sidebar.SidebarViewMode
import com.gitlab.eclipse.views.sidebar.SidebarViewModel
import com.gitlab.eclipse.views.sidebar.Slot
import com.google.gson.JsonObject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs

class QueryRootsComposerTest : StringSpec({
  fun query(id: String, name: String?, type: String = "issues", vararg extra: Pair<String, String>) =
    CustomQuery(
      id,
      JsonObject().apply {
        if (name != null) addProperty("name", name)
        addProperty("type", type)
        extra.forEach { (k, v) -> addProperty(k, v) }
      },
    )
  fun issue(iid: Long, full: String) =
    GitLabIssue(iid, iid, "t$iid", "u$iid", "opened", GitLabIssue.Reference(full))
  fun mr(iid: Long, full: String) =
    GitLabMergeRequest(iid, iid, "m$iid", 1, "mu$iid", "opened", references = GitLabMergeRequest.Reference(full))
  fun composer() = QueryRootsComposer(SidebarViewModel())
  fun slotOf(vararg queries: CustomQuery) = QueriesSlot(QueryConfig.Custom(queries.toList()))
  fun messages(node: SidebarNode) = node.children.map { (it as MessageNode).label }
  fun rootOf(outcome: QueryOutcome, q: CustomQuery = query("a", "A")): SidebarNode {
    val slot = slotOf(q)
    slot.outcomes[q.id] = Slot.Settled(outcome)
    return composer().compose(slot, SidebarViewMode.LIST).single()
  }

  "roots follow config order and carry the query id and trimmed name" {
    val slot = slotOf(query("b", "  Second "), query("a", "First"))
    val roots = composer().compose(slot, SidebarViewMode.LIST).map { it as QueryRootNode }
    roots.map { it.queryId } shouldBe listOf("b", "a")
    roots.map { it.label } shouldBe listOf("Second", "First")
  }

  "a blank or missing name is labelled (unnamed query)" {
    val roots = composer().compose(slotOf(query("a", "   "), query("b", null)), SidebarViewMode.LIST)
    roots.map { it.label } shouldBe listOf("(unnamed query)", "(unnamed query)")
  }

  "a fresh slot is all Pending and every root shows Loading" {
    val roots = composer().compose(slotOf(query("a", "A"), query("b", "B")), SidebarViewMode.LIST)
    roots.forEach { messages(it) shouldBe listOf("Loading…") }
  }

  "the Defaults config yields the six default roots by id" {
    val roots = composer().compose(QueriesSlot(QueryConfig.Defaults), SidebarViewMode.LIST)
    roots.map { (it as QueryRootNode).queryId } shouldBe CustomQueryDefaults.queries.map { it.id }
    roots[0].label shouldBe "Issues assigned to me"
  }

  "issues in LIST mode are flat item nodes" {
    val root = rootOf(QueryOutcome.Issues(listOf(issue(1, "g/p#1"), issue(2, "h/q#2")), truncated = false))
    root.children shouldHaveSize 2
    root.children.forEach { it.shouldBeInstanceOf<IssueNode>() }
  }

  "merge requests in TREE mode are grouped by project" {
    val q = query("a", "A", "merge_requests")
    val slot = slotOf(q)
    slot.outcomes["a"] = Slot.Settled(QueryOutcome.MergeRequests(listOf(mr(1, "g/p!1"), mr(2, "h/q!2")), false))
    val root = composer().compose(slot, SidebarViewMode.TREE).single()
    root.children shouldHaveSize 2
    root.children.forEach { it.shouldBeInstanceOf<ProjectGroupNode>() }
    (root.children[0].children.single() as MergeRequestNode).mr.iid shouldBe 1
  }

  "zero items shows noItemText, or No items found. when it is absent or blank" {
    messages(rootOf(QueryOutcome.Issues(emptyList(), false), query("a", "A", "issues", "noItemText" to "Nothing")))
      .shouldBe(listOf("Nothing"))
    messages(rootOf(QueryOutcome.Issues(emptyList(), false))) shouldBe listOf("No items found.")
    messages(rootOf(QueryOutcome.MergeRequests(emptyList(), false), query("a", "A", "issues", "noItemText" to " ")))
      .shouldBe(listOf("No items found."))
  }

  "a truncated result lists the items then the cap notice" {
    val root = rootOf(QueryOutcome.Issues(listOf(issue(1, "g/p#1")), truncated = true))
    root.children shouldHaveSize 2
    root.children[0].shouldBeInstanceOf<IssueNode>()
    (root.children[1] as MessageNode).label shouldBe "Showing the first 2000 results."
  }

  "each non-item outcome shows its text" {
    messages(rootOf(QueryOutcome.NeedsProject)) shouldBe listOf("Open a file from a GitLab project to run this query.")
    messages(rootOf(QueryOutcome.Invalid("Type is not valid."))) shouldBe listOf("Invalid query: Type is not valid.")
    messages(rootOf(QueryOutcome.Unsupported)) shouldBe listOf("This query type is not supported in Eclipse yet.")
    val failures = mapOf(
      QueryFailureKind.REJECTED to "GitLab rejected this query. Check its filters.",
      QueryFailureKind.UNAUTHORIZED to "Not authorized. Check your GitLab token.",
      QueryFailureKind.NOT_FOUND_OR_DENIED to "Project not found or access denied.",
      QueryFailureKind.RATE_LIMITED to "GitLab rate limit reached. Try Refresh later.",
      QueryFailureKind.TIMED_OUT to "Timed out loading results. Try Refresh.",
      QueryFailureKind.GENERIC to "Failed to load — see the Error Log.",
      QueryFailureKind.CURRENT_USER to "Could not resolve the current user.",
      QueryFailureKind.CONFIG to "Failed to load — see the Error Log.",
    )
    failures.forEach { (kind, text) -> messages(rootOf(QueryOutcome.Failed(kind))) shouldBe listOf(text) }
  }

  "a config failure shows its user-safe message" {
    val failed = classifyFailure(GitLabConfigurationException("x"))
    val expected = configErrorMessage(GitLabConfigurationException("x"))
    messages(rootOf(failed)) shouldBe listOf(expected)
  }

  "an unreadable config shows one Sidebar queries root with the fix hint" {
    val roots = composer().compose(
      QueriesSlot(QueryConfig.Unreadable(UnreadableReason.MALFORMED_JSON, "{")),
      SidebarViewMode.LIST,
    )
    val root = roots.single() as QueryRootNode
    root.queryId shouldBe "__unreadable"
    root.label shouldBe "Sidebar queries"
    messages(root) shouldBe
      listOf("Sidebar queries could not be read. Fix or restore them in Preferences > GitLab Duo > Sidebar Queries.")
  }

  "an empty custom config shows one Sidebar queries root with the add hint" {
    val root = composer().compose(QueriesSlot(QueryConfig.Custom(emptyList())), SidebarViewMode.LIST).single()
    (root as QueryRootNode).queryId shouldBe "__empty"
    root.label shouldBe "Sidebar queries"
    messages(root) shouldBe listOf("No sidebar queries. Add them in Preferences > GitLab Duo > Sidebar Queries.")
  }

  "memo: a root is reused when only another query settled" {
    val c = composer()
    val slot = slotOf(query("a", "A"), query("b", "B"))
    val first = c.compose(slot, SidebarViewMode.LIST)
    slot.outcomes["b"] = Slot.Settled(QueryOutcome.Issues(emptyList(), false))
    val second = c.compose(slot, SidebarViewMode.LIST)
    second[0] shouldBeSameInstanceAs first[0]
    second[1] shouldNotBeSameInstanceAs first[1]
  }

  "memo: re-wrapping the same outcome keeps the root; a new outcome or mode rebuilds it" {
    val c = composer()
    val slot = slotOf(query("a", "A"))
    val outcome = QueryOutcome.Issues(listOf(issue(1, "g/p#1")), false)
    slot.outcomes["a"] = Slot.Settled(outcome)
    val first = c.compose(slot, SidebarViewMode.LIST).single()
    slot.outcomes["a"] = Slot.Settled(outcome)
    c.compose(slot, SidebarViewMode.LIST).single() shouldBeSameInstanceAs first
    val treeRoot = c.compose(slot, SidebarViewMode.TREE).single()
    treeRoot shouldNotBeSameInstanceAs first
    slot.outcomes["a"] = Slot.Settled(QueryOutcome.Issues(listOf(issue(1, "g/p#1")), false))
    c.compose(slot, SidebarViewMode.TREE).single() shouldNotBeSameInstanceAs treeRoot
  }

  "memo: a different query object with the same id rebuilds the root" {
    val c = composer()
    val first = c.compose(slotOf(query("a", "A")), SidebarViewMode.LIST).single()
    val renamed = c.compose(slotOf(query("a", "Renamed")), SidebarViewMode.LIST).single()
    renamed shouldNotBeSameInstanceAs first
    renamed.label shouldBe "Renamed"
  }

  "reset drops every memoized root" {
    val c = composer()
    val slot = slotOf(query("a", "A"))
    val first = c.compose(slot, SidebarViewMode.LIST).single()
    c.reset()
    c.compose(slot, SidebarViewMode.LIST).single() shouldNotBeSameInstanceAs first
  }
})
