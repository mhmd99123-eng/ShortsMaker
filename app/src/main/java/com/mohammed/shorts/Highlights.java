package com.mohammed.shorts;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds the most interesting parts of a video, completely offline.
 *
 * <p>Signals (per half second): loudness, sudden rises in loudness above the local level
 * (laughs, shouts, impacts), visual activity, and scene cuts. A window of the requested length is
 * scored by its average plus a bonus for a strong opening, then its edges are moved a little to a
 * pause or a cut so a Short does not start or stop in the middle of a word.
 */
final class Highlights {
  static final long BIN_MS = 500;

  interface Listener {
    void progress(float fraction);

    boolean cancelled();
  }

  /** Raw signals, one value per {@link #BIN_MS}. */
  static final class Signal {
    float[] loudDb; // dBFS, -90 when silent
    float[] motion; // 0…1
    float[] cut; // 0…1
    long durationMs;
    boolean hasAudio;
  }

  // ---------------------------------------------------------------- analysis (Android)

  static Signal analyze(Context c, Uri uri, long durationMs, Listener l) throws Exception {
    int bins = (int) Math.max(1, (durationMs + BIN_MS - 1) / BIN_MS);
    Signal s = new Signal();
    s.durationMs = durationMs;
    s.loudDb = new float[bins];
    s.motion = new float[bins];
    s.cut = new float[bins];
    Arrays.fill(s.loudDb, -90f);
    float[] audioDone = {0}, videoDone = {0};
    AtomicReference<Exception> failure = new AtomicReference<>();
    Listener audioL =
        new Listener() {
          public void progress(float f) {
            audioDone[0] = f;
            l.progress(audioDone[0] * .45f + videoDone[0] * .55f);
          }

          public boolean cancelled() {
            return l.cancelled();
          }
        };
    Listener videoL =
        new Listener() {
          public void progress(float f) {
            videoDone[0] = f;
            l.progress(audioDone[0] * .45f + videoDone[0] * .55f);
          }

          public boolean cancelled() {
            return l.cancelled();
          }
        };
    Thread audio =
        new Thread(
            () -> {
              try {
                s.hasAudio = readAudio(c, uri, s.loudDb, audioL);
              } catch (Exception e) {
                // A video without readable audio still gets a visual-only analysis.
                s.hasAudio = false;
              }
            },
            "highlights-audio");
    audio.start();
    try {
      readVideo(c, uri, durationMs, s.motion, s.cut, videoL);
    } catch (Exception e) {
      failure.set(e);
    }
    audio.join();
    if (l.cancelled()) throw new InterruptedException();
    if (failure.get() != null && !s.hasAudio) throw failure.get();
    return s;
  }

  private static boolean readAudio(Context c, Uri uri, float[] outDb, Listener l)
      throws Exception {
    MediaExtractor ex = new MediaExtractor();
    MediaCodec codec = null;
    try {
      ex.setDataSource(c, uri, null);
      int track = -1;
      MediaFormat format = null;
      for (int i = 0; i < ex.getTrackCount(); i++) {
        MediaFormat f = ex.getTrackFormat(i);
        String mime = f.getString(MediaFormat.KEY_MIME);
        if (mime != null && mime.startsWith("audio/")) {
          track = i;
          format = f;
          break;
        }
      }
      if (track < 0) return false;
      ex.selectTrack(track);
      long durationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int encoding = 2; // ENCODING_PCM_16BIT
      double[] sum = new double[outDb.length];
      long[] count = new long[outDb.length];
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inputDone = false, outputDone = false;
      long frameIndex = 0; // audio frames (samples per channel) decoded so far
      long firstPtsUs = -1;
      while (!outputDone) {
        if (l.cancelled()) return false;
        if (!inputDone) {
          int in = codec.dequeueInputBuffer(5000);
          if (in >= 0) {
            ByteBuffer buf = codec.getInputBuffer(in);
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
              codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inputDone = true;
            } else {
              codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
              ex.advance();
            }
          }
        }
        int out = codec.dequeueOutputBuffer(info, 5000);
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat f = codec.getOutputFormat();
          sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          if (f.containsKey(MediaFormat.KEY_PCM_ENCODING))
            encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
        } else if (out >= 0) {
          if (info.size > 0) {
            ByteBuffer buf = codec.getOutputBuffer(out);
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs;
            // Use the buffer's own timestamp so gaps or edit lists do not shift the curve.
            long baseFrame = Math.max(frameIndex, (info.presentationTimeUs - Math.max(0, firstPtsUs)) * sampleRate / 1_000_000L);
            int bytesPerSample = encoding == 4 ? 4 : 2; // 4 = ENCODING_PCM_FLOAT
            int frames = info.size / (bytesPerSample * channels);
            long samplesPerBin = Math.max(1, sampleRate * BIN_MS / 1000);
            for (int f = 0; f < frames; f++) {
              double mix = 0;
              for (int ch = 0; ch < channels; ch++) {
                mix += bytesPerSample == 4 ? buf.getFloat() : buf.getShort() / 32768.0;
              }
              mix /= channels;
              int bin = (int) ((baseFrame + f) / samplesPerBin);
              if (bin >= 0 && bin < sum.length) {
                sum[bin] += mix * mix;
                count[bin]++;
              }
            }
            frameIndex = baseFrame + frames;
            if (durationUs > 0) l.progress(Math.min(1f, info.presentationTimeUs / (float) durationUs));
          }
          codec.releaseOutputBuffer(out, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
        }
      }
      for (int i = 0; i < outDb.length; i++) {
        if (count[i] == 0) continue;
        double rms = Math.sqrt(sum[i] / count[i]);
        outDb[i] = (float) Math.max(-90, 20 * Math.log10(rms + 1e-9));
      }
      l.progress(1f);
      return true;
    } finally {
      if (codec != null) {
        try {
          codec.stop();
        } catch (Exception ignored) {
        }
        codec.release();
      }
      ex.release();
    }
  }

  private static void readVideo(
      Context c, Uri uri, long durationMs, float[] motion, float[] cut, Listener l)
      throws Exception {
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      r.setDataSource(c, uri);
      // At most ~480 decoded thumbnails, at least one per second.
      long stepMs = Math.max(1000, durationMs / 480);
      int samples = (int) Math.max(1, durationMs / stepMs);
      float[] times = new float[samples], mot = new float[samples], cuts = new float[samples];
      int[] prev = null;
      float[] prevHist = null;
      for (int i = 0; i < samples; i++) {
        if (l.cancelled()) return;
        long t = i * stepMs + stepMs / 2;
        Bitmap b =
            r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64);
        times[i] = t;
        if (b == null) continue;
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        b.recycle();
        int[] luma = new int[px.length];
        float[] hist = new float[24];
        for (int k = 0; k < px.length; k++) {
          int p = px[k], rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
          luma[k] = (rr * 299 + gg * 587 + bb * 114) / 1000;
          hist[rr >> 5]++;
          hist[8 + (gg >> 5)]++;
          hist[16 + (bb >> 5)]++;
        }
        for (int k = 0; k < 24; k++) hist[k] /= px.length;
        if (prev != null && prev.length == luma.length) {
          long diff = 0;
          for (int k = 0; k < luma.length; k++) diff += Math.abs(luma[k] - prev[k]);
          mot[i] = diff / (255f * luma.length);
          float hd = 0;
          for (int k = 0; k < 24; k++) hd += Math.abs(hist[k] - prevHist[k]);
          cuts[i] = Math.min(1f, hd / 3f); // three channels, each sums to 1 → max 6, /3 → 0…2
        }
        prev = luma;
        prevHist = hist;
        l.progress((i + 1f) / samples);
      }
      // Spread the samples over the half-second bins.
      for (int b = 0; b < motion.length; b++) {
        float t = b * BIN_MS + BIN_MS / 2f;
        int i = (int) Math.min(samples - 1, Math.max(0, Math.floor((t - stepMs / 2f) / stepMs)));
        int j = Math.min(samples - 1, i + 1);
        float f = j == i ? 0 : Math.max(0, Math.min(1, (t - times[i]) / (times[j] - times[i])));
        motion[b] = mot[i] * (1 - f) + mot[j] * f;
      }
      for (int i = 0; i < samples; i++) {
        int b = (int) Math.min(motion.length - 1, Math.max(0, (times[i] - stepMs / 2f) / BIN_MS));
        cut[b] = Math.max(cut[b], cuts[i] > .45f ? cuts[i] : 0);
      }
    } finally {
      try {
        r.release();
      } catch (Exception ignored) {
      }
    }
  }

  // ---------------------------------------------------------------- scoring (pure Java)

  /** Interest per bin, 0…1. */
  static float[] interest(Signal s) {
    int n = s.loudDb.length;
    float[] loud = normalise(s.loudDb, 10, 95);
    float[] mot = normalise(s.motion, 10, 95);
    float[] excite = new float[n];
    // Rise above the local (20 s) average loudness.
    int half = (int) (10_000 / BIN_MS);
    double[] prefix = new double[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + s.loudDb[i];
    float quiet = percentile(s.loudDb, 10);
    for (int i = 0; i < n; i++) {
      int a = Math.max(0, i - half), b = Math.min(n, i + half + 1);
      float local = (float) ((prefix[b] - prefix[a]) / (b - a));
      excite[i] = clamp((s.loudDb[i] - local) / 8f);
    }
    float[] cutDensity = new float[n];
    int w = (int) (4000 / BIN_MS);
    for (int i = 0; i < n; i++) {
      float c = 0;
      for (int k = Math.max(0, i - w); k <= Math.min(n - 1, i + w); k++) c += s.cut[k];
      cutDensity[i] = clamp(c / 2f);
    }
    float[] out = new float[n];
    for (int i = 0; i < n; i++) {
      float v =
          s.hasAudio
              ? .40f * loud[i] + .25f * excite[i] + .25f * mot[i] + .10f * cutDensity[i]
              : .70f * mot[i] + .30f * cutDensity[i];
      if (s.hasAudio && s.loudDb[i] <= quiet + 1.5f) v *= .55f; // long silences are rarely good
      out[i] = v;
    }
    return smooth(out, 2);
  }

  /** Best non-overlapping windows of {@code clipMs}, strongest first. */
  static List<Clip> pick(Signal s, long clipMs, int count) {
    List<Clip> result = new ArrayList<>();
    long total = s.durationMs;
    if (total <= clipMs + 1500) {
      result.add(new Clip(0, total, 1f));
      return result;
    }
    float[] v = interest(s);
    float[] loud = normalise(s.loudDb, 10, 95);
    int n = v.length;
    int len = (int) Math.max(1, clipMs / BIN_MS);
    int hook = Math.min(len, (int) (3000 / BIN_MS));
    double[] prefix = new double[n + 1];
    for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + v[i];
    int last = (int) Math.min(n - len, (total - clipMs) / BIN_MS);
    float[] score = new float[last + 1];
    for (int i = 0; i <= last; i++) {
      float mean = (float) ((prefix[i + len] - prefix[i]) / len);
      float opening = (float) ((prefix[i + hook] - prefix[i]) / hook);
      score[i] = mean * .82f + opening * .18f;
    }
    boolean[] taken = new boolean[last + 1];
    float best = -1;
    for (int round = 0; round < count; round++) {
      int at = -1;
      for (int i = 0; i <= last; i++) if (!taken[i] && (at < 0 || score[i] > score[at])) at = i;
      if (at < 0) break;
      if (best < 0) best = Math.max(1e-6f, score[at]);
      for (int i = Math.max(0, at - len + 1); i <= Math.min(last, at + len - 1); i++) taken[i] = true;
      long start = snap(s, loud, at * BIN_MS, 3000, 0, total - clipMs);
      long end = snap(s, loud, start + clipMs, 1500, start + clipMs - 1500, Math.min(total, start + clipMs + 1500));
      end = Math.min(end, start + 180_000);
      Clip c = new Clip(start, end, clamp(score[at] / best) * .35f + clamp(score[at] * 1.6f) * .65f);
      boolean clash = false;
      for (Clip o : result) clash |= o.overlaps(c);
      if (!clash) result.add(c);
    }
    return result;
  }

  /** Move {@code t} within ±{@code range} to a quiet moment or a scene cut. */
  static long snap(Signal s, float[] loudNorm, long t, long range, long min, long max) {
    long bestT = Math.max(min, Math.min(max, t));
    float bestCost = Float.MAX_VALUE;
    for (long x = t - range; x <= t + range; x += BIN_MS) {
      if (x < min || x > max) continue;
      int b = (int) Math.min(loudNorm.length - 1, Math.max(0, x / BIN_MS));
      float cost = loudNorm[b] - .6f * s.cut[b] + .08f * Math.abs(x - t) / 1000f;
      if (cost < bestCost) {
        bestCost = cost;
        bestT = x;
      }
    }
    return Math.max(min, Math.min(max, bestT));
  }

  static float[] normalise(float[] v, int lowPct, int highPct) {
    float lo = percentile(v, lowPct), hi = percentile(v, highPct);
    float[] out = new float[v.length];
    float span = Math.max(1e-6f, hi - lo);
    for (int i = 0; i < v.length; i++) out[i] = clamp((v[i] - lo) / span);
    return out;
  }

  static float percentile(float[] v, int pct) {
    if (v.length == 0) return 0;
    float[] c = v.clone();
    Arrays.sort(c);
    return c[Math.min(c.length - 1, Math.max(0, Math.round((c.length - 1) * pct / 100f)))];
  }

  static float[] smooth(float[] v, int radius) {
    float[] out = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      float sum = 0;
      int n = 0;
      for (int k = Math.max(0, i - radius); k <= Math.min(v.length - 1, i + radius); k++) {
        sum += v[k];
        n++;
      }
      out[i] = sum / n;
    }
    return out;
  }

  static float clamp(float x) {
    return Math.max(0f, Math.min(1f, x));
  }
}
