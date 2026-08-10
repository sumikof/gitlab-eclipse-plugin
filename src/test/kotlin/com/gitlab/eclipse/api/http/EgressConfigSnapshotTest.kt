package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.lsp.proxy.ProxyConfig
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class EgressConfigSnapshotTest : DescribeSpec({
  describe("toString") {
    it("redacts the certificate paths and delegates to the nested ProxyConfig") {
      val snapshot = EgressConfigSnapshot(
        ignoreCertificateErrors = true,
        caCertificatePath = "/home/user/ca.pem",
        clientCertificatePath = "/home/user/client.pem",
        clientCertificateKeyPath = "/home/user/client.key",
        proxy = ProxyConfig("proxy.internal", 8080, listOf("*.example.com"), "alice", "s3cret"),
      )

      "$snapshot" shouldBe
        "EgressConfigSnapshot(ignoreCertificateErrors=true, caCertificatePath=***, " +
        "clientCertificatePath=***, clientCertificateKeyPath=***, " +
        "proxy=ProxyConfig(host=proxy.internal, port=8080, bypassHosts=[*.example.com], " +
        "username=***, password=***))"
    }

    // §22.2: nullable な秘匿成分の null ケース。`!!` への退行を止める。
    it("says so when the paths and the proxy are absent") {
      val snapshot = EgressConfigSnapshot(
        ignoreCertificateErrors = false,
        caCertificatePath = null,
        clientCertificatePath = null,
        clientCertificateKeyPath = null,
        proxy = null,
      )

      "$snapshot" shouldBe
        "EgressConfigSnapshot(ignoreCertificateErrors=false, caCertificatePath=null, " +
        "clientCertificatePath=null, clientCertificateKeyPath=null, proxy=null)"
    }

    // A2(a): 非秘匿成分は値を変えたら出力も変わる。これが無いと toString を定数にしても通る。
    it("reflects a changed ignoreCertificateErrors") {
      val snapshot = EgressConfigSnapshot(
        ignoreCertificateErrors = true,
        caCertificatePath = null,
        clientCertificatePath = null,
        clientCertificateKeyPath = null,
        proxy = null,
      )

      "$snapshot" shouldBe
        "EgressConfigSnapshot(ignoreCertificateErrors=true, caCertificatePath=null, " +
        "clientCertificatePath=null, clientCertificateKeyPath=null, proxy=null)"
    }

    // A2(a): proxy だけを変えた標本。これが無いと、入れ子のリテラルを直書きした
    // toString も通ってしまう(= 本当の委譲と区別が付かない)。
    it("reflects a changed proxy") {
      val snapshot = EgressConfigSnapshot(
        ignoreCertificateErrors = false,
        caCertificatePath = null,
        clientCertificatePath = null,
        clientCertificateKeyPath = null,
        proxy = ProxyConfig(
          host = "other.host",
          port = 3128,
          bypassHosts = emptyList(),
          username = null,
          password = null,
        ),
      )

      "$snapshot" shouldBe
        "EgressConfigSnapshot(ignoreCertificateErrors=false, caCertificatePath=null, " +
        "clientCertificatePath=null, clientCertificateKeyPath=null, " +
        "proxy=ProxyConfig(host=other.host, port=3128, bypassHosts=[], username=null, password=null))"
    }
  }
})
