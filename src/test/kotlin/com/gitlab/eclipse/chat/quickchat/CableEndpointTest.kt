package com.gitlab.eclipse.chat.quickchat

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.net.URI

class CableEndpointTest : DescribeSpec({
  it("maps https to wss and keeps the default port out of the origin") {
    CableEndpoint.of("https://gitlab.com") shouldBe CableEndpoint(URI("wss://gitlab.com/-/cable"), "https://gitlab.com")
  }
  it("maps http to ws") {
    CableEndpoint.of("http://gitlab.local") shouldBe CableEndpoint(URI("ws://gitlab.local/-/cable"), "http://gitlab.local")
  }
  it("keeps a sub path, with or without a trailing slash") {
    CableEndpoint.of("https://example.com/gitlab").uri shouldBe URI("wss://example.com/gitlab/-/cable")
    CableEndpoint.of("https://example.com/gitlab/").uri shouldBe URI("wss://example.com/gitlab/-/cable")
  }
  it("keeps an explicit non-default port in both") {
    CableEndpoint.of("https://example.com:8443/gl") shouldBe
      CableEndpoint(URI("wss://example.com:8443/gl/-/cable"), "https://example.com:8443")
  }
  it("drops a default port written explicitly from the origin") {
    CableEndpoint.of("https://example.com:443").origin shouldBe "https://example.com"
    CableEndpoint.of("http://example.com:80").origin shouldBe "http://example.com"
  }
  it("drops user info, query and fragment") {
    val endpoint = CableEndpoint.of("https://user:secret@example.com/gl?x=1#y")
    endpoint shouldBe CableEndpoint(URI("wss://example.com/gl/-/cable"), "https://example.com")
  }
  it("keeps the instance host when the path starts with // (no network-path reference)") {
    val endpoint = CableEndpoint.of("https://gitlab.example.com//attacker.example")
    endpoint.uri.host shouldBe "gitlab.example.com"
    endpoint.uri.port shouldBe -1
    endpoint.uri.scheme shouldBe "wss"
    endpoint.uri.rawPath shouldBe "//attacker.example/-/cable"
    endpoint.uri.toString() shouldBe "wss://gitlab.example.com//attacker.example/-/cable"
    endpoint.origin shouldBe "https://gitlab.example.com"
  }
  it("keeps the instance host and port when the path starts with ///") {
    val endpoint = CableEndpoint.of("https://gitlab.example.com:8443///x")
    endpoint.uri.host shouldBe "gitlab.example.com"
    endpoint.uri.port shouldBe 8443
    endpoint.uri.rawPath.endsWith("/-/cable") shouldBe true
    endpoint.uri.rawPath shouldBe "///x/-/cable"
  }
  it("does not double-encode a percent-encoded sub path") {
    CableEndpoint.of("https://example.com/my%20gitlab").uri.rawPath shouldBe "/my%20gitlab/-/cable"
    CableEndpoint.of("https://example.com/my%20gitlab/").uri.rawPath shouldBe "/my%20gitlab/-/cable"
  }
  it("keeps an escaped reserved character in the sub path") {
    CableEndpoint.of("https://example.com/git%2Flab").uri.rawPath shouldBe "/git%2Flab/-/cable"
    CableEndpoint.of("https://example.com/git%2Flab/").uri.rawPath shouldBe "/git%2Flab/-/cable"
  }
  it("builds for an IPv6 host with the same host and port") {
    val endpoint = CableEndpoint.of("https://[::1]:8443/gl")
    endpoint.uri.host shouldBe "[::1]"
    endpoint.uri.port shouldBe 8443
    endpoint.uri shouldBe URI("wss://[::1]:8443/gl/-/cable")
    endpoint.origin shouldBe "https://[::1]:8443"
  }
  it("lets nothing but IllegalArgumentException escape for exotic hosts, without echoing the input") {
    listOf(
      "https://[fe80::1%25eth0]/gl",
      "https://[fe80::1%eth0]",
      "https://[fe80::1%25]:8443",
      "https://[::ffff:1.2.3.4%x]/",
      "https://[fe80::1%e_t-h~0]/",
      "https://[v1.x]/",
      "https://a_b.example/",
      "https://ho%41st/",
    ).forEach { url ->
      val failure = runCatching { CableEndpoint.of(url) }.exceptionOrNull()
      if (failure != null) {
        failure.javaClass shouldBe IllegalArgumentException::class.java
        failure.message.orEmpty() shouldNotContain url
        failure.cause shouldBe null
      }
    }
  }
  it("rejects other schemes and garbage without echoing the input") {
    listOf("ftp://example.com", "example.com", "https://", "::").forEach { url ->
      val e = shouldThrow<IllegalArgumentException> { CableEndpoint.of(url) }
      e.message.orEmpty() shouldNotContain url
    }
  }
})
