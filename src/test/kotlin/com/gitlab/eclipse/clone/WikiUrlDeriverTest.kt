package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WikiUrlDeriverTest : StringSpec({
  "appends .wiki.git after stripping a trailing .git" {
    WikiUrlDeriver.derive("https://gitlab.example.com/group/project.git") shouldBe
      "https://gitlab.example.com/group/project.wiki.git"
  }

  "treats the .git suffix as optional" {
    WikiUrlDeriver.derive("https://gitlab.example.com/group/project") shouldBe
      "https://gitlab.example.com/group/project.wiki.git"
  }

  "drops a trailing slash first" {
    WikiUrlDeriver.derive("https://gitlab.example.com/group/project/") shouldBe
      "https://gitlab.example.com/group/project.wiki.git"
    WikiUrlDeriver.derive("https://gitlab.example.com/group/project.git/") shouldBe
      "https://gitlab.example.com/group/project.wiki.git"
  }

  "never returns the input unchanged for a url without .git" {
    val input = "https://gitlab.example.com/group/sub/project"
    WikiUrlDeriver.derive(input) shouldBe "$input.wiki.git"
  }

  "keeps a project name that merely contains git" {
    WikiUrlDeriver.derive("https://gitlab.example.com/group/gitlab.git") shouldBe
      "https://gitlab.example.com/group/gitlab.wiki.git"
  }
})
