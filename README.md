# G2 カメラ コンパニオン（Android）

G2 カメラアプリ（Even アプリ内 WebView）の相棒になる小さな Android アプリ。

- カメラを**フォアグラウンドサービス（種別 camera）**で保持するので、**スマホの画面が消えていても**映像の配信と撮影が止まらない
- `127.0.0.1:8787` のローカル HTTP で G2 カメラアプリに映像を渡す（WebView の `getUserMedia` 許可が不要になる）
- 撮影した写真は**「写真」アプリの `DCIM/G2Camera`** に直接保存される（NAS 同期アプリなどにもそのまま乗る）

通信仕様は `../docs/companion-protocol.md`。G2 カメラアプリ側は起動時に `GET /status` を見て、返れば自動でこの経路を使う。

> **状態**：コードは書いてあるが、この環境には Android SDK が無く、**まだ一度もビルドしていない**。最初のビルドでエラーが出たら、そのログを貼れば直せる。

## ビルド（GitHub Actions）

JIMTOF 案内アプリと同じ方式。`companion-android/` を**単独のリポジトリ**として push する（`.github/workflows/build.yml` がこのフォルダ直下にあるため）。

1. GitHub に新しいリポジトリを作る（例：`g2cam-companion`。Actions を使うため、ビルドの間は公開にして、終わったら非公開に戻す運用）
2. このフォルダの中身を push する
   ```bash
   cd companion-android
   git init -b main && git add . && git commit -m "G2 camera companion"
   git remote add origin https://github.com/<ユーザー名>/g2cam-companion.git
   git push -u origin main
   ```
3. リポジトリの **Actions** タブで「APK をビルド」が走る → 終わったら成果物 `g2cam-companion-debug` をダウンロード（zip の中に `app-debug.apk`）
4. APK をスマホに入れてインストール（「提供元不明のアプリ」を許可）

Android Studio で開いてビルドしても同じ（Gradle 8.7 / JDK 17）。

## 使いかた

1. アプリを開き、カメラ権限と通知を許可
2. **「電池の最適化から除外する」** を押して許可。vivo / OriginOS ではさらに「アプリ情報」→ 電池 →「バックグラウンドでの高消費電力を許可」をオンにし、最近のタスクでこのアプリをロックしておく
3. **この画面を出したまま**「カメラを開始」を押す（Android 11 以降、バックグラウンドからはカメラ付きサービスを始められない）
4. Even アプリに切り替えて G2 カメラを開く。診断欄に「コンパニオンアプリ」と出れば成功
5. 画面を消しても、通知が出ている間は映像と撮影が続く。止めるときは通知の「停止」かアプリの「停止」

## 構成

| ファイル | 役割 |
| --- | --- |
| `app/src/main/java/com/ef64nagaoka/g2cam/CameraService.kt` | カメラ保持（CameraX の ImageAnalysis と ImageCapture）、フォアグラウンド化、WakeLock、向きの追従、DCIM への保存 |
| `.../CompanionServer.kt` | NanoHTTPD の HTTP サーバー（`/status` `/frame` `/capture` `/facing`、CORS） |
| `.../MainActivity.kt` | 開始／停止、権限、電池の最適化除外の案内 |

## 既知の限界

- 画面 OFF 中もカメラが動くのは、**画面が出ている間にサービスを始めた**場合だけ。端末が再起動したりシステムにサービスを落とされたら、もう一度アプリを開いて開始する（自動再開はしない。裏からの再開は Android が拒否するため）
- 端末の向きは加速度センサーで追従するが、機種によっては画面 OFF 中にセンサーが止まり、向きが固定されることがある
- 映像は長辺 320px の JPEG（G2 の表示が 288×144 までなのでこれで足りる）。フロントカメラは鏡像にしていない
- 他のアプリがカメラを使い始めると Android がこのアプリのカメラを奪うことがある（その場合は開始し直し）
