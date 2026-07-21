package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import com.gitlab.eclipse.preferences.PreferenceConstants
import io.kotest.assertions.throwables.shouldThrow
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
  val loader = TlsMaterialLoader()
  val factory = GitLabHttpClientFactory(prefs, proxyManager, loader)

  fun tls(name: String) =
    java.nio.file.Path.of(GitLabHttpClientFactoryTest::class.java.getResource("/tls/$name")!!.toURI()).toString()

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

  describe("buildSslContext") {
    it("returns null when nothing TLS-related is configured") {
      factory.buildSslContext(EgressConfigSnapshot(false, null, null, null, null)) shouldBe null
    }
    it("returns a context for ignore-cert") {
      factory.buildSslContext(EgressConfigSnapshot(true, null, null, null, null)).shouldNotBeNull()
    }
    it("returns a context for a custom CA") {
      factory.buildSslContext(EgressConfigSnapshot(false, tls("ca.cert.pem"), null, null, null)).shouldNotBeNull()
    }
    it("returns a context for client mTLS (cert + key)") {
      factory.buildSslContext(
        EgressConfigSnapshot(false, null, tls("client_rsa.cert.pem"), tls("rsa_pkcs1.key.pem"), null),
      ).shouldNotBeNull()
    }
    it("throws a config error when only one of cert/key is set") {
      shouldThrow<GitLabConfigurationException> {
        factory.buildSslContext(EgressConfigSnapshot(false, null, tls("client_rsa.cert.pem"), null, null))
      }
    }
  }

  describe("create — proxy authenticator") {
    it("attaches an authenticator only when the proxy has credentials") {
      val withCreds = EgressConfigSnapshot(false, null, null, null, ProxyConfig("p", 8080, emptyList(), "u", "pw"))
      factory.create(withCreds).authenticator().isPresent shouldBe true
      val noCreds = EgressConfigSnapshot(false, null, null, null, ProxyConfig("p", 8080, emptyList(), null, null))
      factory.create(noCreds).authenticator().isPresent shouldBe false
    }
  }
})
