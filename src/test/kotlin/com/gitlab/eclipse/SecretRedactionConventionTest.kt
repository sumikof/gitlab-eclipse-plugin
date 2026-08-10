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
 * variant 0 と 1 は **1 文字も共有せず長さも異なる**(設計 §7.3 — 先頭 N 文字のような部分投影と、
 * `token.length` のような値そのものではない投影の両方を捕まえるため)。
 */
internal object Sentinels {
  /**
   * variant 1 に `#` を使うのは、**Kotlin の識別子に現れ得ない文字**だからである。
   * variant 0 はフィールド名を埋め込むので、識別子に使える文字を選ぶと
   * (`bbbbbbbb` に対する `bearerToken` のように)将来のフィールド名が偶然その文字を含んだ瞬間、
   * 「1 文字も共有しない」が黙って破れる。長さも 8 と `9 + field.length` で必ず異なる。
   */
  fun forString(field: String, variant: Int): String =
    if (variant == 0) "AAAA-$field-AAAA" else "########"

  fun forThrowable(fqcn: String, field: String, declared: Class<*>, variant: Int): Throwable {
    if (!declared.isAssignableFrom(RuntimeException::class.java)) {
      // 設計 §13.1: FQCN・フィールド名・型を報告する。戦略を広げるのは設計判断なのでここではしない
      throw SynthesisFailure("$fqcn#$field: no synthesis strategy for Throwable subtype ${declared.name}")
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
      val (name, java) = nameAndTypeOf(target.fqcn, p)
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
   * 単一インスタンスについて「渡した値をそのまま保持している」ことを確認する。
   * 引数として渡されなかった秘匿フィールドはその時点で失敗させる —
   * これが無いと、渡していない場合も `null` を渡した場合も期待値が `null` になり、
   * **秘匿引数をデフォルトに委ねてしまう誤り(設計 §7.4 が最も間違えやすいと呼ぶ形)を
   * この検査自身が見逃す。**
   *
   * これは「引数を無視するコンストラクタ」を捕まえる検査であって、
   * 2 標本が実際に異なることを保証するものではない。そちらは [verifySamplesDiffer] が受け持つ。
   */
  private fun verifyInjected(target: DetectedClass, instance: Any, args: Map<KParameter, Any?>) {
    target.secrets.forEach { s ->
      val passed = args.entries.firstOrNull { it.key.name == s.name }
        ?: throw SynthesisFailure("${target.fqcn}#${s.name}: no sentinel was passed to the constructor")
      val f = target.kClass.java.getDeclaredField(s.name).apply { isAccessible = true }
      if (f.get(instance) != passed.value) {
        throw SynthesisFailure("${target.fqcn}#${s.name}: sentinel was not injected")
      }
    }
  }

  /**
   * 設計 §7.4 末尾が求める前提の**直接の測定**。出力不変性は 2 標本の秘匿値が実際に異なって
   * 初めて意味を持つ — 同じなら不変性は自明に成立し、テストは何も検査しないまま緑になる。
   * 組み立て手順ではなく、**組み上がった 2 インスタンスから読み出した値**を比べる。
   */
  fun verifySamplesDiffer(target: DetectedClass, a: Any, b: Any) {
    target.secrets.forEach { s ->
      val f = target.kClass.java.getDeclaredField(s.name).apply { isAccessible = true }
      val formA = observableForm(f.get(a))
      val formB = observableForm(f.get(b))
      if (formA == formB) {
        throw SynthesisFailure("${target.fqcn}#${s.name}: both samples hold the same value ($formA)")
      }
    }
  }

  /**
   * 秘匿値の**観測可能な形**。2 標本が異なることの判定に使う。
   *
   * `Throwable` を参照同一性で比べてはならない。`forThrowable` が variant を無視して
   * message も cause も stack trace も suppressed も同じ 2 つの例外を返しても、別オブジェクトである
   * 以上「異なる」と判定されてしまうためである。設計 §7.3 が独立に変えることを要求する 4 成分を
   * そのまま比較対象にする。
   */
  private fun observableForm(v: Any?): String =
    when (v) {
      null -> "null"
      is Throwable -> listOf(
        v.message,
        v.cause?.message,
        v.stackTrace.joinToString(),
        v.suppressed.joinToString { it.message.orEmpty() },
      ).joinToString("|")
      else -> v.toString()
    }

  private fun synthesizeSecret(fqcn: String, name: String, java: Class<*>, variant: Int): Any =
    when {
      java == String::class.java || java == CharSequence::class.java -> Sentinels.forString(name, variant)
      Throwable::class.java.isAssignableFrom(java) -> Sentinels.forThrowable(fqcn, name, java, variant)
      else -> throw SynthesisFailure("$fqcn#$name: no synthesis strategy for ${java.name}")
    }

  /** 引数名と実行時型を取り出す。取れない引数は合成不能として失敗させる(設計 §13.1)。 */
  private fun nameAndTypeOf(fqcn: String, p: KParameter): Pair<String, Class<*>> {
    val name = p.name ?: throw SynthesisFailure("$fqcn: unnamed parameter")
    val java = (p.type.classifier as? KClass<*>)?.java
      ?: throw SynthesisFailure("$fqcn#$name: unresolvable type")
    return name to java
  }

  /**
   * 設計 §7.5。型が再帰対象(非 exemption の秘匿フィールドを持つ被検出クラス)のフィールドには、
   * null ではなく sentinel 入りの実インスタンスを渡す。
   * [all] は秘匿フィールドを 1 つ以上持つクラスだけなので、exemption だけの `CodeCompletion` は
   * そもそも含まれない(= §7.5 の限定が集合の作り方で満たされる)。
   *
   * `build` は再帰しないので、この再帰は**深さ 1 で止まる**。今日の被検出クラスに循環は無い(§7.5)。
   * 将来 `build` 側にも再帰が要るようになったら、訪問済み集合で循環を検出して失敗させること(§13.1)。
   */
  fun buildWithNested(
    target: DetectedClass,
    all: List<DetectedClass>,
    outerVariant: Int,
    nestedVariant: Int,
  ): Any {
    val ctor = target.kClass.constructors.firstOrNull()
      ?: throw SynthesisFailure("${target.fqcn}: no constructor")
    val args = mutableMapOf<KParameter, Any?>()
    val secretNames = target.secrets.map(SecretField::name).toSet()
    ctor.parameters.forEach { p ->
      val (name, java) = nameAndTypeOf(target.fqcn, p)
      val nested = all.firstOrNull { it.kClass.java == java }
      when {
        name in secretNames -> args[p] = synthesizeSecret(target.fqcn, name, java, outerVariant)
        nested != null -> args[p] = build(nested, nestedVariant) // ← 再帰。null にしない
        p.isOptional -> Unit
        else -> args[p] = synthesizeNeutral(target.fqcn, name, java, p.type.isMarkedNullable)
      }
    }
    return try {
      ctor.callBy(args)
    } catch (e: Throwable) {
      throw SynthesisFailure("${target.fqcn}: callBy failed (${e.javaClass.name})", e)
    }
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

/**
 * 型が被検出クラスであるコンストラクタ引数 = 設計 §7.5 の再帰対象を保持するフィールド。
 * A1 後段の describe と、その空回りを見張る guard の**両方**がこれを呼ぶ。
 * 別々に書くと片方だけが変わって guard が見張る対象を静かに失う。
 */
internal fun nestedParametersOf(outer: DetectedClass, all: List<DetectedClass>): List<KParameter> =
  outer.kClass.constructors.first().parameters.filter { p ->
    val java = (p.type.classifier as? KClass<*>)?.java
    all.any { it.kClass.java == java }
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
     *  - フィールド改名後に取り残された項目。どのフィールドにも当たらないので検出結果は変わらず、
     *    A5 は緑のままになる。
     *  - まだ存在しないクラスへの先回り登録。後の PR がその `FQCN#field` を持つ data class を
     *    追加した瞬間に効き始める。**危険なのは `EXEMPTIONS` 側**で、そのフィールドは黙って
     *    除外され、他に秘匿フィールドが無ければクラスは検出集合に入らないため、
     *    R2(新しい秘匿クラスがビルドを壊す)がどの表明も間違えないまま無効化される。
     *    `EXPLICIT_SECRETS` 側は逆にクラスが検出集合に入るので、その時点で A5 が落ちて気づける。
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
        // 2 標本が実際に違う秘匿値を持っていなければ、この下の一致は何も意味しない
        Synthesizer.verifySamplesDiffer(target, a, b)

        val rendered = try {
          "$a" to "$b"
        } catch (e: Throwable) {
          throw AssertionError("${target.fqcn}: toString threw ${e.javaClass.name}", e)
        }

        rendered.first shouldBe rendered.second
      }
    }
  }

  /*
   * A1 後段。前段は各クラスの**自分の**秘匿フィールドしか動かさないため、入れ子の中身は
   * §7.4 の中立規則で null になり、外側の出力は `httpAgentOptions=null` としか読まれない。
   * ここでは入れ子に sentinel 入りの実インスタンスを植え、その秘匿値だけを変える。
   *
   * 捕まえるのは「外側が入れ子の中に手を伸ばして秘匿値を漏らす」形である。
   * 外側が入れ子の描画の代わりに**定数リテラル**を出す形は捕まえられない(定数は自明に不変)。
   * そちらを分けているのは、`GitLabLanguageServerConfigurationParamsTest` の
   * `reflects a partially populated httpAgentOptions` と `EgressConfigSnapshotTest` の
   * `reflects a changed proxy` である。
   */
  describe("output invariance through nested detected classes") {
    detected.forEach { outer ->
      nestedParametersOf(outer, detected).forEach { p ->
        it("does not depend on the secrets inside ${outer.fqcn}#${p.name}") {
          val a = Synthesizer.buildWithNested(outer, detected, outerVariant = 0, nestedVariant = 0)
          val b = Synthesizer.buildWithNested(outer, detected, outerVariant = 0, nestedVariant = 1)

          "$a" shouldBe "$b"
        }
      }
    }
  }

  describe("the nested pairs are not vacuous") {
    /*
     * 上の describe は 2 つの空回りに対して無防備で、どちらも suite を緑のまま素通りする。
     *  - フィルタが 0 件を返すと、テストが 1 つも**生成されない**。
     *  - `buildWithNested` の分岐順序が変わって入れ子に null が渡ると、2 標本とも同じ
     *    `null` を描画するので、どちらのテストも自明に一致して通る。
     *
     * 固定するのは「実インスタンスが実際に植わった組が 1 つ以上ある」ことだけである
     * (A3 の `scanned > 0` と同じ形)。**件数そのものは条件にしない** — 設計 §22 A1 が
     * 固定件数の条件を禁じている。何件あるべきかは条件ではなく現況である。
     */
    it("plants a real nested instance in at least one field") {
      val planted = detected.flatMap { outer ->
        nestedParametersOf(outer, detected).mapNotNull { p ->
          val sample = Synthesizer.buildWithNested(outer, detected, outerVariant = 0, nestedVariant = 0)
          p.name?.let { outer.kClass.java.getDeclaredField(it).apply { isAccessible = true }.get(sample) }
        }
      }

      planted.size shouldBeGreaterThan 0
    }
  }
})
