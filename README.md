# SIM Pilot

Galaxy S26 Ultra（SM-S948Q / One UI 8.5）を最優先の検証端末としつつ、他のAndroid DSDV端末でも実機の能力を検出して既定SIMを管理するAndroidアプリです。

## 主な機能

- データ、通話、メッセージの既定SIMを完全に独立して変更
- 通信品質の連続悪化を検知してデータSIMを自動切替
- 通話・メッセージをデータSIMへ追従させるか個別に設定
- Wi-Fi接続中は自動フェイルオーバーを停止
- Wi-Fi接続時にデータ・通話・メッセージを戻すか、どのSIMへ戻すかを個別に設定
- 挿入中のSIMと回線名を自動検出し、存在しない復帰先だけを安全に保留
- 通話中の切替禁止、連続判定、切替後クールダウンで誤動作を抑制
- 応答時間、電波状態、ネットワーク検証、64KB実効速度テストを組み合わせた判定
- VPNやIMS・MMSなどの専用ネットワークを除外し、既定SIMの物理的な通常インターネット経路だけを測定
- 遅延測定は複数エンドポイントで再試行し、一時的な測定不能は低品質へ加算せず次回へ保留
- 起動直後のServiceState未取得を圏外と区別し、正常な実測値を電波表示だけで低品質にしない
- Direct Boot対応のForeground Service、再起動・アプリ更新後の監視自動復帰、Android 17のPromoted Ongoing通知
- Android 12以降のSplashScreen APIを使った、通信リングが回転する起動アニメーション
- 品質判定、切替保留理由、Shizuku待ち、SIM Pilot／外部からの既定SIM変更を端末内JSONLログへ記録
- 実機の `ISub` Binder Stubからデータ・通話・SMSのtransactionを動的解決し、OEM／Androidバージョン差へ追従
- 切替方式を検出できない役割は操作を無効化し、誤ったBinder呼び出しを防止
- Material Design 3、動的カラー、edge-to-edge、画面幅に応じた適応レイアウト

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
2. SIM Pilotを開き、電話・位置情報・通知を許可します。
3. Shizukuのアクセス許可を与えます。
4. 「データSIMを自動切替」をONにします。
5. 安定運用のため、端末設定でSIM Pilotをバッテリー最適化の対象外にします。

監視またはWi-Fi復帰が有効なら、端末再起動後は画面を開かずに監視サービスを自動起動します。設定はDirect Boot領域に保存されるため、ロック解除前の起動イベントでも復帰できます。ロック解除前は通常Shizukuが未起動なので切替を行わず、「Shizukuの起動待ち」としてログへ記録します。解除後またはShizuku復帰通知を受けると即座に再評価します。

## 診断ログ

最大約10MB（2MB×現在分＋4世代）のJSON Lines形式で、Direct Boot対応のアプリ専用領域に保存します。電話番号、ICCID、IMSI、IMEIは記録しません。各監視周期には通信経路、SIM名／ID、既定SIM、品質値としきい値、判定、切替しなかった理由、Shizuku状態を含みます。SIM Pilotが要求した変更は `app/ui_manual`、`app/auto_failover`、`app/wifi_restore`、それ以外の変更は `external_user_or_system` と記録します。

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

通常は小さな204応答だけを確認し、約5分ごと、または遅延悪化時に64KBをダウンロードして実効速度を測ります。既定値では速度テスト分が最大約18MB/日です（悪化時の追加測定を除く）。しきい値、連続回数、監視間隔、クールダウンはアプリ内で調整できます。

## 実機で確認した切替経路

| 対象 | ISub transaction | 検証先 |
|---|---:|---|
| データ | 31 | `mActiveDataSubId` / `multi_sim_data_call` |
| 通話 | 34 | Telecom `defaultOutgoing` / `multi_sim_voice_call` |
| メッセージ | 37 | `defaultSmsSubId` / `multi_sim_sms` |

`settings put global multi_sim_data_call` だけでは表示値しか変わらず、実データ回線は切り替わりません。このため本アプリは設定値の直接書換えではなく、Samsung端末上のSubscription Binderをshell権限で呼び、変更後の値も検証します。

## プライバシー

電話番号、ICCID、IMSI、IMEIは取得・保存しません。回線名、SIMスロット、電波レベル、既定SIM ID、品質測定値だけを端末内で扱います。疎通確認先は Android connectivity check、速度測定先は Cloudflare Speed Testです。
