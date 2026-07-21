package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.api.GitLabConfigurationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.interfaces.RSAPrivateKey

private fun fixture(name: String): String =
  TlsMaterialLoaderTest::class.java.getResource("/tls/$name")!!.readText()

private fun fixturePath(name: String): String =
  java.nio.file.Path.of(TlsMaterialLoaderTest::class.java.getResource("/tls/$name")!!.toURI()).toString()

class TlsMaterialLoaderTest : DescribeSpec({
  val loader = TlsMaterialLoader()

  describe("parsePrivateKey") {
    it("loads a PKCS#8 RSA key") {
      loader.parsePrivateKey(fixture("rsa_pkcs8.key.pem")).algorithm shouldBe "RSA"
    }
    it("loads a PKCS#1 RSA key with the same modulus as its PKCS#8 form") {
      val fromPkcs8 = loader.parsePrivateKey(fixture("rsa_pkcs8.key.pem")) as RSAPrivateKey
      val fromPkcs1 = loader.parsePrivateKey(fixture("rsa_pkcs1.key.pem")) as RSAPrivateKey
      fromPkcs1.modulus shouldBe fromPkcs8.modulus
      fromPkcs1.privateExponent shouldBe fromPkcs8.privateExponent
    }
    it("loads a PKCS#8 EC key") {
      loader.parsePrivateKey(fixture("ec_pkcs8.key.pem")).algorithm shouldBe "EC"
    }
    it("rejects SEC1 EC keys with a conversion hint") {
      val e = shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey(fixture("ec_sec1.key.pem")) }
      e.message!! shouldContain "openssl pkcs8"
    }
    it("rejects PKCS#8 encrypted keys") {
      shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey(fixture("encrypted_pkcs8.key.pem")) }
    }
    it("rejects legacy-encrypted RSA keys (Proc-Type) instead of failing obscurely") {
      shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey(fixture("legacy_encrypted_rsa.key.pem")) }
    }
    it("rejects garbage") {
      shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey("not a pem") }
    }
    it("normalizes a malformed PKCS#1 key to a configuration error") {
      val garbage = java.util.Base64.getEncoder().encodeToString("not valid der".toByteArray())
      val pem = "-----BEGIN RSA PRIVATE KEY-----\n$garbage\n-----END RSA PRIVATE KEY-----"
      shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey(pem) }
    }
    it("normalizes a PKCS#1 PEM with reversed BEGIN/END markers to a configuration error") {
      val pem = "-----END RSA PRIVATE KEY-----\nAAAA\n-----BEGIN RSA PRIVATE KEY-----"
      shouldThrow<GitLabConfigurationException> { loader.parsePrivateKey(pem) }
    }
  }

  describe("loadTrustManagers") {
    it("builds trust managers from a CA PEM") {
      val tms = loader.loadTrustManagers(fixturePath("ca.cert.pem"))
      tms.isNotEmpty() shouldBe true
    }
    it("rejects a missing CA file") {
      shouldThrow<GitLabConfigurationException> { loader.loadTrustManagers("/no/such/ca.pem") }
    }
    it("reports a CA-specific message for a malformed CA file") {
      val bad = java.io.File.createTempFile("bad-ca", ".pem").apply { writeText("not a certificate"); deleteOnExit() }
      val e = shouldThrow<GitLabConfigurationException> { loader.loadTrustManagers(bad.absolutePath) }
      e.message shouldBe TlsMaterialLoader.INVALID_CA_MSG
    }
  }

  describe("loadKeyManagers") {
    it("builds key managers from a client cert + RSA PKCS#1 key") {
      val kms = loader.loadKeyManagers(fixturePath("client_rsa.cert.pem"), fixturePath("rsa_pkcs1.key.pem"))
      kms.isNotEmpty() shouldBe true
    }
    it("builds key managers from a client cert + RSA PKCS#8 key") {
      val kms = loader.loadKeyManagers(fixturePath("client_rsa.cert.pem"), fixturePath("rsa_pkcs8.key.pem"))
      kms.isNotEmpty() shouldBe true
    }
    it("rejects a missing key file") {
      shouldThrow<GitLabConfigurationException> {
        loader.loadKeyManagers(fixturePath("client_rsa.cert.pem"), "/no/such/key.pem")
      }
    }
  }
})
