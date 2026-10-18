package com.gitlab.eclipse.preferences.storage

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class GitLabInstanceKeyTest : DescribeSpec({
  fun canonicalOf(url: String?): String? = GitLabInstanceKey.of(url)?.canonical

  describe("GitLabInstanceKey.of") {
    it("keeps scheme, host and path of a plain URL") {
      canonicalOf("https://gitlab.com") shouldBe "https://gitlab.com"
      canonicalOf("https://example.com:8443/gitlab") shouldBe "https://example.com:8443/gitlab"
    }

    it("maps spellings of the same instance to one key") {
      listOf(
        "https://GitLab.COM/",
        "HTTPS://gitlab.com:443//",
        " https://gitlab.com ",
        "https://gitlab.com/./",
      ).forEach { canonicalOf(it) shouldBe "https://gitlab.com" }
      canonicalOf("http://example.com:80/") shouldBe "http://example.com"
      canonicalOf("https://example.com/gitlab/") shouldBe "https://example.com/gitlab"
    }

    it("keeps instances that differ in scheme, port or path apart") {
      canonicalOf("http://gitlab.com") shouldBe "http://gitlab.com"
      canonicalOf("https://gitlab.com:8443") shouldBe "https://gitlab.com:8443"
      canonicalOf("https://example.com/GitLab") shouldBe "https://example.com/GitLab"
      (GitLabInstanceKey.of("https://example.com/a") == GitLabInstanceKey.of("https://example.com/b")) shouldBe false
    }

    it("refuses a URL carrying credentials, including a host disguised as credentials") {
      canonicalOf("https://user:secret@example.com") shouldBe null
      canonicalOf("https://gitlab.com@evil.example") shouldBe null
    }

    it("refuses a URL that is not an http(s) base URL") {
      listOf(
        "https://example.com/?a=1",
        "https://example.com/#x",
        "ftp://example.com",
        "example.com",
        "https:///path",
        "",
        "   ",
        null,
        "https://exa mple.com",
      ).forEach { canonicalOf(it) shouldBe null }
    }

    it("keeps an IPv6 host and refuses a host java.net.URI cannot parse as a server") {
      canonicalOf("https://[::1]:8443/") shouldBe "https://[::1]:8443"
      canonicalOf("https://my_host") shouldBe null
    }

    it("keeps a percent-encoded path as written and refuses non-ASCII the storage cannot name") {
      canonicalOf("https://example.com/%E3%82%AE/") shouldBe "https://example.com/%E3%82%AE"
      canonicalOf("https://example.com/ギット") shouldBe null
    }

    it("equals the gitlab.com constant for the default URL") {
      GitLabInstanceKey.of("https://gitlab.com/") shouldBe GitLabInstanceKey.GITLAB_COM
    }
  }
})
