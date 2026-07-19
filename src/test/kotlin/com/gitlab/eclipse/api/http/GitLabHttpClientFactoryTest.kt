package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.eclipse.ui.preferences.ScopedPreferenceStore

class GitLabHttpClientFactoryTest : DescribeSpec({
  val prefs = mockk<ScopedPreferenceStore>(relaxed = true)
  val proxyManager = mockk<LanguageServerProxyManager>()
  val factory = GitLabHttpClientFactory(prefs, proxyManager)

  beforeEach {
    every { prefs.getString(any()) } returns ""
    every { prefs.getBoolean(any()) } returns false
    every { proxyManager.getHttpsProxyConfig() } returns null
  }

  describe("currentSnapshot") {
    it("captures ignore-cert, cert paths and proxy") {
      every { prefs.getBoolean(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS) } returns true
      every { prefs.getString(PreferenceConstants.CLIENT_CERTIFICATE) } returns "/tmp/client.pem"
      every { proxyManager.getHttpsProxyConfig() } returns ProxyConfig("p", 8080, emptyList(), null, null)

      val snap = factory.currentSnapshot()

      snap.ignoreCertificateErrors shouldBe true
      snap.clientCertificatePath shouldBe "/tmp/client.pem"
      snap.proxy.shouldNotBeNull()
    }
    it("blank cert path becomes null") {
      factory.currentSnapshot().clientCertificatePath shouldBe null
    }
  }

  describe("create") {
    it("builds a client with a bypass-aware selector when a proxy is set") {
      val snap = EgressConfigSnapshot(false, null, null, null, ProxyConfig("p", 8080, emptyList(), null, null))
      val client = factory.create(snap)
      client.proxy().isPresent shouldBe true
      client.proxy().get().shouldBeInstanceOf<BypassAwareProxySelector>()
    }
    it("ignore-cert build succeeds and yields a client") {
      factory.create(EgressConfigSnapshot(true, null, null, null, null)).shouldNotBeNull()
    }
  }
})
