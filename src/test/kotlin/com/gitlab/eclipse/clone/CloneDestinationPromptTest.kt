package com.gitlab.eclipse.clone

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Pins the folder-name validator only — a top-level SWT-free function, reachable headless.
 * The dialogs themselves cannot run headless and stay on the manual plan (M1-M5).
 */
class CloneDestinationPromptTest : StringSpec({
  "a plain folder name is accepted" {
    validateFolderName("project.wiki") shouldBe null
  }

  "surrounding whitespace does not fail an otherwise valid name (the trimmed value is consumed)" {
    validateFolderName(" project.wiki ") shouldBe null
  }

  "a blank name is rejected: it would target the parent directory itself" {
    validateFolderName("") shouldNotBe null
    validateFolderName("   ") shouldNotBe null
  }

  "dot and dot-dot are rejected: they resolve to the parent or above it" {
    validateFolderName(".") shouldNotBe null
    validateFolderName("..") shouldNotBe null
    // The trimmed value is what is validated, so whitespace cannot smuggle them through.
    validateFolderName(" . ") shouldNotBe null
    validateFolderName(" .. ") shouldNotBe null
  }

  "path separators are rejected: the clone must land under the picked parent" {
    validateFolderName("a/b") shouldNotBe null
    validateFolderName("a\\b") shouldNotBe null
  }
})
