package com.gitlab.eclipse.chat.quickchat.fix

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class FixAnnotationFilterTest : DescribeSpec({

  // "aaaa\nbbbb\ncccc": lines start at 0, 5, 10
  val lineOf = { offset: Int -> if (offset < 5) 0 else if (offset < 10) 1 else 2 }
  val isSubtype = { t: String, s: String -> t == s || (t == "org.eclipse.jdt.ui.error" && s == ERROR_ANNOTATION_TYPE) }

  fun ann(
    offset: Int,
    length: Int,
    text: String? = "msg",
    type: String = ERROR_ANNOTATION_TYPE,
    deleted: Boolean = false,
  ) = AnnotationFacts(type, text, offset, length, deleted)

  fun select(range: TextSpan, vararg a: AnnotationFacts) =
    selectFixDiagnostics(a.toList(), range, lineOf, isSubtype)

  describe("selectFixDiagnostics") {
    it("returns errors and warnings in offset order, skipping info, spelling and deleted") {
      select(
        TextSpan(0, 14),
        ann(11, 2, "w", WARNING_ANNOTATION_TYPE),
        ann(1, 2, "e"),
        ann(2, 2, "i", "org.eclipse.ui.workbench.texteditor.info"),
        ann(3, 2, "s", "org.eclipse.ui.workbench.spelling"),
        ann(4, 1, "d", deleted = true),
      ) shouldBe listOf(FixDiagnostic("e", 1), FixDiagnostic("w", 3))
    }

    it("accepts subtypes") {
      select(TextSpan(0, 3), ann(0, 2, "j", "org.eclipse.jdt.ui.error")) shouldBe listOf(FixDiagnostic("j", 1))
    }

    it("drops null and blank text") {
      select(TextSpan(0, 14), ann(0, 2, null), ann(1, 2, "  ")) shouldBe emptyList()
    }

    it("matches a zero-length annotation on a selected line even when the selection starts mid-line") {
      select(TextSpan(7, 2), ann(5, 0, "z")) shouldBe listOf(FixDiagnostic("z", 2))
    }

    it("ignores a zero-length annotation on a line outside the range") {
      select(TextSpan(7, 2), ann(0, 0, "z"), ann(10, 0, "y")) shouldBe emptyList()
    }

    it("matches a sized annotation that overlaps and not one that only touches") {
      select(TextSpan(5, 3), ann(3, 3, "over")) shouldBe listOf(FixDiagnostic("over", 1))
      select(TextSpan(5, 3), ann(2, 3, "touch")) shouldBe emptyList()
    }

    it("matches a sized annotation around a zero-length range") {
      select(TextSpan(6, 0), ann(5, 3, "in")) shouldBe listOf(FixDiagnostic("in", 2))
    }

    it("collapses the same line and text") {
      select(TextSpan(0, 14), ann(0, 2, "dup"), ann(1, 2, "dup"), ann(2, 1, "dup", deleted = true)) shouldBe
        listOf(FixDiagnostic("dup", 1))
    }

    it("keeps the same text on different lines") {
      select(TextSpan(0, 14), ann(0, 2, "dup"), ann(6, 2, "dup")) shouldBe
        listOf(FixDiagnostic("dup", 1), FixDiagnostic("dup", 2))
    }
  }
})
