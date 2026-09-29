package com.gitlab.eclipse.views.inlinethread

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

class MarkdownCodeBlocksTest : DescribeSpec({
  describe("split (design §9.7, A16)") {
    it("recognizes a language tag with a plus sign (c++)") {
      val segments = MarkdownCodeBlocks.split("```c++\nint x;\n```")
      segments shouldBe listOf(Segment.Code("c++", "int x;", actionable = true))
    }

    it("recognizes a language tag with a hyphen (objective-c)") {
      val segments = MarkdownCodeBlocks.split("```objective-c\nint x;\n```")
      segments shouldBe listOf(Segment.Code("objective-c", "int x;", actionable = true))
    }

    it("recognizes a tilde fence") {
      val segments = MarkdownCodeBlocks.split("~~~kotlin\nval x = 1\n~~~")
      segments shouldBe listOf(Segment.Code("kotlin", "val x = 1", actionable = true))
    }

    it("treats an unclosed fence as code to the end of the body") {
      val segments = MarkdownCodeBlocks.split("```kotlin\nval x = 1\nval y = 2")
      segments shouldBe listOf(Segment.Code("kotlin", "val x = 1\nval y = 2", actionable = true))
    }

    it("accepts a closing fence longer than the opening one") {
      val segments = MarkdownCodeBlocks.split("```\ncode\n````")
      segments shouldBe listOf(Segment.Code(null, "code", actionable = true))
    }

    it("does not close on a fence shorter than the opening one (kept as code content)") {
      val segments = MarkdownCodeBlocks.split("````\ncode\n```\nmore\n````")
      segments shouldBe listOf(Segment.Code(null, "code\n```\nmore", actionable = true))
    }

    it("treats a backtick fence whose info string contains a backtick as prose") {
      val segments = MarkdownCodeBlocks.split("```has`backtick\nnot code")
      segments shouldBe listOf(Segment.Prose("```has`backtick\nnot code"))
    }

    it("allows a tilde fence's info string to contain a backtick") {
      val segments = MarkdownCodeBlocks.split("~~~has`backtick\ncode\n~~~")
      segments shouldBe listOf(Segment.Code("has`backtick", "code", actionable = true))
    }

    it("strips the opening fence's indentation from each code line (0-3 spaces allowed)") {
      val segments = MarkdownCodeBlocks.split("  ```\n  code one\n    code two\n  ```")
      segments shouldBe listOf(Segment.Code(null, "code one\n  code two", actionable = true))
    }

    it("does not treat a 4-space-indented fence line as a fence (prose)") {
      val segments = MarkdownCodeBlocks.split("    ```\n    not a fence")
      segments shouldBe listOf(Segment.Prose("    ```\n    not a fence"))
    }
  }

  describe("actionable (empty code blocks)") {
    it("marks an empty code block as not actionable") {
      val segments = MarkdownCodeBlocks.split("```\n```")
      segments shouldBe listOf(Segment.Code(null, "", actionable = false))
    }

    it("marks a whitespace-only code block as not actionable") {
      val segments = MarkdownCodeBlocks.split("```\n   \n```")
      segments shouldBe listOf(Segment.Code(null, "   ", actionable = false))
    }
  }

  describe("MAX_ACTION_BLOCKS resource limit (A27)") {
    it("marks the 31st non-blank code block as not actionable") {
      val body = (1..31).joinToString("\n") { "```\ncode $it\n```" }
      val segments = MarkdownCodeBlocks.split(body)
      val codeBlocks = segments.filterIsInstance<Segment.Code>()
      codeBlocks.size shouldBe 31
      codeBlocks.take(MarkdownCodeBlocks.MAX_ACTION_BLOCKS).forEach { it.actionable shouldBe true }
      codeBlocks.last().actionable shouldBe false
      MarkdownCodeBlocks.MAX_ACTION_BLOCKS shouldBe 30
    }

    it("does not count blank code blocks toward the 30-block limit") {
      val blanks = (1..30).joinToString("\n") { "```\n```" }
      val body = "$blanks\n```\nreal code\n```"
      val segments = MarkdownCodeBlocks.split(body)
      val codeBlocks = segments.filterIsInstance<Segment.Code>()
      codeBlocks.size shouldBe 31
      codeBlocks.dropLast(1).forEach { it.actionable shouldBe false }
      codeBlocks.last() shouldBe Segment.Code(null, "real code", actionable = true)
    }
  }

  describe("truncation mid-block") {
    it("treats a body truncated right after the opening fence line as an unclosed, empty block") {
      val segments = MarkdownCodeBlocks.split("prose\n```kotlin")
      segments shouldBe listOf(Segment.Prose("prose"), Segment.Code("kotlin", "", actionable = false))
    }

    it("treats a body truncated mid-code-line as unclosed code to the end") {
      val segments = MarkdownCodeBlocks.split("```kotlin\nval x = compute(a, b")
      segments shouldBe listOf(Segment.Code("kotlin", "val x = compute(a, b", actionable = true))
    }
  }

  describe("prose") {
    it("returns a single Prose segment for a prose-only body") {
      MarkdownCodeBlocks.split("hello\nworld") shouldBe listOf(Segment.Prose("hello\nworld"))
    }

    it("returns an empty list for an empty body") {
      MarkdownCodeBlocks.split("").shouldBeEmpty()
    }

    it("keeps prose before and after a code block, without the fence lines' newlines") {
      val segments = MarkdownCodeBlocks.split("before\n```\ncode\n```\nafter")
      segments shouldBe listOf(
        Segment.Prose("before"),
        Segment.Code(null, "code", actionable = true),
        Segment.Prose("after"),
      )
    }

    it("omits empty prose between two adjacent code blocks") {
      val segments = MarkdownCodeBlocks.split("```\na\n```\n```\nb\n```")
      segments shouldBe listOf(
        Segment.Code(null, "a", actionable = true),
        Segment.Code(null, "b", actionable = true),
      )
    }
  }

  describe("CRLF handling") {
    it("treats \\r\\n as a line break and leaves no \\r in the code") {
      val segments = MarkdownCodeBlocks.split("prose\r\n```kotlin\r\nval x = 1\r\n```\r\nafter")
      segments shouldBe listOf(
        Segment.Prose("prose"),
        Segment.Code("kotlin", "val x = 1", actionable = true),
        Segment.Prose("after"),
      )
    }
  }
})
