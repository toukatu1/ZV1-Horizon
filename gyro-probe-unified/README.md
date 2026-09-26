# ZV-1 １フレーム実補正診断（独立アプリ）

元の ZV-1 4K MP4 を選択し、① Gyroflow Core 1.6.3 で記録ジャイロと
レンズを解析して補正を計算、② 中央付近の１フレームを RGBA 画素として
Gyroflow Core の `process_pixels::<RGBA8>` で変形する。左に原本、
右に変形後を表示する。画素差が 0 の場合は失敗と表示する。

このアプリは動画を書き出さず、Dance Recenter 本体にも手を加えない。
APK のビルド成功だけでは補正成立の証明にならない。Lenovo TAB P12 で
ZV-1 原本を使い、①②がともに成功し比較画像が適切か実機で確認する。

## ビルド

GitHub Actions `android-one-frame-probe.yml` は PR でビルドし、
`ZV1-one-frame-diagnostic.apk` を成果物として生成する。
公式 `gyroflow/gyroflow` の v1.6.3 と公式レンズプロファイルを
取得して ARM64 ネイティブライブラリをビルドする。アプリは Java と
Android Gradle Plugin でビルドする。この手順は本リポジトリでの CI
成功まで未確認である。

Android 側の `GyroflowBridge.nativeInit(Context)` と解析・画素変形の
JNI 関数は、同一の `.so` に含める。`verify_gyro_probe.py` はこの３関数
を含む４関数の存在を確認する。以前の診断版では別々の `.so` を組み合わせ、
解析時にネイティブ側で落ちた。

診断の `ERR|` は画面に表示する。ネイティブ SIGABRT やメモリ不足などは
Java 側で捕捉できない。実機でその場合はバグレポートの調査を続ける。

## 制限

- ZV-1 で生成した原本を使う。再エンコード後のファイルには記録ジャイロが
  含まれない場合がある。
- 画素差が 0 より大きいことだけではジャイロ由来と断定できないため、
  左右の画像と補正量・時刻も確認する。
- レンズプロファイルが検出できない動画は `ERR|` と表示する。
- この診断は１フレームのみ。動画全編の手ブレ補正・水平補正・音声・保存の
  性能を証明しない。

Gyroflow Core は GPL-3.0-or-later。ソースとライセンスは
https://github.com/gyroflow/gyroflow を参照。本診断版ソースも
GPL-3.0-or-later とする。
