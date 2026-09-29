# ZV-1 v15 補正ライブラリのクラウドビルド

状態: GitHub Actions投入用。YAMLとスクリプトの静的確認のみ実施。クラウドビルド・実機テストはまだ実施していません。

目的: 水平ロックOFF、ジャイロ手ブレ補正の弱・中・強（smoothness 0.25 / 0.5 / 1.0）を含むAndroid arm64用の `libdance_gyroflow_jni.so` を作ります。

## 内容と公開範囲

- Rust JNIのソース、同梱Gyroflow Core 1.6.3のソースと必要なリソース
- Cargo.lock、NDK r27d指定、自動ビルドと静的検査
- 署名鍵、動画、APK、旧ネイティブライブラリは含みません。
- コード中には既存JNIクラス名 `jp.sakaguchi.dancerecenter.GyroflowBridge` が含まれます。
- 公開リポジトリへ追加すると、この一式は誰でも読めるようになります。

## 実行

フォルダの中身をリポジトリ直下へ配置します。`main` または `codex/zv1-v15-native` への対象ファイルのpushで実行します。既定ブランチに導入後はActions画面から手動実行も可能です。

NDK 27.3.13750724、Rust stable＋Androidターゲットを導入し、Cargo.lockを固定してコンパイルします。実際のRustバージョンとソース・バイナリのハッシュを成果物へ記録します。CPU指定はAndroidアプリ起動時のNO_WGPU/NO_OPENCLで行っており、Rustのビルド機能を削るフラグではありません。

成功すると `zv1-v15-native-arm64` に新しい.so、SHA256SUMS、build-info.jsonが保存されます。これはAPKではありません。

## APKまでの残りの手順

1. 成果物をダウンロードし、コミット・ハッシュ・v15マーカーを確認します。
2. 既存のAndroidプロジェクトの `native/lib/arm64-v8a/libdance_gyroflow_jni.so` へ差し替えます。
3. 既存のSDK・ECJとbuild.shでAPKを生成し、保管済みの同じPK8鍵とPEM証明書で署名します。Gradleへの移行やGitHub Secretsへの鍵登録は不要です。
4. APKの署名、JNIシンボル、同梱.soの一致を確認します。
5. TAB P12実機で弱・中・強をそれぞれ再解析し、5秒の保存動画で揺れと画角を比較します。

この一式はGyroflowのGPL-3.0に基づくコードを含みます。ライセンスと既存の著作権表示は保持しています。
