package com.gitlab.eclipse

import com.gitlab.eclipse.api.ConnectionSnapshot
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.reflect.KClass

internal data class SecretField(val name: String, val type: Class<*>)
internal data class DetectedClass(val fqcn: String, val kClass: KClass<*>, val secrets: List<SecretField>)

/** 設計 §7.2 の D1。 */
private val SECRET_NAME_WORDS =
  listOf("token", "secret", "password", "credential", "passphrase", "key", "cert")

/** 設計 §7.2 の明示リスト。名前パターンに当たらないが秘匿であるフィールド。 */
private val EXPLICIT_SECRETS = setOf(
  "com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams\$HttpAgentOptions#ca",
)

/** 設計 §7.3.1 の exemption。名前パターンに当たるが秘匿ではないフィールド。 */
private val EXEMPTIONS = setOf(
  "com.gitlab.eclipse.authentication.GitLabAuthorizationToken#tokenExpirationTimestamp",
  "com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams\$CodeCompletion" +
    "#enableSecretRedaction",
  "com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams#ignoreCertificateErrors",
  "com.gitlab.eclipse.api.http.EgressConfigSnapshot#ignoreCertificateErrors",
)

/**
 * 設計 §8 の A5。**検出器の出力から作らない**(初回実行の失敗メッセージから手で書き写した)。
 * 設計 §6 の表とは 3 件の FQCN が食い違う(§17 の U2)。`CheckoutResult` / `PushOutcome` /
 * `WebviewResolution` は同名ファイル内のトップレベル sealed interface であり、
 * サービスクラスの入れ子ではない。
 */
private val EXPECTED_SECRET_CLASSES: Map<String, Set<String>> = mapOf(
  "com.gitlab.eclipse.authentication.GitLabAuthorizationToken" to setOf("accessToken", "refreshToken"),
  "com.gitlab.eclipse.api.ConnectionSnapshot" to setOf("token"),
  "com.gitlab.eclipse.api.http.EgressConfigSnapshot" to
    setOf("caCertificatePath", "clientCertificatePath", "clientCertificateKeyPath"),
  "com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams" to setOf("token"),
  "com.gitlab.eclipse.lsp.configuration.GitLabLanguageServerConfigurationParams\$HttpAgentOptions" to
    setOf("ca", "cert", "certKey"),
  "com.gitlab.eclipse.lsp.proxy.ProxyConfig" to setOf("password"),
  "com.gitlab.eclipse.lsp.webview.WebviewResolution\$Failed" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.CheckoutResult\$Failed" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.PushOutcome\$Failed" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome\$Ambiguous" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.discussions.DiscussionWriteOutcome\$Definite" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.discussions.DiscussionsLoader\$FetchOutcome\$Failed" to setOf("cause"),
  "com.gitlab.eclipse.mergerequests.discussions.LoadOutcome\$Failed" to setOf("cause"),
  "com.gitlab.eclipse.preferences.healthcheck.ConfigurationValidationRequest" to setOf("token"),
)

internal object ConventionScan {
  private val outputRoot: File =
    File(ConnectionSnapshot::class.java.protectionDomain.codeSource.location.toURI())

  val unexaminable = mutableListOf<String>()
  var scanned = 0
    private set

  fun run(): List<DetectedClass> {
    val loader = ConnectionSnapshot::class.java.classLoader
    val detected = mutableListOf<DetectedClass>()
    outputRoot.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
      val fqcn = file.relativeTo(outputRoot).path
        .removeSuffix(".class").replace(File.separatorChar, '.')
      scanned++
      val java = try {
        Class.forName(fqcn, false, loader)
      } catch (e: LinkageError) {
        unexaminable += "$fqcn (${e.javaClass.name})"
        return@forEach
      } catch (e: ClassNotFoundException) {
        unexaminable += "$fqcn (${e.javaClass.name})"
        return@forEach
      }
      val kClass = try {
        java.kotlin
      } catch (e: Throwable) {
        unexaminable += "$fqcn (${e.javaClass.name})"
        return@forEach
      }
      if (!kClass.isData) return@forEach
      val fields = try {
        java.declaredFields
      } catch (e: LinkageError) {
        unexaminable += "$fqcn (${e.javaClass.name})"
        return@forEach
      }
      val secrets = fields.filter { f ->
        val id = "$fqcn#${f.name}"
        if (id in EXEMPTIONS) return@filter false
        if (id in EXPLICIT_SECRETS) return@filter true
        val byName = SECRET_NAME_WORDS.any { w -> f.name.lowercase().contains(w) }
        val byType = Throwable::class.java.isAssignableFrom(f.type)
        byName || byType
      }.map { SecretField(it.name, it.type) }
      if (secrets.isNotEmpty()) detected += DetectedClass(fqcn, kClass, secrets)
    }
    return detected
  }
}

class SecretRedactionConventionTest : DescribeSpec({
  val detected = ConventionScan.run()

  describe("the scan itself") {
    // A3: 走査が壊れると 0 クラスを見て「違反なし」と報告する
    it("visits the compiled output") {
      ConventionScan.scanned shouldBeGreaterThan 0
    }

    // A4: 読めなかったクラスを読み飛ばすと、その中に秘匿クラスがあっても静かに通る
    it("can examine every compiled class") {
      ConventionScan.unexaminable.shouldBeEmpty()
    }
  }

  describe("the detected set") {
    // A5: 手書きの期待リストと双方向で一致する
    it("matches the hand-written expectation") {
      detected.associate { it.fqcn to it.secrets.map(SecretField::name).toSet() } shouldBe
        EXPECTED_SECRET_CLASSES
    }
  }
})
