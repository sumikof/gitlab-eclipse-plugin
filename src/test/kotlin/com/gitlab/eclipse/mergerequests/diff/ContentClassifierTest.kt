package com.gitlab.eclipse.mergerequests.diff

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

class ContentClassifierTest : DescribeSpec({
  describe("type") {
    it("is the lowercase extension of the basename") {
      ContentClassifier.classify("Main.KT", "x".toByteArray(), forceBinary = false).type shouldBe "kt"
    }

    it("is txt for a basename without an extension") {
      ContentClassifier.classify("Makefile", "all:".toByteArray(), forceBinary = false).type shouldBe "txt"
      ContentClassifier.classify("trailing.", "x".toByteArray(), forceBinary = false).type shouldBe "txt"
    }

    it("is binary when forced, unless the extension is an image") {
      ContentClassifier.classify("lib.class", bytes(0xCA, 0, 0xBE), forceBinary = true).type shouldBe "binary"
      ContentClassifier.classify("logo.png", bytes(0x89, 0, 0x4E), forceBinary = true).type shouldBe "png"
      ContentClassifier.classify("photo.JPEG", bytes(0xFF, 0), forceBinary = true).type shouldBe "jpeg"
    }
  }

  describe("looksBinary") {
    it("is true for a NUL within the first 8000 bytes") {
      ContentClassifier.looksBinary(bytes(0x41, 0, 0x42)) shouldBe true
      ContentClassifier.looksBinary(ByteArray(8000) { if (it == 7999) 0 else 0x41 }) shouldBe true
    }

    it("is false for text and for a NUL only after the first 8000 bytes") {
      ContentClassifier.looksBinary("plain text".toByteArray()) shouldBe false
      ContentClassifier.looksBinary(ByteArray(8001) { if (it == 8000) 0 else 0x41 }) shouldBe false
      ContentClassifier.looksBinary(ByteArray(0)) shouldBe false
    }
  }

  describe("charset") {
    it("is UTF-8 for empty content") {
      ContentClassifier.classify("a.txt", ByteArray(0), forceBinary = false).charset shouldBe "UTF-8"
    }

    it("is UTF-8 for a BOM-prefixed file") {
      ContentClassifier.classify("a.txt", bytes(0xEF, 0xBB, 0xBF, 0x41), forceBinary = false).charset shouldBe "UTF-8"
    }

    it("is UTF-8 for strictly valid UTF-8 such as Japanese") {
      ContentClassifier.classify("a.txt", "日本語".toByteArray(), forceBinary = false).charset shouldBe "UTF-8"
    }

    it("is null for Shift_JIS and Latin-1 bytes") {
      ContentClassifier.classify("a.txt", bytes(0x93, 0xFA), forceBinary = false).charset.shouldBeNull()
      ContentClassifier.classify("a.txt", bytes(0x63, 0x61, 0x66, 0xE9), forceBinary = false).charset.shouldBeNull()
    }
  }

  describe("bytes") {
    it("are returned unchanged, CRLF and missing trailing newline included") {
      val original = "a\r\nb\r\nc".toByteArray()

      ContentClassifier.classify("a.txt", original, forceBinary = false).bytes shouldBeSameInstanceAs original
    }
  }
})
