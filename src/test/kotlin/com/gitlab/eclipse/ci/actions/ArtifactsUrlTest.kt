package com.gitlab.eclipse.ci.actions

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class ArtifactsUrlTest : DescribeSpec({
  describe("buildArtifactsDownloadUrl") {
    it("returns null for a null webUrl") {
      buildArtifactsDownloadUrl(null).shouldBeNull()
    }
    it("returns null for an empty webUrl") {
      buildArtifactsDownloadUrl("").shouldBeNull()
    }
    it("returns null for a blank webUrl") {
      buildArtifactsDownloadUrl("   ").shouldBeNull()
    }
    it("appends the artifacts-download suffix to a valid https webUrl") {
      buildArtifactsDownloadUrl("https://gitlab.com/g/p/-/jobs/5") shouldBe
        "https://gitlab.com/g/p/-/jobs/5/artifacts/download?file_type=archive"
    }
    it("appends the artifacts-download suffix to a valid http webUrl") {
      buildArtifactsDownloadUrl("http://host/x") shouldBe "http://host/x/artifacts/download?file_type=archive"
    }
    it("returns null for a non-http(s) scheme") {
      buildArtifactsDownloadUrl("ftp://h/x").shouldBeNull()
    }
    it("returns null for a file scheme") {
      buildArtifactsDownloadUrl("file:///x").shouldBeNull()
    }
    it("returns null when the scheme has no host") {
      buildArtifactsDownloadUrl("https:///path").shouldBeNull()
    }
    it("returns null for a relative path") {
      buildArtifactsDownloadUrl("/g/p/jobs/5").shouldBeNull()
    }
    it("returns null for garbage input") {
      buildArtifactsDownloadUrl("not a url").shouldBeNull()
    }
    it("returns null when URI.create fails to parse the webUrl") {
      buildArtifactsDownloadUrl("http://h/ x").shouldBeNull()
    }
    it("accepts an upper-case scheme and preserves the original webUrl verbatim") {
      buildArtifactsDownloadUrl("HTTPS://gitlab.com/x").shouldNotBeNull()
    }
  }
})
