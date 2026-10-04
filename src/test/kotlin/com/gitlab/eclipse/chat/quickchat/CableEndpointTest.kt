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
  it("rejects other schemes and garbage without echoing the input") {
    listOf("ftp://example.com", "example.com", "https://", "::").forEach { url ->
      val e = shouldThrow<IllegalArgumentException> { CableEndpoint.of(url) }
      e.message.orEmpty() shouldNotContain url
    }
  }
})
