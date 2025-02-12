package com.gitlab.eclipse.lsp.proxy

import com.gitlab.eclipse.extensions.LoggingKotestExtension
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.eclipse.core.internal.net.ProxyData
import org.eclipse.core.internal.net.ProxyManager

class LanguageServerProxyManagerTest : DescribeSpec({
  val eclipseProxyManager = mockk<ProxyManager>()
  val lsProxyManager = LanguageServerProxyManager()

  extensions(LoggingKotestExtension)

  beforeSpec { mockkStatic(ProxyManager::class) }

  beforeEach {
    every { ProxyManager.getProxyManager() } returns eclipseProxyManager
  }

  afterSpec { unmockkAll() }

  describe("getHttpProxyUrl") {
    it("should return null when no HTTP proxy is set") {
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns null

      lsProxyManager.getHttpProxyUrl() shouldBe null
    }

    it("should return null when HTTP proxy host is null") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns null
      every { proxyData.port } returns 8080
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpProxyUrl() shouldBe null
    }

    it("should return null when HTTP proxy port is invalid") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns -1
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpProxyUrl() shouldBe null
    }

    it("should return correct URL for HTTP proxy without authentication") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8080
      every { proxyData.isRequiresAuthentication } returns false
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpProxyUrl() shouldBe "http://proxy.example.com:8080"
    }

    it("should return correct URL for HTTP proxy with authentication") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8080
      every { proxyData.isRequiresAuthentication } returns true
      every { proxyData.userId } returns "user"
      every { proxyData.password } returns "pass"
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpProxyUrl() shouldBe "http://user:pass@proxy.example.com:8080"
    }

    it("should handle special characters in username and password") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8080
      every { proxyData.isRequiresAuthentication } returns true
      every { proxyData.userId } returns "user@domain"
      every { proxyData.password } returns "p@ss:word"
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpProxyUrl() shouldBe "http://user%40domain:p%40ss%3Aword@proxy.example.com:8080"
    }
  }

  describe("getHttpsProxyUrl") {
    it("should return null when no HTTPS or HTTP proxy is set") {
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns null
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns null

      lsProxyManager.getHttpsProxyUrl() shouldBe null
    }

    it("should return null when HTTPS proxy host is null and no HTTP proxy is set") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns null
      every { proxyData.port } returns 8443
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns proxyData
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns null

      lsProxyManager.getHttpsProxyUrl() shouldBe null
    }

    it("should return null when HTTPS proxy port is invalid and no HTTP proxy is set") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns -1
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns proxyData
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns null

      lsProxyManager.getHttpsProxyUrl() shouldBe null
    }

    it("should return correct URL for HTTPS proxy without authentication") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8443
      every { proxyData.isRequiresAuthentication } returns false
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpsProxyUrl() shouldBe "http://proxy.example.com:8443"
    }

    it("should return correct URL for HTTPS proxy with authentication") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8443
      every { proxyData.isRequiresAuthentication } returns true
      every { proxyData.userId } returns "user"
      every { proxyData.password } returns "pass"
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpsProxyUrl() shouldBe "http://user:pass@proxy.example.com:8443"
    }

    it("should handle special characters in username and password for HTTPS proxy") {
      val proxyData = mockk<ProxyData>()
      every { proxyData.host } returns "proxy.example.com"
      every { proxyData.port } returns 8443
      every { proxyData.isRequiresAuthentication } returns true
      every { proxyData.userId } returns "user@domain"
      every { proxyData.password } returns "p@ss:word"
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns proxyData

      lsProxyManager.getHttpsProxyUrl() shouldBe "http://user%40domain:p%40ss%3Aword@proxy.example.com:8443"
    }

    it("should use HTTP proxy as fallback when HTTPS proxy is not configured") {
      val httpsProxyData = mockk<ProxyData>()
      every { httpsProxyData.host } returns null
      every { eclipseProxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE) } returns httpsProxyData

      val httpProxyData = mockk<ProxyData>()
      every { httpProxyData.host } returns "proxy.example.com"
      every { httpProxyData.port } returns 8080
      every { httpProxyData.isRequiresAuthentication } returns false
      every { eclipseProxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE) } returns httpProxyData

      lsProxyManager.getHttpsProxyUrl() shouldBe "http://proxy.example.com:8080"
    }
  }

  describe("getBypassHosts") {
    it("should return null when no bypass hosts are set") {
      every { eclipseProxyManager.nonProxiedHosts } returns null

      lsProxyManager.getBypassHosts() shouldBe null
    }

    it("should return empty string when bypass hosts list is empty") {
      every { eclipseProxyManager.nonProxiedHosts } returns emptyArray()

      lsProxyManager.getBypassHosts() shouldBe ""
    }

    it("should return comma-separated list of bypass hosts") {
      val bypassHosts = arrayOf("localhost", "127.0.0.1", "*.example.com")
      every { eclipseProxyManager.nonProxiedHosts } returns bypassHosts

      lsProxyManager.getBypassHosts() shouldBe "localhost,127.0.0.1,*.example.com"
    }
  }
})
