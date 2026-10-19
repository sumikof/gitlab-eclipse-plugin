package com.gitlab.eclipse.mergerequests.diff

import com.gitlab.eclipse.api.BoundedBytes
import com.gitlab.eclipse.api.ConnectionSnapshot
import com.gitlab.eclipse.api.GitLabApiClient
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

private val CONNECTION = ConnectionSnapshot("https://gl.example", "tok", "fp", 1L)

class RawFileFetcherTest : DescribeSpec({
  describe("encodePath") {
    it("percent-encodes slashes, spaces and UTF-8 bytes") {
      RawFileFetcher.encodePath("a/b c/日本.kt") shouldBe "a%2Fb%20c%2F%E6%97%A5%E6%9C%AC.kt"
    }

    it("encodes URL-special characters and keeps only RFC 3986 unreserved ones") {
      RawFileFetcher.encodePath("%#?+&=;:@!") shouldBe "%25%23%3F%2B%26%3D%3B%3A%40%21"
      RawFileFetcher.encodePath("AZaz09-._~") shouldBe "AZaz09-._~"
    }
  }

  describe("fetch") {
    it("GETs the raw endpoint of the target project with ref = the side's SHA") {
      val api = mockk<GitLabApiClient>()
      val path = slot<String>()
      val query = slot<Map<String, String>>()
      val bytes = BoundedBytes.Ok(byteArrayOf(7))
      every { api.fetchBytes(capture(path), capture(query), 1024, CONNECTION) } returns bytes

      val result = RawFileFetcher(api).fetch(42L, SideRef("src/a b.kt", "c".repeat(40)), 1024, CONNECTION)

      result shouldBeSameInstanceAs bytes
      path.captured shouldBe "/projects/42/repository/files/src%2Fa%20b.kt/raw"
      query.captured shouldBe mapOf("ref" to "c".repeat(40))
    }
  }
})
