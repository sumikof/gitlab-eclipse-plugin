package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import com.gitlab.eclipse.navigation.PathSegmentEncoder
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * RFC 3986 percent-*decoding*, deliberately not [java.net.URLDecoder]: that class implements
 * `application/x-www-form-urlencoded`, which turns a literal `+` into a space — the exact
 * mismatch design §7.3a rule 7 exists to keep out of a query-component encoder/decoder pair.
 */
private fun percentDecode(s: String): String {
  val bytes = ArrayList<Byte>(s.length)
  var i = 0
  while (i < s.length) {
    val c = s[i]
    if (c == '%' && i + 3 <= s.length) {
      bytes.add(s.substring(i + 1, i + 3).toInt(16).toByte())
      i += 3
    } else {
      bytes.add(c.code.toByte())
      i += 1
    }
  }
  return String(bytes.toByteArray(), Charsets.UTF_8)
}

class WebviewQueryBuilderTest : DescribeSpec({
  extensions(LoggingKotestExtension)

  describe("append") {
    it("returns null for an opaque URI") {
      WebviewQueryBuilder.append("mailto:foo@example.com", mapOf("uri" to "x")).shouldBeNull()
    }

    it("returns null for a relative (non-absolute) URI") {
      WebviewQueryBuilder.append("root/flow?x=1", mapOf("uri" to "x")).shouldBeNull()
    }

    it("returns null for a syntactically invalid URI string") {
      WebviewQueryBuilder.append("http://exa mple.com/path", mapOf("uri" to "x")).shouldBeNull()
    }

    it("returns the base unchanged when there are no parameters to append") {
      val base = "https://gitlab.example.com/root/flow"
      WebviewQueryBuilder.append(base, emptyMap()) shouldBe base
    }

    it("leaves unreserved characters in the appended value unescaped") {
      val base = "https://gitlab.example.com/root/flow"

      WebviewQueryBuilder.append(base, mapOf("uri" to "AZaz09-._~")) shouldBe "$base?uri=AZaz09-._~"
    }

    it("produces the exact RFC 3986 percent-encoding for & # = ? space non-ASCII + and %, and round-trips") {
      val base = "https://gitlab.example.com/root/flow"
      val original = "a&b#c=d?e f+g%h日本語i"
      val expectedEncoded = "a%26b%23c%3Dd%3Fe%20f%2Bg%25h%E6%97%A5%E6%9C%AC%E8%AA%9Ei"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to original))

      result shouldBe "$base?uri=$expectedEncoded"
      val nonNullResult = result.shouldNotBeNull()
      percentDecode(nonNullResult.removePrefix("$base?uri=")) shouldBe original
    }

    it("preserves an existing raw query byte-for-byte, including %26 %3D %25") {
      val base = "https://gitlab.example.com/root/mcp?existing=%26%3D%25"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/mcp?existing=%26%3D%25&uri=x"
    }

    it("keep-behaviour: appends without removing or overwriting an existing uri parameter") {
      val base = "https://gitlab.example.com/root/flow?uri=old-value"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "new-value"))

      result shouldBe "https://gitlab.example.com/root/flow?uri=old-value&uri=new-value"
    }

    it("places the appended query before an existing fragment when there is no existing query") {
      val base = "https://gitlab.example.com/root/flow#frag"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/flow?uri=x#frag"
    }

    it("preserves a fragment after an existing query") {
      val base = "https://gitlab.example.com/root/flow?a=1#frag"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/flow?a=1&uri=x#frag"
    }

    it("does not produce '?&' when the base URI ends in a bare '?'") {
      val base = "https://gitlab.example.com/root/flow?"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/flow?uri=x"
    }

    it("preserves a bare trailing '#' (empty raw fragment) verbatim") {
      val base = "https://gitlab.example.com/root/flow#"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/flow?uri=x#"
    }

    it("encodes a value identically to PathSegmentEncoder.encodeSegment (shared RFC 3986 primitive)") {
      val base = "https://gitlab.example.com/root/flow"
      val shared = "AZaz09-._~ /?#[]@!$&'()*+,;=%\n日本語"

      val result = WebviewQueryBuilder.append(base, mapOf("k" to shared))

      val fromQueryBuilder = result.shouldNotBeNull().removePrefix("$base?k=")
      val fromPathSegmentEncoder = PathSegmentEncoder.encodeSegment(shared)
      fromQueryBuilder shouldBe fromPathSegmentEncoder
      fromQueryBuilder shouldBe "AZaz09-._~%20%2F%3F%23%5B%5D%40%21%24%26%27%28%29%2A%2B%2C%3B%3D%25%0A%E6%97%A5%E6%9C%AC%E8%AA%9E"
    }
  }
})
