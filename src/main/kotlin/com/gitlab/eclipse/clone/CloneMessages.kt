package com.gitlab.eclipse.clone

import java.io.File

/**
 * Every user-facing notification string for the `cloneWiki` flow.
 *
 * All members are pure functions/values with no Eclipse or SWT dependency, so the universal rule
 * the design places on this wording can be pinned by a headless test: for every
 * [ImportSkipReason], the notification differs by [RepositorySource] — no individual reason gets
 * a single fixed message shared across both paths. Adding a reason is only complete once wording
 * exists for both paths; see `CloneMessagesTest` for the test that enforces this.
 */
object CloneMessages {

  /** 取り込みを飛ばしたときの通知。理由と経路の全組み合わせに文面がある(全称規定)。 */
  fun importSkipped(reason: ImportSkipReason, source: RepositorySource, projectName: String): String =
    when (reason) {
      ImportSkipReason.NAME_TAKEN -> nameTaken(source, projectName)
      ImportSkipReason.NO_WORKSPACE -> noWorkspace(source)
      ImportSkipReason.IMPORT_FAILED -> importFailed(source)
      ImportSkipReason.LOCATION_REJECTED -> locationRejected(source, projectName)
    }

  /** 取り込みが成功したときの通知。 */
  fun imported(source: RepositorySource, projectName: String): String =
    when (source) {
      RepositorySource.CLONED_NOW -> "clone しました。プロジェクト `$projectName` を取り込みました。"
      RepositorySource.ADOPTED_EXISTING ->
        "既にあったリポジトリを取り込みました。プロジェクト `$projectName` を開きました。"
    }

  /** (a0) 宛先に同一 URL のリポジトリがある場合の同意文. */
  const val adoptConsent: String =
    "この場所には同じリポジトリの clone が既にあります。これを取り込みますか?" +
      "(clone はやり直しません。中身は変更しません。)" +
      "前回が中断されていた場合、内容は不完全かもしれません。 やり直したい場合は取り込まず、別の空の場所を指定してください。"

  /** (a) 宛先が非空で (a0) にも当たらない場合。削除を一切促さない。 */
  const val occupied: String =
    "指定した場所は空ではないため、clone を行いませんでした。何も変更していません。別の空の場所を指定してやり直してください。"

  /** clone 自体が失敗したときの定型文。C16: 「空」に言及しない — 非空の宛先は入口判定で (a)/(a0) に分岐しこの経路に入らないため。 */
  const val cloneFailed: String = "clone できませんでした。リポジトリへアクセスできない可能性があります。"

  /** (b) 中断・失敗後、宛先が空である場合の文面。 */
  const val cloneIncompleteNothingLeft: String =
    "clone は完了しませんでした。指定した場所には何も残っていません。 やり直すか、別の場所を指定してください。"

  /**
   * (b) 中断・失敗後、宛先が非空である場合の文面。A9 の意図的な例外として宛先パスを含む唯一の通知
   * (ユーザー自身がその場で入力した値のため)。`cleanup()` は削除を試行するだけで、ロック・権限・
   * 同時書き込みにより残ることがあり、異常終了時はそもそも走らないため、「必ず残る/消える」は主張しない。
   */
  fun cloneIncompleteLeftovers(destination: File): String =
    "clone は完了しませんでした。このプラグインは何も削除していません。" +
      " `${destination.path}` に途中経過が残っています。中身を確認したうえで削除するか、別の場所でやり直してください。"

  /** guard が取れなかった場合。 */
  const val busy: String = "同じ場所への clone が実行中です。完了を待ってからやり直してください。"

  /** 孤立登録(閉じている + location 一致)の解除同意。 */
  fun orphanConsent(projectName: String): String =
    "同名のプロジェクト `$projectName` がありますが、閉じており、場所は今回の clone 先と同じです。" +
      "前回の中断で残ったものと思われます。登録だけ解除して取り込み直しますか? ディスク上の内容は消えません。"

  /** 補償失敗 / 同意しなかった場合の手順案内。 */
  fun orphanCleanupInstructions(projectName: String): String =
    "ワークスペースに閉じたプロジェクト `$projectName` が残っています。" +
      "プロジェクトを右クリック → Delete → 「Delete project contents on disk」のチェックを外したまま OK してください。" +
      "チェックを入れると clone した内容も消えます。"

  private fun nameTaken(source: RepositorySource, projectName: String): String =
    when (source) {
      RepositorySource.CLONED_NOW ->
        "clone は完了しましたが、同名のプロジェクト `$projectName` が既にワークスペースにあるため取り込めませんでした。" +
          "既存のプロジェクトはそのままです。 clone した内容はそのまま残っています。"
      RepositorySource.ADOPTED_EXISTING ->
        "同名のプロジェクト `$projectName` が既にワークスペースにあるため取り込めませんでした。" +
          "既存のプロジェクトはそのままです。この場所の内容は変更していません。"
    }

  private fun noWorkspace(source: RepositorySource): String =
    when (source) {
      RepositorySource.CLONED_NOW ->
        "clone は完了しましたが、ワークスペースの場所を特定できなかったため取り込めませんでした。" +
          "手動でインポートしてください。clone した内容はそのまま残っています。"
      RepositorySource.ADOPTED_EXISTING ->
        "ワークスペースの場所を特定できなかったため取り込めませんでした。" +
          "この場所は今回 clone したものではありません。 手動でインポートしてください。この場所の内容は変更していません。"
    }

  private fun importFailed(source: RepositorySource): String =
    when (source) {
      RepositorySource.CLONED_NOW -> "clone は完了しましたが、取り込めませんでした。手動でインポートしてください。"
      RepositorySource.ADOPTED_EXISTING ->
        "指定した場所のリポジトリを取り込めませんでした。" +
          "この場所は今回 clone したものではありません。 手動でインポートしてください。"
    }

  private fun locationRejected(source: RepositorySource, projectName: String): String =
    when (source) {
      RepositorySource.CLONED_NOW ->
        "clone は完了しましたが、ワークスペース直下のこの場所は取り込めません" +
          "(Eclipse の制約により、ワークスペース直下ではフォルダ名をプロジェクト名 `$projectName` と同じにする必要があります)。" +
          "フォルダ名を `$projectName` にするか、ワークスペースの外の場所を指定してください。clone した内容はそのまま残っています。"
      RepositorySource.ADOPTED_EXISTING ->
        "ワークスペース直下のこの場所は取り込めません" +
          "(Eclipse の制約により、ワークスペース直下ではフォルダ名をプロジェクト名 `$projectName` と同じにする必要があります)。" +
          "フォルダ名を `$projectName` にするか、ワークスペースの外の場所を指定してください。この場所の内容は変更していません。"
    }
}
