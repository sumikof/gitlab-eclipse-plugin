package com.gitlab.eclipse.codesuggestions.tutorial

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** §12.2's adaptation rules, applied to [DuoTutorialContent.TEXT] (spec `DuoTutorialContentTest`). */
class DuoTutorialContentTest : DescribeSpec({

  describe("identity") {
    it("names the project and file the tutorial creates") {
      DuoTutorialContent.PROJECT_NAME shouldBe "GitLab Duo Tutorial"
      DuoTutorialContent.FILE_NAME shouldBe "duo_tutorial.js"
    }
  }

  describe("MIT attribution (§12.2 header row)") {
    it("keeps the MIT notice for the upstream source it was adapted from") {
      DuoTutorialContent.TEXT shouldContain "MIT License"
      DuoTutorialContent.TEXT shouldContain "Copyright (c) 2020-present GitLab Inc."
    }
  }

  describe("Eclipse key bindings and real popup labels (§12.2)") {
    it("mentions the Duo Chat key binding") {
      DuoTutorialContent.TEXT shouldContain "Alt + D"
    }

    it("uses the real editor popup label for Explain Code") {
      DuoTutorialContent.TEXT shouldContain "Explain Code"
    }

    it("uses the real editor popup label for Generate Tests") {
      DuoTutorialContent.TEXT shouldContain "Generate Tests"
    }

    it("uses the real editor popup label for Refactor Code") {
      DuoTutorialContent.TEXT shouldContain "Refactor Code"
    }
  }

  describe("removed Quick Chat section (§12.2 row 4)") {
    it("does not mention Quick Chat") {
      DuoTutorialContent.TEXT shouldNotContain "Quick Chat"
    }

    it("does not contain the Quick Chat sample function") {
      DuoTutorialContent.TEXT shouldNotContain "fibonacci"
    }

    it("does not mention the Quick Chat key binding") {
      DuoTutorialContent.TEXT shouldNotContain "Alt> + C"
    }

    it("does not mention the never-bound Generate Tests key binding") {
      DuoTutorialContent.TEXT shouldNotContain "Alt> + T"
    }

    it("does not mention the never-bound Refactor Code key binding") {
      DuoTutorialContent.TEXT shouldNotContain "Alt> + R"
    }
  }

  describe("regex escaping on the Kotlin raw string (§12.2 raw-string note)") {
    it("does not contain a doubled backslash before the JS regex's \\s") {
      DuoTutorialContent.TEXT shouldNotContain "\\\\s"
    }

    it("contains the regex's dollar-slash end anchor") {
      DuoTutorialContent.TEXT shouldContain "$/"
    }
  }
})
