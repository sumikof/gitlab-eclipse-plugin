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

  // Flat input validation: each branch rejects one unsupported/invalid PEM shape with its
  // own user-facing message. Collapsing the throws behind a shared exit or nesting the
  // `when` would hide which input was rejected and why, so the count is kept as is.
  @Suppress("ThrowsCount")
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
  } catch (@Suppress("SwallowedException") e: Exception) {
    // The cause is dropped on purpose: X.509 parse failures quote certificate bytes and
    // file paths, which this class contractually never surfaces (see the class KDoc).
    // GitLabConfigurationException takes no `cause` for exactly that reason.
    throw GitLabConfigurationException(invalidMessage)
  }

  private fun readFile(path: String): ByteArray = try {
    java.io.File(path).readBytes()
  } catch (@Suppress("SwallowedException") e: Exception) {
    // The cause is dropped on purpose: an IOException names the file it failed to read,
    // and the resulting message is shown in the UI, which must stay path-free.
    throw GitLabConfigurationException(UNREADABLE_FILE_MSG)
  }

  private fun keyFromPkcs8(der: ByteArray): PrivateKey {
    val spec = PKCS8EncodedKeySpec(der)
    return try {
      KeyFactory.getInstance("RSA").generatePrivate(spec)
    } catch (@Suppress("SwallowedException") rsa: InvalidKeySpecException) {
      // Not an error yet: "not an RSA key" is the normal signal to retry the same DER as EC.
      try {
        KeyFactory.getInstance("EC").generatePrivate(spec)
      } catch (@Suppress("SwallowedException") ec: InvalidKeySpecException) {
        // Both causes are dropped on purpose: InvalidKeySpecException messages can embed
        // fragments of the decoded key material, which must never reach the UI or a log.
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
    if (start < 0 || stop < start + begin.length) throw GitLabConfigurationException(INVALID_KEY_MSG)
    val body = pem.substring(start + begin.length, stop).replace("\\s".toRegex(), "")
    return try {
      Base64.getDecoder().decode(body)
    } catch (@Suppress("SwallowedException") e: IllegalArgumentException) {
      // The cause is dropped on purpose: the Base64 decoder reports the offending
      // character and its offset, i.e. a fragment of the private key body.
      throw GitLabConfigurationException(INVALID_KEY_MSG)
    }
  }

  /**
   * Wraps a PKCS#1 RSAPrivateKey DER in a PKCS#8 PrivateKeyInfo:
   *   SEQUENCE { INTEGER 0, SEQUENCE { OID rsaEncryption, NULL }, OCTET STRING { pkcs1 } }
   */
  private fun wrapPkcs1AsPkcs8(pkcs1: ByteArray): ByteArray {
    val octetString = tlv(DER_TAG_OCTET_STRING, pkcs1)
    return tlv(DER_TAG_SEQUENCE, PKCS8_VERSION_0 + RSA_ENCRYPTION_ALGORITHM_ID + octetString)
  }

  /** DER tag-length-value with definite length encoding. */
  private fun tlv(tag: Int, content: ByteArray): ByteArray =
    byteArrayOf(tag.toByte()) + derLength(content.size) + content

  private fun derLength(len: Int): ByteArray {
    if (len < DER_LENGTH_SHORT_FORM_LIMIT) return byteArrayOf(len.toByte())
    val bytes = ArrayList<Byte>()
    var n = len
    while (n > 0) {
      bytes.add(0, (n and BYTE_MASK).toByte())
      n = n ushr BITS_PER_BYTE
    }
    return byteArrayOf((DER_LENGTH_LONG_FORM_MARKER or bytes.size).toByte()) + bytes.toByteArray()
  }

  companion object {
    /** DER universal tag for SEQUENCE (constructed). */
    private const val DER_TAG_SEQUENCE = 0x30

    /** DER universal tag for OCTET STRING. */
    private const val DER_TAG_OCTET_STRING = 0x04

    /** Lengths 0..0x7F use the DER short form (a single length byte). */
    private const val DER_LENGTH_SHORT_FORM_LIMIT = 0x80

    /** DER long form: first byte is 0x80 or'd with the count of following length bytes. */
    private const val DER_LENGTH_LONG_FORM_MARKER = 0x80

    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** PKCS#8 PrivateKeyInfo `version` field, encoded as `INTEGER 0`. */
    private val PKCS8_VERSION_0 = byteArrayOf(0x02, 0x01, 0x00)

    /**
     * PKCS#8 `privateKeyAlgorithm`, encoded as
     * `SEQUENCE { OID 1.2.840.113549.1.1.1 (rsaEncryption), NULL }`.
     */
    private val RSA_ENCRYPTION_ALGORITHM_ID = byteArrayOf(
      0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
      0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00,
    )

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
