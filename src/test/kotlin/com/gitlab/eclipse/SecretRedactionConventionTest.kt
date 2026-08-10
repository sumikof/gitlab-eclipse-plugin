package com.gitlab.eclipse

import com.gitlab.eclipse.api.ConnectionSnapshot
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KParameter

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

  /** 走査中に出会った `data class`(秘匿フィールドの有無を問わない)。2 度目の走査を避けるため。 */
  val dataClasses = mutableMapOf<String, KClass<*>>()

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
      dataClasses[fqcn] = kClass
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

/**
 * 設計 §7.3。秘匿フィールドだけが異なる 2 インスタンスを作るための値。
 * variant 0 と 1 は **1 文字も共有せず長さも異なる**(設計 §7.3 — `token.length` のような
 * 値そのものではない投影を捕まえるため)。
 */
internal object Sentinels {
  fun forString(field: String, variant: Int): String =
    if (variant == 0) "AAAA-$field-AAAA" else "bbbbbbbb"

  fun forThrowable(declared: Class<*>, variant: Int): Throwable {
    require(declared.isAssignableFrom(RuntimeException::class.java)) {
      "no synthesis strategy for Throwable subtype ${declared.name}"
    }
    val inner = if (variant == 0) RuntimeException("AAAA-inner") else RuntimeException("bbbbbbbb-inner")
    val outer = RuntimeException(if (variant == 0) "AAAA-msg" else "bbbbbbbb-msg", inner)
    outer.stackTrace = arrayOf(
      StackTraceElement("A${variant}Class", "m$variant", "F$variant.kt", variant + 1)
    )
    outer.addSuppressed(RuntimeException(if (variant == 0) "AAAA-sup" else "bbbbbbbb-sup"))
    return outer
  }
}

internal class SynthesisFailure(message: String, cause: Throwable? = null) : AssertionError(message, cause)

internal object Synthesizer {
  fun build(target: DetectedClass, variant: Int): Any {
    val ctor = target.kClass.constructors.firstOrNull()
      ?: throw SynthesisFailure("${target.fqcn}: no constructor")
    val args = argumentsFor(target, ctor.parameters, variant)
    val instance = try {
      ctor.callBy(args)
    } catch (e: Throwable) {
      throw SynthesisFailure("${target.fqcn}: callBy failed (${e.javaClass.name})", e)
    }
    verifyInjected(target, instance, args)
    return instance
  }

  private fun argumentsFor(
    target: DetectedClass,
    params: List<KParameter>,
    variant: Int,
  ): Map<KParameter, Any?> {
    val args = mutableMapOf<KParameter, Any?>()
    val secretNames = target.secrets.map(SecretField::name).toSet()
    params.forEach { p ->
      val name = p.name ?: throw SynthesisFailure("${target.fqcn}: unnamed parameter")
      val java = (p.type.classifier as? KClass<*>)?.java
        ?: throw SynthesisFailure("${target.fqcn}#$name: unresolvable type")
      when {
        // 設計 §7.4: 秘匿引数はデフォルトの有無にかかわらず必ず明示的に渡す
        name in secretNames -> args[p] = synthesizeSecret(target.fqcn, name, java, variant)
        // デフォルトに委ねる(設計 §7.4)
        p.isOptional -> Unit
        else -> args[p] = synthesizeNeutral(target.fqcn, name, java, p.type.isMarkedNullable)
      }
    }
    return args
  }

  /**
   * 設計 §7.4 末尾。出力不変性は 2 値が実際に注入されて初めて意味を持つ。
   * 注入されていなければ 2 インスタンスは同一になり、不変性が自明に成立してしまう。
   */
  private fun verifyInjected(target: DetectedClass, instance: Any, args: Map<KParameter, Any?>) {
    target.secrets.forEach { s ->
      val f = target.kClass.java.getDeclaredField(s.name).apply { isAccessible = true }
      val held = f.get(instance)
      val expected = args.entries.firstOrNull { it.key.name == s.name }?.value
      if (held != expected) {
        throw SynthesisFailure("${target.fqcn}#${s.name}: sentinel was not injected")
      }
    }
  }

  private fun synthesizeSecret(fqcn: String, name: String, java: Class<*>, variant: Int): Any =
    when {
      java == String::class.java || java == CharSequence::class.java -> Sentinels.forString(name, variant)
      Throwable::class.java.isAssignableFrom(java) -> Sentinels.forThrowable(java, variant)
      else -> throw SynthesisFailure("$fqcn#$name: no synthesis strategy for ${java.name}")
    }

  private fun synthesizeNeutral(fqcn: String, name: String, java: Class<*>, nullable: Boolean): Any? =
    when {
      nullable -> null
      java == String::class.java -> "neutral"
      java == Int::class.javaPrimitiveType || java == Integer::class.java -> 0
      // `java.lang.Long::class` とは書けない — 引数名 `java` がパッケージ名を隠すため
      java == Long::class.javaPrimitiveType || java == Long::class.javaObjectType -> 0L
      java == Boolean::class.javaPrimitiveType || java == Boolean::class.javaObjectType -> false
      List::class.java.isAssignableFrom(java) -> emptyList<Any>()
      Set::class.java.isAssignableFrom(java) -> emptySet<Any>()
      Map::class.java.isAssignableFrom(java) -> emptyMap<Any, Any>()
      java.isEnum -> java.enumConstants.first()
      else -> throw SynthesisFailure("$fqcn#$name: cannot synthesize non-null ${java.name}")
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

  describe("the hand-written field lists") {
    /*
     * 固定するのは「各項目が実在の `data class` の実在フィールドを指している」ことだけである。
     * 一覧が網羅的であること・分類(秘匿か非秘匿か)が正しいことは、この検査では**分からない**。
     *
     * 捕まえるのは A5 が捕まえない 2 つの形 — どちらも今日は不活性で、後から効き始める。
     *  - フィールド改名後に取り残された項目。検出結果は変わらないので A5 は緑のまま。
     *  - まだ存在しないクラスへの先回り登録。後の PR がその `FQCN#field` を持つ data class を
     *    追加した瞬間、そのフィールドは黙って exemption され、クラスは検出集合に入らない。
     *    R2(新しい秘匿クラスがビルドを壊す)が、どの表明も間違えないまま無効化される。
     */
    it("names only fields that really exist") {
      val entries = (EXEMPTIONS + EXPLICIT_SECRETS).sorted()
      val unresolved = entries.filterNot { entry ->
        val fqcn = entry.substringBefore('#')
        val field = entry.substringAfter('#')
        ConventionScan.dataClasses[fqcn]?.java?.declaredFields?.any { it.name == field } == true
      }
      unresolved.shouldBeEmpty()
    }
  }

  describe("output invariance") {
    detected.forEach { target ->
      // A1 前段: 秘匿フィールドだけが異なる 2 インスタンスの出力が完全一致する
      it("does not depend on the secret components of ${target.fqcn}") {
        val a = Synthesizer.build(target, 0)
        val b = Synthesizer.build(target, 1)

        val rendered = try {
          "$a" to "$b"
        } catch (e: Throwable) {
          throw AssertionError("${target.fqcn}: toString threw ${e.javaClass.name}", e)
        }

        rendered.first shouldBe rendered.second
      }
    }
  }
})
