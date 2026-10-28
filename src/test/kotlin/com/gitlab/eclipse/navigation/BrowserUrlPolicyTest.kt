package com.gitlab.eclipse.navigation

import com.gitlab.eclipse.lsp.schemeOf
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

private fun allowedForm(url: String?): String =
  browserUrlVerdict(url).shouldBeInstanceOf<BrowserUrlVerdict.Allowed>().url.toExternalForm()

private fun refusalOf(url: String?): BrowserUrlRefusal =
  browserUrlVerdict(url).shouldBeInstanceOf<BrowserUrlVerdict.Refused>().reason

class BrowserUrlPolicyTest : DescribeSpec({
  describe("browserUrlVerdict allows") {
    // Compared as toExternalForm strings: URL.equals resolves the host over DNS. java.net.URL lower-cases
    // the scheme, so the expected strings do too; host, path, query and fragment are handed over untouched.
    listOf(
      "http://gitlab.com/x" to "http://gitlab.com/x",
      "https://gitlab.com/x" to "https://gitlab.com/x",
      "HTTPS://Gitlab.com/a" to "https://Gitlab.com/a",
      "hTtP://h.example/x" to "http://h.example/x",
      "https://gitlab.com:8443/a/" to "https://gitlab.com:8443/a/",
      "https://gitlab.com" to "https://gitlab.com",
      "https://gitlab.com/g/p/-/issues/1?token=x&a=b#frag" to "https://gitlab.com/g/p/-/issues/1?token=x&a=b#frag",
      "http://localhost:3000/x" to "http://localhost:3000/x",
      "https://[::1]:3000/" to "https://[::1]:3000/",
      "https://xn--r8jz45g.jp/x" to "https://xn--r8jz45g.jp/x",
    ).forEach { (input, expected) ->
      it("allows $input and hands over $expected") {
        allowedForm(input) shouldBe expected
      }
    }
  }

  describe("browserUrlVerdict refuses") {
    listOf(
      null to BrowserUrlRefusal.BLANK,
      "" to BrowserUrlRefusal.BLANK,
      "   " to BrowserUrlRefusal.BLANK,
      " https://gitlab.com/x" to BrowserUrlRefusal.UNPARSEABLE,
      "https://gitlab.com/x " to BrowserUrlRefusal.UNPARSEABLE,
      "https://gitlab.com/a b" to BrowserUrlRefusal.UNPARSEABLE,
      "https://gitlab.com/%zz" to BrowserUrlRefusal.UNPARSEABLE,
      "gitlab.com/x" to BrowserUrlRefusal.NO_SCHEME,
      "//gitlab.com/x" to BrowserUrlRefusal.NO_SCHEME,
      "/foo/bar" to BrowserUrlRefusal.NO_SCHEME,
      "javascript:alert(1)" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "javascript://gitlab.com/%0Aalert(1)" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "JavaScript:alert(1)" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "file:///etc/passwd" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "file://localhost/etc/passwd" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "data:text/html;base64,PHNjcmlwdD4=" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      // Raw `<` / `>` are illegal in a URI, so this one never gets as far as its scheme.
      "data:text/html,<script>alert(1)</script>" to BrowserUrlRefusal.UNPARSEABLE,
      "vbscript:msgbox(1)" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "mailto:someone@example.com" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "ftp://h.example/x" to BrowserUrlRefusal.DISALLOWED_SCHEME,
      "http:///x" to BrowserUrlRefusal.NO_HOST,
      "https:x" to BrowserUrlRefusal.NO_HOST,
      "https://例え.jp/x" to BrowserUrlRefusal.NO_HOST,
      "https://my_host.example/x" to BrowserUrlRefusal.NO_HOST,
      "https://u:p@gitlab.com/" to BrowserUrlRefusal.USERINFO,
      "https://gitlab.com@evil.example/" to BrowserUrlRefusal.USERINFO,
    ).forEach { (input, reason) ->
      it("refuses ${input?.let { "\"$it\"" } ?: "null"} as $reason") {
        refusalOf(input) shouldBe reason
      }
    }

    it("labels the refused scheme with the bounded vocabulary, never the raw scheme") {
      val refused = browserUrlVerdict("glpat-abc:xyz").shouldBeInstanceOf<BrowserUrlVerdict.Refused>()

      refused.reason shouldBe BrowserUrlRefusal.DISALLOWED_SCHEME
      refused.schemeLabel shouldBe "other"
    }
  }

  describe("Allowed") {
    it("does not print the URL it carries") {
      val verdict = browserUrlVerdict("https://secret-host.example/x?token=abc#frag")

      verdict.toString() shouldNotContain "secret-host"
      verdict.toString() shouldNotContain "token"
      verdict.toString() shouldNotContain "frag"
    }
  }

  describe("schemeLabel") {
    it("names a scheme from the fixed vocabulary, lower-cased") {
      schemeLabel("HTTPS://gitlab.com/x") shouldBe "https"
      schemeLabel("http://gitlab.com/x") shouldBe "http"
      schemeLabel("JavaScript:alert(1)") shouldBe "javascript"
      schemeLabel("vbscript:x") shouldBe "vbscript"
      schemeLabel("data:text/plain,x") shouldBe "data"
      schemeLabel("file:///etc/passwd") shouldBe "file"
      schemeLabel("mailto:a@b.example") shouldBe "mailto"
      schemeLabel("ftp://h/x") shouldBe "ftp"
      schemeLabel("about:blank") shouldBe "about"
      schemeLabel("blob:https://h/x") shouldBe "blob"
      schemeLabel("jar:file:///x.jar!/y") shouldBe "jar"
      schemeLabel("ssh://git@h/x") shouldBe "ssh"
      schemeLabel("git://h/x") shouldBe "git"
    }

    it("says other for a scheme outside the vocabulary, so a token-shaped scheme never reaches a log") {
      schemeLabel("glpat-abc:xyz") shouldBe "other"
      schemeLabel("x-custom://h/x") shouldBe "other"
    }

    it("says none for null or blank input") {
      schemeLabel(null) shouldBe "none"
      schemeLabel("") shouldBe "none"
      schemeLabel(" ") shouldBe "none"
    }

    it("says relative for a reference without a scheme") {
      schemeLabel("/relative#frag") shouldBe "relative"
      schemeLabel("//gitlab.com/x") shouldBe "relative"
    }

    it("says unparseable when the input is not a URI, without quoting it") {
      schemeLabel("https://evil.example/a b") shouldBe "unparseable"
      schemeLabel("https://evil.example/%zz") shouldBe "unparseable"
    }
  }

  describe("schemeOf (the log label of ShowDocumentLauncher, VulnerabilityLinkPolicy and OpenUrlHandler)") {
    it("is the bounded label, so a token-shaped scheme is logged as other") {
      schemeOf("glpat-abc:xyz") shouldBe "other"
      schemeOf("x-custom://h/x") shouldBe "other"
      schemeOf("JavaScript:alert(1)") shouldBe "javascript"
    }
  }
})
