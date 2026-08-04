package com.gitlab.eclipse.lsp.diagnostics

/**
 * 診断適用の順序づけを一手に引き受けるレジストリ(§14.6)。
 *
 * 3 つの軸を持つ。混同すると壊れるので用途を取り違えないこと。
 *  - generation: 診断を受信するたびに進む。**置換 1 回に固有**。§12 の二段階置換で使う
 *  - epoch:      LS 停止・バンドル停止でしか進まない。§14.2.1 の掃除と接続の隔離で使う
 *  - sourceEpoch: 設定の有効/無効でしか進まない。偶数 = 稼働、奇数 = 停止
 *
 * すべての状態変更は [lock] の下で行う。UI スレッドから入ることもあるため、
 * `kotlinx.coroutines.sync.Mutex` ではなく**プレーンなモニタ**である(runBlocking を避ける)。
 * ロック順序は「送信 Mutex -> このモニタ」の一方向に固定する(§14.6)。
 *
 * `@Suppress("TooManyFunctions")`: この object は後続タスク(2〜7)が逐語で依存する固定 API
 * (Produces)として 15 個の public 関数を**必須**として持つ。既定閾値(object 内 11)は
 * private helper をゼロにしても 15 > 11 で必ず超過するため、helper 抽出では解消できない
 * (helper を object 外へ追い出すには private な状態(epoch 等)を internal に緩め、
 * この object が保証する「全状態変更は lock 配下」という単一モニタの前提を弱めることになり、
 * 本タスクの範囲を超える設計判断となるため行わない)。
 */
@Suppress("TooManyFunctions")
object DiagnosticGenerationRegistry {
  val lock = Any()

  @Volatile
  var active: Boolean = true
    private set

  private var epoch: Long = 0
  private var generationCounter: Long = 0
  private var settingsSeq: Long = 0
  private var lastAppliedSettingsSeq: Long = -1
  private var stoppedForEpoch: Long = -1
  private val latest = mutableMapOf<String, Long>()
  private val sourceEpochs = mutableMapOf<String, Long>()

  val currentEpoch: Long get() = synchronized(lock) { epoch }

  // NOTE(§2.1): `nextGeneration` は計画では式本体 `= synchronized(lock) { ... return null ... }`
  // だったが、Kotlin は式本体関数の中の `return` を拒否する(RETURN_NOT_ALLOWED)ためブロック本体へ
  // 書き換えた。`synchronized` は inline なので非局所 return が使える。セマンティクスは不変:
  // 不成立なら null を返し、カウンタも latest マップも一切変更しない。
  fun nextGeneration(key: String, connectionEpoch: Long): Long? {
    synchronized(lock) {
      if (!active || connectionEpoch != epoch) return null
      generationCounter += 1
      latest[key] = generationCounter
      return generationCounter
    }
  }

  // NOTE(§2.1): 同上の理由でブロック本体へ書き換え。セマンティクスは不変:
  // 不成立なら null を返し、カウンタも latest マップも一切変更しない。
  fun acceptToken(source: String?, connectionEpoch: Long): SourceToken? {
    synchronized(lock) {
      if (!active || connectionEpoch != epoch) return null
      val name = source?.takeIf { it.isNotBlank() } ?: DiagnosticMarkerAttributes.UNKNOWN_SOURCE
      val current = sourceEpochs.getOrDefault(name, 0L)
      if (current % 2 != 0L) return null // 奇数 = 停止中
      return SourceToken(name, current)
    }
  }

  fun isTokenValid(token: SourceToken?): Boolean = synchronized(lock) {
    token != null && sourceEpochs.getOrDefault(token.source, 0L) == token.sourceEpoch
  }

  fun shouldApply(key: String, generation: Long, capturedEpoch: Long): Boolean = synchronized(lock) {
    active && capturedEpoch == epoch && latest[key] == generation
  }

  fun onActivate() = synchronized(lock) {
    active = true
    epoch += 1
    latest.clear()
  }

  fun onDeactivate() = synchronized(lock) { active = false }

  // NOTE(§2.1): 計画では式本体 `= synchronized(lock) { if (...) return; ... }` だったが、
  // 式本体関数中の bare `return` も同じ理由で拒否されるためブロック本体へ書き換えた。
  //
  // NOTE(実装バグの修正): 計画の逐語コードは `stoppedForEpoch = epoch` を `epoch += 1` の**前**に
  // 実行しており、`stoppedForEpoch` に旧 epoch を記録していた。次回呼び出し時のガード
  // `stoppedForEpoch == epoch` は旧 epoch と新 epoch を比較することになり、同一接続への
  // 2 回目の呼び出しでも常に不一致となって毎回 epoch が進んでしまう(brief §2.1 が明記する
  // 「同じ epoch について 2 回目は何もしない」という不変条件に違反し、逐語テスト
  // 「onServerStopped > is idempotent for the same connection」が実際に落ちることで確認した)。
  // `epoch += 1` の**後**に `stoppedForEpoch = epoch`(新 epoch)を記録するよう順序を入れ替え、
  // 不変条件を回復した。
  // LS 停止。**active は落とさない**(§14.2.1)。同じ接続について二重に呼ばれても進めない。
  fun onServerStopped() {
    synchronized(lock) {
      if (stoppedForEpoch == epoch) return
      epoch += 1
      stoppedForEpoch = epoch
      latest.clear()
    }
  }

  fun nextSettingsSeq(): Long = synchronized(lock) {
    settingsSeq += 1
    settingsSeq
  }

  /** lock を保持したまま呼ぶこと(§9.1.1 の破壊的手順の最新性判定)。 */
  fun isLatestSettingsSeq(seq: Long): Boolean = synchronized(lock) { settingsSeq == seq }

  fun currentGenerationCounter(): Long = synchronized(lock) { generationCounter }

  fun suspendSource(source: String, settingsSeq: Long): Boolean =
    applyTransition(source, settingsSeq, desiredOdd = true)

  fun resumeSource(source: String, settingsSeq: Long): Boolean =
    applyTransition(source, settingsSeq, desiredOdd = false)

  // NOTE(§2.1): 計画では式本体 `= synchronized(lock) { if (...) return false; ... true }` だったが、
  // 同じ理由でブロック本体へ書き換えた。セマンティクスは不変:
  // seq <= lastAppliedSettingsSeq なら lastAppliedSettingsSeq も parity も変更せず false。
  private fun applyTransition(source: String, seq: Long, desiredOdd: Boolean): Boolean {
    synchronized(lock) {
      if (seq <= lastAppliedSettingsSeq) return false // 待ち行列で追い越された古い遷移
      lastAppliedSettingsSeq = seq
      setParity(source, desiredOdd)
      return true
    }
  }

  /** 収束専用。**lastAppliedSettingsSeq を進めない**ので保留中の遷移の後続処理を奪わない(§9.1.1)。 */
  fun reconcileSource(source: String, desiredSuspended: Boolean) = synchronized(lock) {
    setParity(source, desiredSuspended)
  }

  private fun setParity(source: String, desiredOdd: Boolean) {
    val current = sourceEpochs.getOrDefault(source, 0L)
    val isOdd = current % 2 != 0L
    if (isOdd != desiredOdd) sourceEpochs[source] = current + 1 // CAS 相当。冪等
  }

  fun isSuspended(source: String?): Boolean = synchronized(lock) {
    val name = source?.takeIf { it.isNotBlank() } ?: DiagnosticMarkerAttributes.UNKNOWN_SOURCE
    sourceEpochs.getOrDefault(name, 0L) % 2 != 0L
  }

  fun resetForTest() = synchronized(lock) {
    active = true
    epoch = 0
    generationCounter = 0
    settingsSeq = 0
    lastAppliedSettingsSeq = -1
    stoppedForEpoch = -1
    latest.clear()
    sourceEpochs.clear()
  }
}
