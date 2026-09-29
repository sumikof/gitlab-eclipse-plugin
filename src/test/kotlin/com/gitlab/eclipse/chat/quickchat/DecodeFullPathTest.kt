package com.gitlab.eclipse.chat.quickchat

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

/** Design A25: each `/`-separated segment is percent-decoded exactly once as UTF-8; fail closed. */
class DecodeFullPathTest : DescribeSpec({

  describe("decodeFullPath") {
    it("leaves a plain path unchanged") {
      decodeFullPath("group/sub/project") shouldBe "group/sub/project"
    }

    it("decodes non-ASCII UTF-8 escapes in every segment") {
      decodeFullPath("%E3%82%B0%E3%83%AB%E3%83%BC%E3%83%97/pr%C3%B6ject") shouldBe "グループ/pröject"
    }

    it("decodes exactly once") {
      decodeFullPath("group/a%2541") shouldBe "group/a%41"
    }

    it("does not turn + into a space") {
      decodeFullPath("group/a+b") shouldBe "group/a+b"
    }

    it("keeps literal non-BMP characters next to an escape intact") {
      decodeFullPath("g/😀%C3%B6😀") shouldBe "g/😀ö😀"
    }

    it("accepts lowercase hex digits") {
      decodeFullPath("g/%c3%b6") shouldBe "g/ö"
    }

    it("returns null for a truncated escape") {
      decodeFullPath("group/abc%4") shouldBe null
      decodeFullPath("group/abc%") shouldBe null
    }

    it("returns null for a non-hex escape") {
      decodeFullPath("group/%G1") shouldBe null
    }

    it("returns null for an escape sequence that is not valid UTF-8") {
      decodeFullPath("group/%C3") shouldBe null
      decodeFullPath("group/%FF") shouldBe null
    }

    it("returns null when a segment decodes to a slash (would change the path structure)") {
      decodeFullPath("group/a%2Fb") shouldBe null
    }
  }
})
