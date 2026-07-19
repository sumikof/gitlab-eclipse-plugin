package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.inject.service
import com.gitlab.eclipse.lsp.proxy.LanguageServerProxyManager
import com.gitlab.eclipse.preferences.PreferenceConstants
import org.eclipse.ui.preferences.ScopedPreferenceStore
import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Single egress seam: every native REST client obtains its [HttpClient] here so that
 * TLS and proxy configuration are centralized. PR1 supports ignore-cert and a
 * non-authenticated proxy. PR2 adds mTLS cert/key loading and proxy auth into the
 * SAME factory.
 */
class GitLabHttpClientFactory(
  private val preferenceStore: ScopedPreferenceStore = service(),
  private val proxyManager: LanguageServerProxyManager = service(),
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

    // PR1: ignore-cert only. PR2 will build an mTLS/CA SSLContext from the cert paths here.
    if (snapshot.ignoreCertificateErrors) {
      builder.sslContext(trustAllContext())
    }

    // PR1: non-authenticated proxy. PR2 will add builder.authenticator(...) when proxy has creds.
    snapshot.proxy?.let { builder.proxy(BypassAwareProxySelector(it)) }

    return builder.build()
  }

  private fun trustAllContext(): SSLContext {
    val trustAll = object : X509TrustManager {
      override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
      override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
      override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply {
      init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
    }
  }

  private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

  companion object {
    private const val CONNECT_TIMEOUT_SECONDS = 30L
  }
}
