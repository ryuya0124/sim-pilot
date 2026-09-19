# SIM Pilot

Galaxy S26 Ultra（SM-S948Q / One UI 8.5）を最優先の検証端末としつつ、他のAndroid DSDV端末でも実機の能力を検出して既定SIMを管理するAndroidアプリです。

## 主な機能

- データ、通話、メッセージの既定SIMを完全に独立して変更
- 電波強度・RSRQ・SINR・実効速度・遅延・疎通・残容量をスコア化してデータSIMを自動切替
- 無線状態はホームとは別の専用ページで表示し、開いている間は5秒ごとに更新
- NetMonster CoreでRIL値を検証・統合し、Primary / Secondary / Neighborの全セルをSIM別に保持
- PLMN、MCC/MNC、国コード、バンド、チャネル、ARFCN、帯域幅、CA、各方式のセル識別子を観測
- LTEのRSSI/RSRP/RSRQ/SNR/CQI/Timing Advance、NRのSS/CSI RSRP・RSRQ・SINR、旧世代方式のRSCP/EcNo/BER/CDMA・EVDO値など、端末から得られた信号項目を方式別に表示・記録
- CQI、帯域幅、CA、通信方式、Primary状態、Servingセルの切替頻度を保守的な無線インテリジェンスへ変換し、実測値と組み合わせて判定
- 電波レベル・在圏状態・通信方式・データ接続・通話状態・既定ネットワーク・SIM構成の変化を受けて即時再評価
- `-114 dBm`以下を既定の弱電波として減点し、品質低下中は3秒間隔で連続判定
- 一度の小さな疎通成功では悪化履歴を全消去せず、回復に応じて段階的に減衰
- 2.5秒未満に集中した回線コールバックは同じ不良サンプルとして扱い、Wi-Fi切断直後などの過渡状態による誤切替を抑制
- 画面復帰時は電波・疎通を先行表示し、通信量集計は別スレッドで更新して初回判定を塞がない
- 端末のスリープ中は監視・通信測定・自動切替を停止し、画面復帰時に再開
- 通話・メッセージをデータSIMへ追従させるか個別に設定
- Wi-Fi接続中は自動フェイルオーバーを停止
- Wi-Fi接続時にデータ・通話・メッセージを戻すか、どのSIMへ戻すかを個別に設定
- 挿入中のSIMと回線名を自動検出し、存在しない復帰先だけを安全に保留
- 通話中の切替禁止、連続判定、切替後クールダウンで誤動作を抑制
- 500kbpsを単純な合格ラインにせず、1Mbps未満・2.5Mbps未満・目標速度未満を段階的に減点
- 弱電波時は両SIMを比較。非既定SIMを直接測れない端末ではデータだけ一時切替して実測し、優位なら維持、劣位なら復帰
- 一時比較は目的別の測定上限を持ち、比較中であることを画面表示。品質救済では回線確立を最大30秒待ち、容量バランス目的は7秒で打ち切る。画面OFF時は進行中の通信測定も中断して元のSIMへ復帰
- VPNやIMS・MMSなどの専用ネットワークを除外し、既定SIMの物理的な通常インターネット経路だけを測定
- 遅延測定は複数エンドポイントで再試行し、一時的な測定不能は低品質へ加算せず次回へ保留
- 起動直後のServiceState未取得を圏外と区別し、正常な実測値を電波表示だけで低品質にしない
- Direct Boot対応のForeground Service、再起動・アプリ更新後の監視自動復帰
- Android 13以降では通知権限を要求せず、監視中も通知ドロワーへ表示しない
- Android 12以降のSplashScreen APIを使った、通信リングが回転する起動アニメーション
- 品質判定、切替保留理由、Shizuku待ち、SIM Pilot／外部からの既定SIM変更を端末内JSONLログへ記録
- 実機の `ISub` Binder Stubからデータ・通話・SMSのtransactionを動的解決し、OEM／Androidバージョン差へ追従
- 切替方式を検出できない役割は操作を無効化し、誤ったBinder呼び出しを防止
- Material Design 3、動的カラー、edge-to-edge、画面幅に応じた適応レイアウト
- 画面下部の常設タブで「ホーム」「無線」「設定」を直接切替。ホーム上の画面遷移ボタンを整理
- Androidの予測型「戻る」に対応し、設定の子ページと各トップページをジェスチャー進行に合わせて遷移
- 設定ホームから「通信品質」「Wi-Fi接続時」「データプラン」を独立ページとして編集
- SIM別の月次プラン、固定期間プラン、povo向け複数トッピングと手動残量に対応

## 対応環境

- `compileSdk 37` / `targetSdk 37` / `minSdk 31`
- Galaxy S26 Ultra SM-S948Q、Android 16、One UI 8.5で全機能を実機検証（最優先プロファイル）
- その他のAndroid 12以降は、実機の内部 `ISub` メソッドを安全に解決できた役割のみ利用可能
- Shizuku または Shizuku Plus が必要（ADB権限で起動）

SIM切替はShizuku UserService（UID 2000）から `ISub` を呼びます。SM-S948Qを含め、メソッド名から実機のtransactionを動的解決し、解決できた役割だけを有効化します。SM-S948Qでは動的に得たデータ31・通話34・SMS37で実機動作を確認済みです。この端末に限り、動的検出自体が使えない場合の非常用フォールバックとして同じ固定値を保持します。内部APIを利用するため、すべてのOEMでの動作を保証するものではありません。

## ビルド

```bash
./gradlew testDebugUnitTest assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## 初期設定

1. ShizukuまたはShizuku Plusをワイヤレスデバッグ／ADBで起動します。
2. SIM Pilotを開き、電話・位置情報を許可します。
3. Shizukuのアクセス許可を与えます。
4. 「データSIMを自動切替」をONにします。
5. 安定運用のため、端末設定でSIM Pilotをバッテリー最適化の対象外にします。

監視またはWi-Fi復帰が有効なら、端末再起動後は画面を開かずに監視サービスを自動起動します。設定はDirect Boot領域に保存されるため、ロック解除前の起動イベントでも復帰できます。ロック解除前は通常Shizukuが未起動なので切替を行わず、「Shizukuの起動待ち」としてログへ記録します。端末のロックが外れるたびに強制チェックを1回予約し、モバイル接続中なら速度も測定します。Shizukuが後から復帰した場合も同様に即時再評価し、同時に届く解除通知による二重測定は抑制します。

Android 13以降では通知権限を宣言・要求しないため、監視通知は通知ドロワーへ表示されません。Foreground Service自体はAndroidの要件として維持され、システムの「実行中のアプリ」管理画面には表示されます。Android 12ではOS仕様上Foreground Service通知を完全には隠せません。

スリープは `PowerManager.isInteractive` で判定します。スリープ移行時に監視タイマーと回線コールバックを止め、測定途中にスリープした場合も品質判定と切替を中断します。画面ONで回線監視を再登録して即時チェックし、ロック解除後の強制チェックも維持します。

## 診断ログ

現在ログは最大8MBのJSON Lines、過去ログは最大16世代のgzip圧縮JSON Linesとして、Direct Boot対応のアプリ専用領域に保存します。各NetMonster観測でPrimary / Secondary / Neighbor全セルの全取得値を間引かず記録し、無線ページ表示中の5秒観測も保存します。電話番号、ICCID、IMSI、IMEIは記録しません。各監視周期には通信経路、SIM名／ID、dBm、RSRP/RSRQ/SINR、CQI、帯域幅、CA、セル識別情報、無線評価、品質スコアと内訳、両SIMの比較値、残容量、判定、切替しなかった理由、Shizuku状態を含みます。SIM Pilotが要求した変更は `app/ui_manual`、`app/auto_failover`、`app/comparison_probe`、`app/comparison_revert`、`app/wifi_restore`、それ以外の変更は `external_user_or_system` と記録します。

接続中の端末からログと現在のシステム状態をまとめて取得できます。

```bash
./tools/pull-device-logs.sh 192.168.3.13:5555
```

現在ログだけを直接読む場合:

```bash
adb -s 192.168.3.13:5555 exec-out run-as dev.simpilot cat /data/user_de/0/dev.simpilot/files/diagnostics/sim-pilot-current.jsonl
```

設定画面の「Wi-Fi接続時」では、復帰機能全体とデータ・通話・メッセージの各対象を独立してON/OFFできます。「現在の既定SIMを復帰先にセット」を使うと、現在の3つの割り当てをまとめて記録できます。SIM名やSIMの有無は `SubscriptionManager` から毎回取得し、通信会社名をコードへ固定していません。

## 判定と通信量

通常は小さな204応答だけを確認し、定期確認・弱電波・RSRQ/SINR悪化・遅延悪化時に64KBをダウンロードして実効速度を測ります。電波、電波品質、遅延、速度を0〜100の悪化スコアへ合算し、既定値では45以上を低品質、20以上を品質低下として扱います。目標速度、弱電波dBm、連続回数、監視間隔、クールダウンは「設定 → 通信品質」で調整できます。NetMonster情報を取得できない端末やタイミングでは、従来のAndroid公開API値だけで判定を継続します。

「設定 → データプラン」ではSIMごとに次を設定できます。

- 毎月更新: 容量と更新日。31日など存在しない月はその月の末日に丸めます。
- 期間指定: 開始日、終了日、容量。
- 柔軟プラン: 開始日・終了日・容量が異なる複数のデータトッピング。povoの24時間・30日・365日などを併存できます。
- 手動残量: 事業者アプリの残量を優先。自動集計との差や複数トッピングの消費順が不明な場合に使います。

SIM別実績はShizukuのshell権限からAndroidのネットワーク統計を読みます。Galaxy S26 Ultraでは公開APIで加入者別取得が拒否されるため、`dumpsys netstats` の`subId`別・1時間バケットを端末内で集計するフォールバックを使います。加入者識別子は一時的なAPI照会以外に使わず、保存・画面表示・ログ出力をしません。統計にはOS側の集計遅延があり、プラン残量の正確性は事業者アプリが優先です。

残容量は通信品質が同等の場合のタイブレークです。圏外・疎通不能からの復旧が常に最優先で、現在回線が良好な状態で15分以上安定し、候補回線の電波も十分な場合のみ、残量率が15ポイント以上かつ500MB以上多いSIMを比較対象にします。

## 実機で確認した切替経路

| 対象 | ISub transaction | 検証先 |
|---|---:|---|
| データ | 31 | `mActiveDataSubId` / `multi_sim_data_call` |
| 通話 | 34 | Telecom `defaultOutgoing` / `multi_sim_voice_call` |
| メッセージ | 37 | `defaultSmsSubId` / `multi_sim_sms` |

`settings put global multi_sim_data_call` だけでは表示値しか変わらず、実データ回線は切り替わりません。このため本アプリは設定値の直接書換えではなく、Samsung端末上のSubscription Binderをshell権限で呼び、変更後の値も検証します。

## プライバシー

電話番号、ICCID、IMEIは取得・保存しません。加入者識別子はSIM別通信量を公開APIへ問い合わせる間だけメモリ上で扱い、保存・表示・ログ出力しません。診断のため、回線名、SIMスロット、電波値、帯域、PCI、TAC、Cell ID、既定SIM ID、品質測定値、通信量集計を端末内だけで扱います。セル識別情報はおおよその場所を推測できる場合があるため、ログ共有時は取り扱いに注意してください。疎通確認先は Android connectivity check、速度測定先は Cloudflare Speed Testです。

## サードパーティ

- [NetMonster Core](https://github.com/mroczis/netmonster-core) 1.3.0 — Copyright 2019 Michal Mroček、Apache License 2.0。Android Telephony/RIL情報の検証・統合と無線パラメータ取得に使用します。ライセンス全文をAPKとソースに同梱し、アプリの「設定 → ライセンス」から確認できます。

## ライセンス管理

SIM Pilot本体はCopyright © 2026 ryuya0124、All rights reservedです。公開リポジトリを閲覧できること自体は、複製・変更・再配布の許諾を意味しません。詳細は`LICENSE`を参照してください。

第三者コンポーネントは`THIRD_PARTY_NOTICES.md`でバージョン・著作権者・ライセンス・配布元を管理します。依存関係を追加または更新する際は、上流ライセンスの確認、通知の更新、必要なライセンス本文のAPK同梱を必須とします。
