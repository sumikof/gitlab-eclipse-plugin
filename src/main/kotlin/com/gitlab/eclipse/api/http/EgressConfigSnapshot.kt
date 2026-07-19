package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig

/**
 * Immutable snapshot of the egress-affecting configuration. The HTTP client is
 * rebuilt whenever this value changes (see GitLabHttpClient), so a settings-page
 * edit takes effect on the next Refresh without an IDE restart.
 *
 * PR1 uses [ignoreCertificateErrors] and [proxy] (no auth). The cert/key paths are
 * captured but only consumed by PR2 (native mTLS).
 */
data class EgressConfigSnapshot(
  val ignoreCertificateErrors: Boolean,
  val caCertificatePath: String?,
  val clientCertificatePath: String?,
  val clientCertificateKeyPath: String?,
  val proxy: ProxyConfig?,
)
