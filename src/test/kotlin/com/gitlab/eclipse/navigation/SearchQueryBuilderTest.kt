package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class SearchQueryBuilderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  it("maps basic free text to the search param") {
    SearchQueryBuilder.parseQuery("some bug", "issues") shouldBe "?search=some+bug"
  }
  it("returns empty string for empty input") {
    SearchQueryBuilder.parseQuery("", "issues") shouldBe ""
  }
  it("maps title: to search") {
    SearchQueryBuilder.parseQuery("title: crash", "issues") shouldBe "?search=crash"
  }
  it("maps milestone: to milestone_title") {
    SearchQueryBuilder.parseQuery("milestone: 15.0", "issues") shouldBe "?milestone_title=15.0"
  }
  it("maps author:me to scope=created-by-me") {
    SearchQueryBuilder.parseQuery("author: me", "issues") shouldBe "?scope=created-by-me"
  }
  it("maps author:name to author_username") {
    SearchQueryBuilder.parseQuery("author: alice", "issues") shouldBe "?author_username=alice"
  }
  it("maps assignee:me to scope=assigned-to-me") {
    SearchQueryBuilder.parseQuery("assignee: me", "issues") shouldBe "?scope=assigned-to-me"
  }
  it("uses assignee_username[] for issues") {
    SearchQueryBuilder.parseQuery("assignee: bob", "issues") shouldBe "?assignee_username%5B%5D=bob"
  }
  it("uses assignee_username for merge_requests") {
    SearchQueryBuilder.parseQuery("assignee: bob", "merge_requests") shouldBe "?assignee_username=bob"
  }
  it("joins labels with commas") {
    SearchQueryBuilder.parseQuery("labels: bug, ui", "issues") shouldBe "?labels=bug%2Cui"
  }
  it("accumulates single label tokens") {
    SearchQueryBuilder.parseQuery("label: bug label: ui", "issues") shouldBe "?labels=bug%2Cui"
  }
})
