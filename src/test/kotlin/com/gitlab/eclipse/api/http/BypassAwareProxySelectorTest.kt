package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

class BypassAwareProxySelectorTest : DescribeSpec({
  val proxy = ProxyConfig("proxy.internal", 8080, listOf("*.example.com", "localhost"), null, null)
  val selector = BypassAwareProxySelector(proxy)

  describe("select") {
    it("returns the proxy for a non-bypassed host") {
      val result = selector.select(URI.create("https://gitlab.com/api/v4/issues"))
      result shouldBe listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.internal", 8080)))
    }
    it("returns NO_PROXY for a wildcard-bypassed host") {
      selector.select(URI.create("https://foo.example.com/x")) shouldBe listOf(Proxy.NO_PROXY)
    }
    it("returns NO_PROXY for an exact-bypassed host") {
      selector.select(URI.create("http://localhost:3000/x")) shouldBe listOf(Proxy.NO_PROXY)
    }
  }
})
