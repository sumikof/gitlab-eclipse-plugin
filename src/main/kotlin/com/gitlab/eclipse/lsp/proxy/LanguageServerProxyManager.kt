package com.gitlab.eclipse.lsp.proxy

import com.gitlab.eclipse.utils.logger
import org.eclipse.core.internal.net.ProxyData
import org.eclipse.core.internal.net.ProxyManager
import org.eclipse.core.net.proxy.IProxyData
import org.eclipse.core.net.proxy.IProxyService
import java.net.URLEncoder

class LanguageServerProxyManager(
  private val proxyManager: IProxyService = ProxyManager.getProxyManager(),
) {
  private val logger by lazy { logger<LanguageServerProxyManager>() }

  fun getHttpProxyUrl(): String? {
    return proxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE)?.let { getProxyUrl(it) }
  }

  fun getHttpsProxyUrl(): String? {
    val httpsProxyUrl = proxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE)?.let { getProxyUrl(it) }

    if (httpsProxyUrl == null) {
      val httpProxyUrl = getHttpProxyUrl()
        ?: return null

      logger.info("Using HTTP proxy URL for HTTPS as HTTPS proxy is not configured.")
      return httpProxyUrl
    }

    return httpsProxyUrl
  }

  fun getBypassHosts(): String? {
    return proxyManager.nonProxiedHosts?.joinToString(",")
  }

  /** Structured HTTPS (falling back to HTTP) proxy config for the native REST client. */
  fun getHttpsProxyConfig(): ProxyConfig? {
    val data = proxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE)?.takeIf { it.host != null && it.port > 0 }
      ?: proxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE)?.takeIf { it.host != null && it.port > 0 }
      ?: return null
    return ProxyConfig(
      host = data.host,
      port = data.port,
      bypassHosts = proxyManager.nonProxiedHosts?.toList() ?: emptyList(),
      username = data.userId?.takeIf { data.isRequiresAuthentication },
      password = data.password?.takeIf { data.isRequiresAuthentication },
    )
  }

  private fun getProxyUrl(data: IProxyData): String? {
    if (data.host == null || data.port <= 0) {
      return null
    }

    return if (data.isRequiresAuthentication) {
      val encodedLogin = URLEncoder.encode(data.userId, Charsets.UTF_8)
      val encodedPassword = URLEncoder.encode(data.password, Charsets.UTF_8)
      "http://$encodedLogin:$encodedPassword@${data.host}:${data.port}"
    } else {
      "http://${data.host}:${data.port}"
    }
  }
}
