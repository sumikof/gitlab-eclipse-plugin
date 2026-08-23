package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the project-path validator only — a top-level SWT-free function, reachable headless.
 * The dialog itself cannot run headless and stays on the manual plan.
 *
 * The validator deliberately does NOT require a `/`: GitLab paths are not narrowed here, because
 * wrongly rejecting a legitimate path is worse than letting the lookup answer with the explicit
 * "project not found" wording.
 */
class ProjectPathPromptTest : StringSpec({
  "a namespaced project path is accepted" {
    validateProjectPath("group/subgroup/project") shouldBe null
  }

  "a path without a slash is accepted: the shape of GitLab paths is not narrowed here" {
    validateProjectPath("project") shouldBe null
  }

  "surrounding whitespace does not fail an otherwise valid path (the trimmed value is consumed)" {
    validateProjectPath("  group/project  ") shouldBe null
  }

  // The four rejections are compared verbatim, not merely against null: the message is the whole
  // of what the user is told, and a swap between the four would otherwise pass unnoticed.
  "a blank path is rejected" {
    val expected = "Enter a project path, for example group/subgroup/project."
    validateProjectPath("") shouldBe expected
    validateProjectPath("   ") shouldBe expected
  }

  "a pasted URL is rejected with its own wording" {
    validateProjectPath("https://gitlab.com/group/project") shouldBe "Enter the project path, not a URL."
    validateProjectPath("git://gitlab.com/group/project") shouldBe "Enter the project path, not a URL."
  }

  "an inner space is rejected" {
    validateProjectPath("group/my project") shouldBe "A project path cannot contain spaces."
    validateProjectPath("group /project") shouldBe "A project path cannot contain spaces."
  }

  "a leading or trailing slash is rejected" {
    val expected = "A project path cannot start or end with '/'."
    validateProjectPath("/group/project") shouldBe expected
    validateProjectPath("group/project/") shouldBe expected
    // The trimmed value is what is validated, so whitespace cannot smuggle an edge slash through.
    validateProjectPath(" /group/project ") shouldBe expected
  }

  "each rejection carries its own explanation" {
    val blank = validateProjectPath("")
    val url = validateProjectPath("https://gitlab.com/group/project")
    val spaced = validateProjectPath("group/my project")
    val slashed = validateProjectPath("/group/project")
    setOf(blank, url, spaced, slashed).size shouldBe 4
  }
})
