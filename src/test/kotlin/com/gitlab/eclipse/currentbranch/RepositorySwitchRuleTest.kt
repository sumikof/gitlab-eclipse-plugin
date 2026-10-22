package com.gitlab.eclipse.currentbranch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Status-bar design §4.3: refresh when the activated editor lies outside the tracked work tree. */
class RepositorySwitchRuleTest : StringSpec({
  "a file inside the work tree does not refresh" {
    RepositorySwitchRule.shouldRefresh("/a/b/src/X.kt", "/a/b") shouldBe false
  }

  "a file outside the work tree refreshes" {
    RepositorySwitchRule.shouldRefresh("/other/X.kt", "/a/b") shouldBe true
  }

  "a sibling directory sharing the prefix is outside (separator boundary)" {
    RepositorySwitchRule.shouldRefresh("/a/bc/X.kt", "/a/b") shouldBe true
  }

  "a trailing separator on the work tree is ignored" {
    RepositorySwitchRule.shouldRefresh("/a/b/X.kt", "/a/b/") shouldBe false
    RepositorySwitchRule.shouldRefresh("/a/bc/X.kt", "/a/b/") shouldBe true
  }

  "the work tree path itself counts as inside" {
    RepositorySwitchRule.shouldRefresh("/a/b", "/a/b") shouldBe false
  }

  "Windows separators are handled" {
    RepositorySwitchRule.shouldRefresh("C:\\w\\repo\\src\\X.kt", "C:\\w\\repo") shouldBe false
    RepositorySwitchRule.shouldRefresh("C:\\w\\repo2\\X.kt", "C:\\w\\repo") shouldBe true
  }

  "no active editor never refreshes" {
    RepositorySwitchRule.shouldRefresh(null, "/a/b") shouldBe false
    RepositorySwitchRule.shouldRefresh(null, null) shouldBe false
  }

  "an editor with no tracked work tree refreshes" {
    RepositorySwitchRule.shouldRefresh("/a/b/X.kt", null) shouldBe true
  }
})
