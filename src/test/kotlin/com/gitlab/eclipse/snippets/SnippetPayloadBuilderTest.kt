package com.gitlab.eclipse.snippets

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class SnippetPayloadBuilderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  val text = "line0\nline1\nline2\n"

  describe("build") {
    it("uses the whole document when there is no selection") {
      val payload = SnippetPayloadBuilder.build("Foo.kt", text, null, SnippetVisibility.PRIVATE)

      payload.content shouldBe text
      payload.fileName shouldBe "Foo.kt"
      payload.title shouldBe "Foo.kt"
      payload.visibility shouldBe "private"
    }

    it("rounds a selection to whole lines, up to the start of the line after the end line") {
      // Selecting part of line1 must yield exactly "line1\n" — VSCode takes
      // Range(Position(start.line, 0), Position(end.line + 1, 0)) in create_snippet.ts.
      val payload =
        SnippetPayloadBuilder.build("Foo.kt", text, SelectionRange(1, 1), SnippetVisibility.PRIVATE)

      payload.content shouldBe "line1\n"
    }

    it("spans multiple selected lines") {
      val payload =
        SnippetPayloadBuilder.build("Foo.kt", text, SelectionRange(0, 1), SnippetVisibility.PUBLIC)

      payload.content shouldBe "line0\nline1\n"
      payload.visibility shouldBe "public"
    }

    it("keeps the last line when it has no trailing newline") {
      val payload =
        SnippetPayloadBuilder.build("Foo.kt", "a\nb", SelectionRange(1, 1), SnippetVisibility.PRIVATE)

      payload.content shouldBe "b"
    }
  }
})
