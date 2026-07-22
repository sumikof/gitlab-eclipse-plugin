package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.api.GitLabConfigurationException
import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Single egress seam: every native REST client obtains its [HttpClient] here so TLS
 * and proxy configuration are centralized. Composes an SSLContext from three axes —
 * client KeyManagers (mTLS), TrustManagers (custom CA / trust-all / default), and
 * hostname verification (kept ON) — and attaches proxy auth when credentials are set.
 */
class GitLabHttpClientFactory(
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val proxyManager: LanguageServerProxyManager = service(),
  private val tlsMaterialLoader: TlsMaterialLoader = service(),
) {
  fun currentSnapshot(): EgressConfigSnapshot = EgressConfigSnapshot(
    ignoreCertificateErrors = preferenceStore.getBoolean(PreferenceConstants.IGNORE_CERTIFICATE_ERRORS),
    caCertificatePath = preferenceStore.getString(PreferenceConstants.CA_CERTIFICATE).blankToNull(),
    clientCertificatePath = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE).blankToNull(),
    clientCertificateKeyPath = preferenceStore.getString(PreferenceConstants.CLIENT_CERTIFICATE_KEY).blankToNull(),
    proxy = proxyManager.getHttpsProxyConfig(),
  )

  fun create(snapshot: EgressConfigSnapshot): HttpClient {
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))

    buildSslContext(snapshot)?.let { builder.sslContext(it) }

    snapshot.proxy?.let { proxy ->
      builder.proxy(BypassAwareProxySelector(proxy))
      if (proxy.username != null && proxy.password != null) {
        builder.authenticator(GitLabProxyAuthenticator(proxy))
      }
    }

    return builder.build()
  }

  /**
   * Composes an SSLContext from the three egress axes, or null when everything is
   * default (so the client keeps the JDK default TLS). ignore-cert supersedes a
   * custom CA. Hostname verification is never disabled here (chain-only trust-all).
   */
  internal fun buildSslContext(snapshot: EgressConfigSnapshot): SSLContext? {
    val keyManagers: Array<KeyManager>? = when {
      snapshot.clientCertificatePath != null && snapshot.clientCertificateKeyPath != null ->
        tlsMaterialLoader.loadKeyManagers(snapshot.clientCertificatePath, snapshot.clientCertificateKeyPath)
      snapshot.clientCertificatePath != null || snapshot.clientCertificateKeyPath != null ->
        throw GitLabConfigurationException(
          "Both the client certificate and its key must be set (or neither).",
        )
      else -> null
    }

    val trustManagers: Array<TrustManager>? = when {
      // ignore-cert supersedes a configured CA.
      snapshot.ignoreCertificateErrors -> arrayOf(trustAllManager())
      snapshot.caCertificatePath != null -> tlsMaterialLoader.loadTrustManagers(snapshot.caCertificatePath)
      else -> null
    }

    if (keyManagers == null && trustManagers == null) return null
    return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, SecureRandom()) }
  }

  // Trust-all bypasses certificate CHAIN validation only. It does NOT disable hostname
  // (SNI/endpoint-identification) verification, so a mismatched CN/SAN still fails.
  private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
    // Empty by design: accepting every chain IS the contract of a trust-all manager.
    // An X509TrustManager rejects by throwing, so returning normally from an empty body
    // is the only way to express "do not validate". Any statement added here would
    // re-introduce the validation the user explicitly opted out of.
    @Suppress("EmptyFunctionBlock")
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}

    // Empty for the same reason as checkClientTrusted above.
    @Suppress("EmptyFunctionBlock")
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  }

  private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

  companion object {
    private const val CONNECT_TIMEOUT_SECONDS = 30L
  }
}
