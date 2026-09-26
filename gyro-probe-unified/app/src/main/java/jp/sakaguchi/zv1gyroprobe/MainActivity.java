package jp.sakaguchi.zv1gyroprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;

import jp.sakaguchi.dancerecenter.GyroflowBridge;

/** Select an original Sony 4K file, prepare Gyroflow Core, render one frame. */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO = 101;
    private static final int PREVIEW_WIDTH = 640;
    private TextView status;
    private ImageView before, after;
    private Button prepare, render;
    private ParcelFileDescriptor source;
    private int sourceW, sourceH, fpsX1000 = 30000;
    private long durationMs;
    private volatile boolean prepared;
    private String startupError;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        try { GyroflowBridge.nativeInit(getApplicationContext()); }
        catch (Throwable e) { startupError = e.toString(); }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(18, 12, 18, 12);
        TextView info = new TextView(this);
        info.setText("ZV-1 4K原本を選び、①解析、②1フレーム補正を試します。レンズ未登録なら仮の画角で動作だけ検証します。動画保存はしません。");
        root.addView(info);
        Button pick = new Button(this); pick.setText("ZV-1の4K動画を選ぶ"); root.addView(pick);
        prepare = new Button(this); prepare.setText("① ジャイロ解析＋補正計算");
        render = new Button(this); render.setText("② 1フレーム画素変換");
        prepare.setEnabled(false); render.setEnabled(false);
        root.addView(prepare); root.addView(render);
        status = new TextView(this); status.setTextIsSelectable(true); root.addView(status);
        LinearLayout pictures = new LinearLayout(this);
        before = new ImageView(this); after = new ImageView(this);
        before.setScaleType(ImageView.ScaleType.FIT_CENTER);
        after.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pictures.addView(before, new LinearLayout.LayoutParams(0, 440, 1));
        pictures.addView(after, new LinearLayout.LayoutParams(0, 440, 1));
        root.addView(pictures);
        ScrollView scroll = new ScrollView(this); scroll.addView(root); setContentView(scroll);
        status.setText(startupError == null ? "元のZV-1動画を選択してください" : "起動エラー: " + startupError);
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            startActivityForResult(intent, PICK_VIDEO);
        });
        prepare.setOnClickListener(v -> startPrepare());
        render.setOnClickListener(v -> startRender());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null) return;
        try {
            if (source != null) source.close();
            Uri uri = data.getData();
            source = getContentResolver().openFileDescriptor(uri, "r");
            if (source == null) throw new IOException("動画を開けません");
            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                mmr.setDataSource(source.getFileDescriptor());
                sourceW = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                sourceH = Integer.parseInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                durationMs = Long.parseLong(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                String rate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE);
                if (rate != null) fpsX1000 = Math.round(Float.parseFloat(rate) * 1000);
            }
            prepared = false; before.setImageDrawable(null); after.setImageDrawable(null);
            prepare.setEnabled(startupError == null); render.setEnabled(false);
            status.setText(String.format(Locale.US, "選択: %dx%d / %.1f秒 / %.3ffps",
                    sourceW, sourceH, durationMs / 1000.0, fpsX1000 / 1000.0));
        } catch (Throwable e) { prepare.setEnabled(false); show("選択エラー: " + e); }
    }

    private void startPrepare() {
        prepare.setEnabled(false); render.setEnabled(false); show("① ジャイロ解析中…");
        new Thread(() -> {
            String result;
            try { result = GyroflowBridge.nativePrepareStabilization(source.getFd(), durationMs,
                    sourceW, sourceH, fpsX1000); }
            catch (Throwable e) { result = "ERR|" + e; }
            final String response = result;
            runOnUiThread(() -> {
                prepared = response.startsWith("OK|");
                show("① " + response);
                prepare.setEnabled(true); render.setEnabled(prepared);
            });
        }, "gyro-prepare").start();
    }

    private void startRender() {
        if (!prepared) return;
        render.setEnabled(false); show("② 画素変換中…");
        new Thread(() -> {
            String result;
            Bitmap first = null, last = null;
            try (MediaMetadataRetriever mmr = new MediaMetadataRetriever()) {
                mmr.setDataSource(source.getFileDescriptor());
                long stampUs = Math.min(durationMs * 500, Math.max(0, durationMs * 1000 - 100000));
                Bitmap frame = mmr.getFrameAtTime(stampUs, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) throw new IOException("中央フレームを取得できません");
                int previewH = Math.max(1, Math.round(PREVIEW_WIDTH * (float) frame.getHeight() / frame.getWidth()));
                first = Bitmap.createScaledBitmap(frame, PREVIEW_WIDTH, previewH, true);
                int[] pixels = new int[PREVIEW_WIDTH * previewH];
                first.getPixels(pixels, 0, PREVIEW_WIDTH, 0, 0, PREVIEW_WIDTH, previewH);
                byte[] input = new byte[pixels.length * 4], output = new byte[input.length];
                for (int i = 0; i < pixels.length; i++) {
                    int p = pixels[i], j = i * 4;
                    input[j] = (byte)(p >>> 16); input[j+1] = (byte)(p >>> 8);
                    input[j+2] = (byte)p; input[j+3] = (byte)(p >>> 24);
                }
                result = GyroflowBridge.nativeStabilizeFrame(source.getFd(), durationMs,
                        sourceW, sourceH, fpsX1000, stampUs, input, output);
                if (result.startsWith("OK|")) {
                    for (int i = 0; i < pixels.length; i++) {
                        int j = i * 4;
                        pixels[i] = ((output[j+3] & 255) << 24) | ((output[j] & 255) << 16)
                                | ((output[j+1] & 255) << 8) | (output[j+2] & 255);
                    }
                    last = Bitmap.createBitmap(pixels, PREVIEW_WIDTH, previewH, Bitmap.Config.ARGB_8888);
                }
            } catch (Throwable e) { result = "ERR|" + e; }
            final String response = result;
            final Bitmap inputImage = first, outputImage = last;
            runOnUiThread(() -> {
                before.setImageBitmap(inputImage); after.setImageBitmap(outputImage);
                show("② " + response + "\n左: 原本 / 右: Gyroflow Core実補正");
                render.setEnabled(true);
            });
        }, "gyro-frame").start();
    }

    private void show(String message) { status.setText(message); }
    @Override protected void onDestroy() {
        try { if (source != null) source.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }
}
