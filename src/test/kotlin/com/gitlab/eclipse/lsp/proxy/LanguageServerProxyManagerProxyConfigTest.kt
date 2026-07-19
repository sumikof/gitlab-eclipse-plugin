package com.gitlab.eclipse.lsp.proxy

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.eclipse.core.internal.net.ProxyData
import org.eclipse.core.net.proxy.IProxyData
import org.eclipse.core.net.proxy.IProxyService

class LanguageServerProxyManagerProxyConfigTest : DescribeSpec({
  describe("getHttpsProxyConfig") {
    it("returns structured config with credentials when auth is required") {
      val https = mockk<IProxyData> {
        every { host } returns "proxy.internal"
        every { port } returns 8080
        every { isRequiresAuthentication } returns true
        every { userId } returns "alice"
        every { password } returns "s3cret"
      }
      val service = mockk<IProxyService> {
        every { getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns https
        every { nonProxiedHosts } returns arrayOf("*.example.com", "localhost")
      }
      val manager = LanguageServerProxyManager(service)

      val config = manager.getHttpsProxyConfig()

      config shouldBe ProxyConfig("proxy.internal", 8080, listOf("*.example.com", "localhost"), "alice", "s3cret")
      config.toString().contains("s3cret") shouldBe false
    }

    it("returns null when no proxy configured") {
      val service = mockk<IProxyService> {
        every { getProxyData(any()) } returns null
        every { nonProxiedHosts } returns emptyArray()
      }
      LanguageServerProxyManager(service).getHttpsProxyConfig() shouldBe null
    }
  }
})
