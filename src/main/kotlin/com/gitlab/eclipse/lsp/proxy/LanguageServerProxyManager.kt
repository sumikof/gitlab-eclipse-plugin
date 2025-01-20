package com.gitlab.eclipse.lsp.proxy

import org.eclipse.core.internal.net.ProxyData
import org.eclipse.core.internal.net.ProxyManager
import org.eclipse.core.net.proxy.IProxyData
import java.net.URLEncoder

class LanguageServerProxyManager {
  private val proxyManager
    get() = ProxyManager.getProxyManager()

  fun getHttpProxyUrl(): String? {
    return proxyManager.getProxyData(ProxyData.HTTP_PROXY_TYPE)?.let { getProxyUrl(it) }
  }

  fun getHttpsProxyUrl(): String? {
    return proxyManager.getProxyData(ProxyData.HTTPS_PROXY_TYPE)?.let { getProxyUrl(it) }
  }

  fun getBypassHosts(): String? {
    return proxyManager.nonProxiedHosts?.joinToString(",")
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
