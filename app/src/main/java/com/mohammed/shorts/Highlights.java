package com.mohammed.shorts;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

  /**
   * Visual signals come from key frames only (no full decoding), sampled by two retrievers in
   * parallel; audio comes from the shared, cached {@link AudioData}.
   */
  static Signal analyze(Context c, Uri uri, long durationMs, AudioData audio, Listener l)
      throws Exception {
    int bins = (int) Math.max(1, (durationMs + BIN_MS - 1) / BIN_MS);
    Signal s = new Signal();
    s.durationMs = durationMs;
    s.motion = new float[bins];
    s.cut = new float[bins];
    s.hasAudio = audio != null && audio.hasAudio;
    s.loudDb = s.hasAudio ? audio.loudDb(BIN_MS, bins) : new float[bins];
    if (!s.hasAudio) Arrays.fill(s.loudDb, -90f);
    readVideo(c, uri, durationMs, s.motion, s.cut, l);
    if (l.cancelled()) throw new InterruptedException();
    return s;
  }

  private static final class Frame {
    int[] luma;
    float[] hist;
  }

  private static Frame grab(MediaMetadataRetriever r, long ms) {
    Bitmap b = r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 48, 48);
    if (b == null) return null;
    int w = b.getWidth(), h = b.getHeight();
    int[] px = new int[w * h];
    b.getPixels(px, 0, w, 0, 0, w, h);
    b.recycle();
    Frame f = new Frame();
    f.luma = new int[px.length];
    f.hist = new float[24];
    for (int k = 0; k < px.length; k++) {
      int p = px[k], rr = (p >> 16) & 255, gg = (p >> 8) & 255, bb = p & 255;
      f.luma[k] = (rr * 299 + gg * 587 + bb * 114) / 1000;
      f.hist[rr >> 5]++;
      f.hist[8 + (gg >> 5)]++;
      f.hist[16 + (bb >> 5)]++;
    }
    for (int k = 0; k < 24; k++) f.hist[k] /= px.length;
    return f;
  }

  private static void readVideo(
      Context c, Uri uri, long durationMs, float[] motion, float[] cut, Listener l)
      throws Exception {
    long stepMs = Math.max(1000, durationMs / 300);
    int samples = (int) Math.max(2, durationMs / stepMs);
    Frame[] frames = new Frame[samples];
    int[] done = {0};
    int workers = 2;
    Thread[] threads = new Thread[workers];
    Exception[] error = {null};
    for (int w = 0; w < workers; w++) {
      int from = w * samples / workers, to = (w + 1) * samples / workers;
      threads[w] =
          new Thread(
              () -> {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                try {
                  r.setDataSource(c, uri);
                  for (int i = from; i < to && !l.cancelled(); i++) {
                    frames[i] = grab(r, i * stepMs + stepMs / 2);
                    synchronized (done) {
                      done[0]++;
                      l.progress(done[0] / (float) samples);
                    }
                  }
                } catch (Exception e) {
                  error[0] = e;
                } finally {
                  try {
                    r.release();
                  } catch (Exception ignored) {
                  }
                }
              },
              "highlights-video-" + w);
      threads[w].start();
    }
    for (Thread t : threads) t.join();
    if (error[0] != null && frames[0] == null) throw error[0];
    float[] mot = new float[samples], cuts = new float[samples];
    boolean[] valid = new boolean[samples];
    for (int i = 1; i < samples; i++) {
      Frame a = frames[i - 1], b = frames[i];
      if (a == null || b == null || a.luma.length != b.luma.length) continue;
      long diff = 0;
      for (int k = 0; k < b.luma.length; k++) diff += Math.abs(b.luma[k] - a.luma[k]);
      if (diff == 0) continue; // same key frame returned twice: no information
      valid[i] = true;
      mot[i] = diff / (255f * b.luma.length);
      float hd = 0;
      for (int k = 0; k < 24; k++) hd += Math.abs(b.hist[k] - a.hist[k]);
      cuts[i] = Math.min(1f, hd / 3f);
    }
    // fill gaps from neighbours
    float last = 0;
    for (int i = 0; i < samples; i++) {
      if (valid[i]) last = mot[i];
      else mot[i] = last;
    }
    for (int b = 0; b < motion.length; b++) {
      int i = (int) Math.min(samples - 1, Math.max(0, (b * BIN_MS) / stepMs));
      motion[b] = mot[i];
    }
    for (int i = 0; i < samples; i++) {
      int b = (int) Math.min(motion.length - 1, Math.max(0, (i * stepMs) / BIN_MS));
      cut[b] = Math.max(cut[b], cuts[i] > .45f ? cuts[i] : 0);
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
