package com.gitlab.eclipse.views.sidebar

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pure instance-membership check backing the connection tagging in `loadMrChildren` (Codex round-1
 * P1-1): a merge request may only be stamped with a captured connection's tags when its own web
 * URL actually lives under that instance, on a `/` boundary — otherwise the connection gate would
 * later compare the live connection against a tag the data never came from and pass wrongly.
 */
class MrBelongsToInstanceTest : StringSpec({
  val mrUrl = "https://gitlab.example.com/group/project/-/merge_requests/42"

  "true when the merge request URL is under the exact instance URL" {
    mrBelongsToInstance(mrUrl, "https://gitlab.example.com") shouldBe true
  }

  "true when the instance URL has a trailing slash and the merge request URL does not repeat it" {
    mrBelongsToInstance(mrUrl, "https://gitlab.example.com/") shouldBe true
  }

  "true for an instance hosted under a custom path" {
    mrBelongsToInstance(
      "https://example.com/gitlab/group/project/-/merge_requests/42",
      "https://example.com/gitlab",
    ) shouldBe true
  }

  "false for a different host" {
    mrBelongsToInstance(
      "https://other.example.com/group/project/-/merge_requests/42",
      "https://gitlab.example.com",
    ) shouldBe false
  }

  "false for a prefix-boundary attack: the instance URL as a leading substring of a longer host" {
    mrBelongsToInstance(
      "https://gitlab.example.com.attacker.test/group/project/-/merge_requests/42",
      "https://gitlab.example.com",
    ) shouldBe false
  }

  "false for a null webUrl" {
    mrBelongsToInstance(null, "https://gitlab.example.com") shouldBe false
  }

  "false for a blank webUrl" {
    mrBelongsToInstance("   ", "https://gitlab.example.com") shouldBe false
  }

  "false for a blank instance URL (never a match-everything prefix)" {
    mrBelongsToInstance(mrUrl, "") shouldBe false
  }
})
