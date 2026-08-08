package com.gitlab.eclipse.lsp.webview

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.net.URLDecoder

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

    it("round-trips a value containing & # = ? space non-ASCII + and %") {
      val base = "https://gitlab.example.com/root/flow"
      val original = "a&b#c=d?e f+g%h日本語i"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to original))

      result.shouldNotBeNull()
      val encoded = result!!.removePrefix("$base?uri=")
      URLDecoder.decode(encoded, "UTF-8") shouldBe original
    }

    it("preserves an existing raw query byte-for-byte, including %26 %3D %25") {
      val base = "https://gitlab.example.com/root/mcp?existing=%26%3D%25"

      val result = WebviewQueryBuilder.append(base, mapOf("uri" to "x"))

      result shouldBe "https://gitlab.example.com/root/mcp?existing=%26%3D%25&uri=x"
    }

    it("appends without removing or overwriting an existing uri parameter") {
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
  }
})
