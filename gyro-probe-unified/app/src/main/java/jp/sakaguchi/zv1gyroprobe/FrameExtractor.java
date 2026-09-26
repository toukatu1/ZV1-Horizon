package jp.sakaguchi.zv1gyroprobe;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Decode one original 4K frame, without FFmpeg or a whole-video export. */
final class FrameExtractor {
    static final class Frame {
        final Bitmap bitmap;
        final long timestampUs;
        Frame(Bitmap bitmap, long timestampUs) {
            this.bitmap = bitmap;
            this.timestampUs = timestampUs;
        }
    }

    static Frame at(ContentResolver resolver, Uri uri, long targetUs) throws IOException {
        String retrieverProblem = "中央フレームが空でした";
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r");
             MediaMetadataRetriever retriever = new MediaMetadataRetriever()) {
            if (fd == null) throw new IOException("動画を開けません");
            retriever.setDataSource(fd.getFileDescriptor());
            Bitmap b = retriever.getScaledFrameAtTime(targetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST, 640, 360);
            if (b == null) b = retriever.getFrameAtTime(targetUs,
                    MediaMetadataRetriever.OPTION_CLOSEST);
            if (b != null) return new Frame(b, targetUs);
        } catch (Exception e) { retrieverProblem = e.toString(); }

        // Some Android 4K implementations play video but MediaMetadataRetriever
        // returns null. Decode the center with Android's regular video codec.
        try { return usingCodec(resolver, uri, targetUs); }
        catch (Exception e) {
            throw new IOException("4Kフレーム取得失敗。静止画取得=" + retrieverProblem
                    + " / 動画デコーダ=" + e, e);
        }
    }

    private static Frame usingCodec(ContentResolver resolver, Uri uri, long targetUs)
            throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        boolean started = false;
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException("動画を再度開けません");
            extractor.setDataSource(fd.getFileDescriptor());
            int video = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    video = i; format = candidate; break;
                }
            }
            if (video < 0 || format == null) throw new IOException("映像トラックがありません");
            extractor.selectTrack(video);
            extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            codec.configure(format, null, null, 0);
            codec.start(); started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sentEos = false;
            // Hard limit avoids blocking indefinitely on a broken stream.
            for (int attempts = 0; attempts < 240; attempts++) {
                if (!sentEos) {
                    int inputIndex = codec.dequeueInputBuffer(100_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input == null) throw new IOException("入力バッファがありません");
                        input.clear();
                        int count = extractor.readSampleData(input, 0);
                        if (count < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sentEos = true;
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, count,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outputIndex = codec.dequeueOutputBuffer(info, 100_000);
                if (outputIndex >= 0) {
                    boolean usable = info.size > 0 && info.presentationTimeUs >= targetUs;
                    if (usable) {
                        Image image = codec.getOutputImage(outputIndex);
                        try {
                            if (image == null) throw new IOException("動画デコーダの画像が空です");
                            Bitmap bitmap = sampleYuv(image);
                            return new Frame(bitmap, info.presentationTimeUs);
                        } finally {
                            if (image != null) image.close();
                            codec.releaseOutputBuffer(outputIndex, false);
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                }
            }
            throw new IOException("中央まで映像を復号できませんでした");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("動画デコーダ: " + e, e); }
        finally {
            if (codec != null) {
                try { if (started) codec.stop(); } catch (Exception ignored) {}
                codec.release();
            }
            extractor.release();
        }
    }

    private static Bitmap sampleYuv(Image image) throws IOException {
        if (image.getFormat() != ImageFormat.YUV_420_888)
            throw new IOException("非対応の画像形式: " + image.getFormat());
        Rect crop = image.getCropRect();
        int width = 640;
        int height = Math.max(1, Math.round(width * (float) crop.height() / crop.width()));
        int[] pixels = new int[width * height];
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer y = planes[0].getBuffer();
        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        for (int row = 0; row < height; row++) {
            int sy = crop.top + Math.min(crop.height() - 1, row * crop.height() / height);
            for (int col = 0; col < width; col++) {
                int sx = crop.left + Math.min(crop.width() - 1, col * crop.width() / width);
                int yy = y.get(sy * planes[0].getRowStride()
                        + sx * planes[0].getPixelStride()) & 255;
                int uu = u.get((sy / 2) * planes[1].getRowStride()
                        + (sx / 2) * planes[1].getPixelStride()) & 255;
                int vv = v.get((sy / 2) * planes[2].getRowStride()
                        + (sx / 2) * planes[2].getPixelStride()) & 255;
                int c = Math.max(0, yy - 16), d = uu - 128, e = vv - 128;
                int r = Math.min(255, Math.max(0, (298 * c + 409 * e + 128) >> 8));
                int g = Math.min(255, Math.max(0, (298 * c - 100 * d - 208 * e + 128) >> 8));
                int b = Math.min(255, Math.max(0, (298 * c + 516 * d + 128) >> 8));
                pixels[row * width + col] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }
}
