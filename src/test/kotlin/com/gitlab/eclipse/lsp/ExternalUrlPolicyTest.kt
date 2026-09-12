package com.gitlab.eclipse.lsp

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ExternalUrlPolicyTest : DescribeSpec({
  describe("isBrowsableExternalUrl") {
    it("returns false for a null url") {
      isBrowsableExternalUrl(null) shouldBe false
    }
    it("returns false for an empty url") {
      isBrowsableExternalUrl("") shouldBe false
    }
    it("returns false for a blank url") {
      isBrowsableExternalUrl("   ") shouldBe false
    }
    it("returns false when URI.create fails to parse the url") {
      isBrowsableExternalUrl("http://h/ x") shouldBe false
    }
    it("returns false for a non-http(s) scheme") {
      isBrowsableExternalUrl("ftp://h/x") shouldBe false
    }
    it("returns false for a file scheme") {
      isBrowsableExternalUrl("file:///etc/passwd") shouldBe false
    }
    it("returns false for a javascript scheme, which matters for security") {
      isBrowsableExternalUrl("javascript:alert(1)") shouldBe false
    }
    it("returns false for a host-bearing javascript scheme, isolating the scheme check") {
      isBrowsableExternalUrl("javascript://gitlab.com/%0Aalert(1)") shouldBe false
    }
    it("returns false for a host-bearing file scheme, isolating the scheme check") {
      isBrowsableExternalUrl("file://localhost/etc/passwd") shouldBe false
    }
    it("returns false for a data scheme") {
      isBrowsableExternalUrl("data:text/html,<script>alert(1)</script>") shouldBe false
    }
    it("returns false for a vbscript scheme") {
      isBrowsableExternalUrl("vbscript:msgbox(1)") shouldBe false
    }
    it("returns false for a jar scheme") {
      isBrowsableExternalUrl("jar:file:///x.jar!/y") shouldBe false
    }
    it("returns false for a relative path") {
      isBrowsableExternalUrl("/foo/bar") shouldBe false
    }
    it("returns false for a scheme-less host-looking string") {
      isBrowsableExternalUrl("example.com/x") shouldBe false
    }
    it("returns false when the scheme has no host") {
      isBrowsableExternalUrl("http:///path") shouldBe false
    }
    it("returns false for an empty https url with no host") {
      isBrowsableExternalUrl("https://") shouldBe false
    }
    it("returns true for an absolute https url with a query and fragment") {
      isBrowsableExternalUrl("https://gitlab.com/g/p/-/issues/1?token=x#frag") shouldBe true
    }
    it("returns true for an upper-case HTTPS scheme") {
      isBrowsableExternalUrl("HTTPS://gitlab.com/x") shouldBe true
    }
    it("returns true for a mixed-case HtTp scheme") {
      isBrowsableExternalUrl("HtTp://h/x") shouldBe true
    }
    it("returns true for a plain absolute http url") {
      isBrowsableExternalUrl("http://gitlab.com/x") shouldBe true
    }
    it("returns false when the authority carries userinfo disguising the real host") {
      isBrowsableExternalUrl("https://gitlab.com@evil.example/x") shouldBe false
    }
    it("returns true for a normal https url with no userinfo") {
      isBrowsableExternalUrl("https://gitlab.com/x") shouldBe true
    }
  }
})
