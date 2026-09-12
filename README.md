# SIM Pilot

Galaxy S26 Ultra（SM-S948Q / One UI 8.5）の DSDV 環境向けに、povo と LINEMO の既定SIMを管理するAndroidアプリです。

## 主な機能

- データ、通話、メッセージの既定SIMを完全に独立して変更
- 通信品質の連続悪化を検知してデータSIMを自動切替
- 通話・メッセージをデータSIMへ追従させるか個別に設定
- Wi-Fi接続中は判定・切替を必ず停止
- 通話中の切替禁止、連続判定、切替後クールダウンで誤動作を抑制
- 応答時間、電波状態、ネットワーク検証、64KB実効速度テストを組み合わせた判定
- Foreground Service、再起動後の監視復帰、Android 17のPromoted Ongoing通知
- Material Design 3、動的カラー、edge-to-edge、画面幅に応じた適応レイアウト

## 対応環境

- `compileSdk 37` / `targetSdk 37` / `minSdk 24`
- Galaxy S26 Ultra SM-S948Q、Android 16、One UI 8.5で実機検証
- Shizuku または互換実装が必要（ADB権限で起動）

SIM切替はSamsung One UI 8.5で確認した `ISub` transactionをShizuku UserService（UID 2000）から呼びます。端末や大型アップデートで内部APIが変わる可能性があるため、現時点では上記実機を対象とします。

## ビルド

```bash
./gradlew testDebugUnitTest assembleDebug
```

APKは `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

## 初期設定

1. ShizukuをワイヤレスデバッグまたはADBで起動します。
2. SIM Pilotを開き、電話・位置情報・通知を許可します。
3. Shizukuのアクセス許可を与えます。
4. 「データSIMを自動切替」をONにします。
5. 安定運用のため、端末設定でSIM Pilotをバッテリー最適化の対象外にします。

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
