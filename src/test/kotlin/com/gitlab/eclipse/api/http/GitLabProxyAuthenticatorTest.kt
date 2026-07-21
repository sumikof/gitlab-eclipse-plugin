package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.net.Authenticator
import java.net.InetAddress
import java.net.URL

private fun ask(
  auth: GitLabProxyAuthenticator, host: String, port: Int, type: Authenticator.RequestorType,
) = Authenticator.requestPasswordAuthentication(
  auth, host, null as InetAddress?, port, "http", "prompt", "Basic", null as URL?, type,
)

class GitLabProxyAuthenticatorTest : DescribeSpec({
  val proxy = ProxyConfig("proxy.local", 8080, emptyList(), "puser", "ppass")
  val auth = GitLabProxyAuthenticator(proxy)

  it("returns credentials for a matching proxy request") {
    val pa = ask(auth, "proxy.local", 8080, Authenticator.RequestorType.PROXY)
    pa.userName shouldBe "puser"
    String(pa.password) shouldBe "ppass"
  }
  it("returns null for a server (401) request — never leaks proxy creds to the server") {
    ask(auth, "gitlab.example.com", 443, Authenticator.RequestorType.SERVER).shouldBeNull()
  }
  it("returns null for a different proxy host/port") {
    ask(auth, "other.host", 8080, Authenticator.RequestorType.PROXY).shouldBeNull()
    ask(auth, "proxy.local", 3128, Authenticator.RequestorType.PROXY).shouldBeNull()
  }
  it("returns null for a SERVER request even when host/port match the proxy (isolates the requestorType guard)") {
    ask(auth, "proxy.local", 8080, Authenticator.RequestorType.SERVER).shouldBeNull()
  }
  it("returns null when the proxy has no credentials") {
    val noCreds = GitLabProxyAuthenticator(ProxyConfig("proxy.local", 8080, emptyList(), null, null))
    ask(noCreds, "proxy.local", 8080, Authenticator.RequestorType.PROXY).shouldBeNull()
  }
})
