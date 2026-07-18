# 手動検証: ステップ2 Code Suggestions

devcontainerはheadlessのため、以下はGUIのあるEclipse実機で実施する。

## 準備
1. Eclipse 2025-06 (4.36) + JDK 21。
2. リポジトリルートで `mvn -q verify` 後、`bundles/gitlab-eclipse-plugin/target/gitlab-eclipse-plugin-0.1.0-SNAPSHOT.jar` を Eclipse の `dropins/` へコピーして再起動(`-clean` 推奨)。
3. Preferences > GitLab で接続URLとPAT(Duo有効なアカウント)を設定。
4. 初回はLSバイナリDL(約300MB)がProgressビューに出るので完了を待つ。

## 検証項目
| # | 操作 | 期待結果 |
|---|---|---|
| 1 | .javaファイルを開き、メソッド本体内で `int sum = ` まで入力して手を止める | 250ms+LS応答後、グレーのghost textが表示される |
| 2 | ghost text表示中に TAB | 全文が挿入されghost text消滅。カーソルは挿入末尾 |
| 3 | ghost text表示中に Ctrl+→(macはCmd+→) | 1単語だけ挿入され、残りがghost textのまま |
| 4 | ghost text表示中に ESC | ghost textが消える。ドキュメント無変更 |
| 5 | ghost textなしで TAB | 通常のインデント挿入(素通し) |
| 6 | Ctrl+Alt+/(macはCmd+Alt+/) | 待たずに即補完リクエスト |
| 7 | `// 1からnまでの合計を返す関数` と書いて改行し手を止める | (生成系判定時)ストリーミング完了後に複数行ghost text一括表示。1行目インライン+2行目以降ブロック |
| 8 | 複数行ghost text表示中に TAB | 複数行全体が挿入される |
| 9 | ghost text表示中にタイピング継続 | 旧候補が消え、停止後に新候補 |
| 10 | ghost text表示中にマウスで別位置クリック | 候補が消える |
| 11 | エディタを切り替える | 候補が消え、新エディタ側で補完が動く |
| 12 | (管理者)GitLab側のDuo利用ログ | suggestion_shown / suggestion_accepted が記録される |

## 既知の制約(仕様)
- ファイル最終行では2行目以降のブロックghost textが表示されない(CodeMining APIの制約)
- ghost text表示中の1文字タイプは候補を消す(typed-prefix消費は未実装、スコープ外)
- 候補内タブは先頭のみスペース表示(行中タブは幅ゼロ描画になり得る)
- win-arm64非対応(ステップ1からの既知の制限)

## 問題発生時
- Error Logビュー(Window > Show View)で `gitlab-eclipse-plugin` のエントリを確認
- LSログ: Preferences > GitLab の Language Server Log Level を debug に
