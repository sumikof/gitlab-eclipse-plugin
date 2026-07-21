package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.api.GitLabConfigurationException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.security.interfaces.RSAPrivateKey

private fun fixture(name: String): String =
  TlsMaterialLoaderTest::class.java.getResource("/tls/$name")!!.readText()

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
  }
})
