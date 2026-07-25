package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class PathSegmentEncoderTest : DescribeSpec({
  extensions(LoggingKotestExtension)
  it("leaves unreserved chars untouched") {
    PathSegmentEncoder.encodePath("src/main/App-1.0_final.kt") shouldBe "src/main/App-1.0_final.kt"
  }
  it("preserves path separators, encodes spaces and reserved chars per segment") {
    PathSegmentEncoder.encodePath("dir a/b#c?d.txt") shouldBe "dir%20a/b%23c%3Fd.txt"
  }
  it("encodes a literal percent to %25 (raw path, not pre-encoded)") {
    PathSegmentEncoder.encodePath("weird%41.txt") shouldBe "weird%2541.txt"
  }
  it("encodes non-ASCII as UTF-8 bytes") {
    PathSegmentEncoder.encodePath("café.txt") shouldBe "caf%C3%A9.txt"
  }
  it("encodeSegment does not preserve slashes") {
    PathSegmentEncoder.encodeSegment("a/b") shouldBe "a%2Fb"
  }
  it("preserves ~ and encodes sub-delims") {
    PathSegmentEncoder.encodePath("a~b+c*d!e") shouldBe "a~b%2Bc%2Ad%21e"
  }
  it("preserves empty segments and the empty string") {
    PathSegmentEncoder.encodePath("/a//b") shouldBe "/a//b"
    PathSegmentEncoder.encodeSegment("") shouldBe ""
  }
})
