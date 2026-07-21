package com.gitlab.eclipse.api.http

import com.gitlab.eclipse.api.GitLabConfigurationException
import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

/**
 * Parses PEM cert/CA/key files into JCA objects for the native egress path.
 * Pure JDK — no BouncyCastle. Supports RSA PKCS#1/PKCS#8 and EC PKCS#8 private keys.
 * Never logs key material or paths; failures surface as [GitLabConfigurationException]
 * with user-safe, secret-free messages.
 */
class TlsMaterialLoader {

  internal fun parsePrivateKey(pem: String): PrivateKey {
    val text = pem.replace("\r\n", "\n")
    // Order matters: detect encryption BEFORE the PKCS#1 branch, because a legacy
    // OpenSSL encrypted RSA key still carries the `BEGIN RSA PRIVATE KEY` label.
    if (text.contains("ENCRYPTED PRIVATE KEY") || text.contains("Proc-Type:") || text.contains("DEK-Info:")) {
      throw GitLabConfigurationException(UNSUPPORTED_KEY_MSG)
    }
    return when {
      text.contains("BEGIN PRIVATE KEY") -> keyFromPkcs8(pemBody(text, "PRIVATE KEY"))
      text.contains("BEGIN RSA PRIVATE KEY") -> keyFromPkcs8(wrapPkcs1AsPkcs8(pemBody(text, "RSA PRIVATE KEY")))
      text.contains("BEGIN EC PRIVATE KEY") -> throw GitLabConfigurationException(UNSUPPORTED_KEY_MSG)
      else -> throw GitLabConfigurationException(INVALID_KEY_MSG)
    }
  }

  fun loadTrustManagers(caCertPath: String): Array<TrustManager> {
    val certs = parseCertificates(readFile(caCertPath), INVALID_CA_MSG)
    if (certs.isEmpty()) throw GitLabConfigurationException(INVALID_CA_MSG)
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      certs.forEachIndexed { i, c -> setCertificateEntry("ca-$i", c) }
    }
    return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
      .apply { init(ks) }.trustManagers
  }

  fun loadKeyManagers(certPath: String, keyPath: String): Array<KeyManager> {
    val chain = parseCertificates(readFile(certPath), INVALID_CERT_MSG)
    if (chain.isEmpty()) throw GitLabConfigurationException(INVALID_CERT_MSG)
    val key = parsePrivateKey(String(readFile(keyPath)))
    val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
      load(null, null)
      setKeyEntry("client", key, CharArray(0), chain.toTypedArray())
    }
    return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
      .apply { init(ks, CharArray(0)) }.keyManagers
  }

  private fun parseCertificates(bytes: ByteArray, invalidMessage: String): List<X509Certificate> = try {
    CertificateFactory.getInstance("X.509")
      .generateCertificates(ByteArrayInputStream(bytes))
      .filterIsInstance<X509Certificate>()
  } catch (e: Exception) {
    throw GitLabConfigurationException(invalidMessage)
  }

  private fun readFile(path: String): ByteArray = try {
    java.io.File(path).readBytes()
  } catch (e: Exception) {
    throw GitLabConfigurationException(UNREADABLE_FILE_MSG)
  }

  private fun keyFromPkcs8(der: ByteArray): PrivateKey {
    val spec = PKCS8EncodedKeySpec(der)
    return try {
      KeyFactory.getInstance("RSA").generatePrivate(spec)
    } catch (rsa: InvalidKeySpecException) {
      try {
        KeyFactory.getInstance("EC").generatePrivate(spec)
      } catch (ec: InvalidKeySpecException) {
        throw GitLabConfigurationException(INVALID_KEY_MSG)
      }
    }
  }

  /** Base64 body between the first `-----BEGIN <label>-----` / `-----END <label>-----`. */
  private fun pemBody(pem: String, label: String): ByteArray {
    val begin = "-----BEGIN $label-----"
    val end = "-----END $label-----"
    val start = pem.indexOf(begin)
    val stop = pem.indexOf(end)
    if (start < 0 || stop < 0) throw GitLabConfigurationException(INVALID_KEY_MSG)
    val body = pem.substring(start + begin.length, stop).replace("\\s".toRegex(), "")
    return try {
      Base64.getDecoder().decode(body)
    } catch (e: IllegalArgumentException) {
      throw GitLabConfigurationException(INVALID_KEY_MSG)
    }
  }

  /**
   * Wraps a PKCS#1 RSAPrivateKey DER in a PKCS#8 PrivateKeyInfo:
   *   SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING { pkcs1 } }
   */
  private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray {
    val version = byteArrayOf(0x02, 0x01, 0x00)                                   // INTEGER 0
    val algId = byteArrayOf(                                                      // SEQUENCE { rsaEncryption, NULL }
      0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
      0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
    )
    val octet = tlv(0x04, pkcs1)                                                  // OCTET STRING { pkcs1 }
    return tlv(0x30, version + algId + octet)                                     // outer SEQUENCE
  }

  /** DER tag-length-value with definite length encoding. */
  private fun tlv(tag: Int, content: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + derLength(content.size) + content

  private fun derLength(len: Int): ByteArray {
    if (len < 0x80) return byteArrayOf(len.toByte())
    val bytes = ArrayList<Byte>()
    var n = len
    while (n > 0) { bytes.add(0, (n and 0xFF).toByte()); n = n ushr 8 }
    return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.toByteArray()
  }

  companion object {
    const val UNSUPPORTED_KEY_MSG =
      "Unsupported client certificate key format (encrypted or EC SEC1). Convert to an " +
        "unencrypted PKCS#8 key: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem"
    const val INVALID_KEY_MSG =
      "Could not read the client certificate key. Provide an unencrypted PEM key " +
        "(PKCS#8 'BEGIN PRIVATE KEY' or RSA PKCS#1 'BEGIN RSA PRIVATE KEY')."
    const val INVALID_CA_MSG = "No certificates found in the configured CA certificate file."
    const val INVALID_CERT_MSG = "Could not read the client certificate. Provide a PEM X.509 certificate."
    const val UNREADABLE_FILE_MSG =
      "Could not read a configured certificate or key file. Check the paths in GitLab preferences."
  }
}
