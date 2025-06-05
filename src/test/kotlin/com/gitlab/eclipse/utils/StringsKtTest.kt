package com.gitlab.eclipse.utils

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class StringsKtTest : DescribeSpec({
  it("should return empty list for empty string") {
    "".linesWithSeparators() shouldBe emptyList()
  }

  it("should return single line for string without line separators") {
    "hello".linesWithSeparators() shouldBe listOf("hello")
  }

  it("should handle LF line endings") {
    "line1\nline2".linesWithSeparators() shouldBe listOf("line1\n", "line2")
  }

  it("should handle CR line endings") {
    "line1\rline2".linesWithSeparators() shouldBe listOf("line1\r", "line2")
  }

  it("should handle CRLF line endings") {
    "line1\r\nline2".linesWithSeparators() shouldBe listOf("line1\r\n", "line2")
  }

  it("should handle mixed line endings") {
    "line1\nline2\r\nline3\rline4".linesWithSeparators() shouldBe listOf("line1\n", "line2\r\n", "line3\r", "line4")
  }

  it("should handle trailing line separator") {
    "line1\n".linesWithSeparators() shouldBe listOf("line1\n")
  }

  it("should handle multiple consecutive line separators") {
    "line1\n\nline2".linesWithSeparators() shouldBe listOf("line1\n", "\n", "line2")
  }

  it("should handle string starting with line separator") {
    "\nline1".linesWithSeparators() shouldBe listOf("\n", "line1")
  }
})
