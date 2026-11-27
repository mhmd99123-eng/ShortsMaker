package com.mohammed.shorts;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The video's sound, decoded once and kept small: loudness and a 24-band spectrum, 30 times a
 * second. Used by the smart search (loudness) and the audio-wave layers (spectrum). Cached on disk
 * so reopening the same video is instant.
 */
final class AudioData {
  static final int FPS = 30, BANDS = 24;
  private static final int VERSION = 2;

  int frames;
  boolean hasAudio;
  /** Linear RMS per frame. */
  float[] rms = new float[0];
  /** frames × BANDS, normalised 0…1. */
  float[] bands = new float[0];
  /** RMS normalised 0…1 for the visuals. */
  private float rmsScale = 1;

  static AudioData load(Context c, Uri uri, long durationMs, Highlights.Listener l) throws Exception {
    File cache = new File(c.getCacheDir(), "audio-" + Integer.toHexString((uri.toString() + "|" + durationMs).hashCode()) + ".bin");
    AudioData cached = read(cache);
    if (cached != null) {
      l.progress(1f);
      return cached;
    }
    AudioData a = decode(c, uri, durationMs, l);
    if (!l.cancelled()) write(a, cache);
    return a;
  }

  float level(long ms) {
    if (frames == 0) return 0;
    int f = frame(ms);
    float v = 0;
    for (int k = 0; k < 3; k++) v = Math.max(v, rms[Math.max(0, f - k)] * (1f - .22f * k));
    return Math.min(1f, v * rmsScale);
  }

  /** Spectrum at a time, with quick rise and smooth fall so bars dance instead of flicker. */
  void bandsAt(long ms, float[] out) {
    if (frames == 0) {
      java.util.Arrays.fill(out, 0f);
      return;
    }
    int f = frame(ms);
    int n = out.length;
    for (int i = 0; i < n; i++) {
      // map requested bar to the 24 analysed bands
      float pos = i * (BANDS - 1f) / Math.max(1, n - 1);
      int b0 = (int) pos, b1 = Math.min(BANDS - 1, b0 + 1);
      float t = pos - b0, v = 0;
      for (int k = 0; k < 4; k++) {
        int ff = Math.max(0, f - k);
        float x = bands[ff * BANDS + b0] * (1 - t) + bands[ff * BANDS + b1] * t;
        v = Math.max(v, x * (1f - .2f * k));
      }
      out[i] = v;
    }
  }

  /** Mean loudness in dBFS per bin, for the smart search. */
  float[] loudDb(long binMs, int bins) {
    float[] out = new float[bins];
    double[] sum = new double[bins];
    int[] count = new int[bins];
    for (int f = 0; f < frames; f++) {
      int b = (int) (f * 1000L / FPS / binMs);
      if (b >= bins) break;
      sum[b] += (double) rms[f] * rms[f];
      count[b]++;
    }
    for (int b = 0; b < bins; b++)
      out[b] = count[b] == 0 ? -90f : (float) Math.max(-90, 10 * Math.log10(sum[b] / count[b] + 1e-12));
    return out;
  }

  private int frame(long ms) {
    return (int) Math.max(0, Math.min(frames - 1, ms * FPS / 1000));
  }

  // ---------------------------------------------------------------- decoding

  private static AudioData decode(Context c, Uri uri, long durationMs, Highlights.Listener l) throws Exception {
    AudioData a = new AudioData();
    a.frames = (int) Math.max(1, durationMs * FPS / 1000 + 1);
    a.rms = new float[a.frames];
    a.bands = new float[a.frames * BANDS];
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
      if (track < 0) {
        l.progress(1f);
        return a;
      }
      ex.selectTrack(track);
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
      codec.configure(format, null, null, 0);
      codec.start();
      int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
      int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
      int encoding = 2;
      Spectrum spec = new Spectrum(rate);
      MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
      boolean inDone = false, outDone = false;
      long firstPts = -1;
      long written = 0; // mono samples pushed so far
      long durationUs = durationMs * 1000;
      while (!outDone) {
        if (l.cancelled()) throw new InterruptedException();
        if (!inDone) {
          int in = codec.dequeueInputBuffer(2000);
          if (in >= 0) {
            ByteBuffer buf = codec.getInputBuffer(in);
            int size = ex.readSampleData(buf, 0);
            if (size < 0) {
              codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
              inDone = true;
            } else {
              codec.queueInputBuffer(in, 0, size, ex.getSampleTime(), 0);
              ex.advance();
            }
          }
        }
        int out = codec.dequeueOutputBuffer(info, 2000);
        if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          MediaFormat f = codec.getOutputFormat();
          rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
          if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING);
          spec = new Spectrum(rate);
        } else if (out >= 0) {
          if (info.size > 0) {
            ByteBuffer buf = codec.getOutputBuffer(out);
            buf.position(info.offset);
            buf.limit(info.offset + info.size);
            buf.order(ByteOrder.LITTLE_ENDIAN);
            if (firstPts < 0) firstPts = info.presentationTimeUs;
            // Fill silence if the stream skipped ahead so frames stay aligned with the video.
            long expected = (info.presentationTimeUs - firstPts) * rate / 1_000_000L;
            while (written < expected - rate / 10) {
              spec.push(0f, a);
              written++;
            }
            boolean isFloat = encoding == 4;
            int frameCount = info.size / ((isFloat ? 4 : 2) * channels);
            if (isFloat) {
              java.nio.FloatBuffer fb = buf.asFloatBuffer();
              for (int i = 0; i < frameCount; i++) {
                float m = 0;
                for (int ch = 0; ch < channels; ch++) m += fb.get();
                spec.push(m / channels, a);
              }
            } else {
              java.nio.ShortBuffer sb = buf.asShortBuffer();
              for (int i = 0; i < frameCount; i++) {
                int m = 0;
                for (int ch = 0; ch < channels; ch++) m += sb.get();
                spec.push(m / (32768f * channels), a);
              }
            }
            written += frameCount;
            if (durationUs > 0) l.progress(Math.min(1f, info.presentationTimeUs / (float) durationUs));
          }
          codec.releaseOutputBuffer(out, false);
          if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
        }
      }
      a.hasAudio = true;
      a.finish();
      l.progress(1f);
      return a;
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

  /** Normalise the spectrum per band so quiet and loud videos both move nicely. */
  private void finish() {
    float[] tmp = new float[frames];
    for (int b = 0; b < BANDS; b++) {
      for (int f = 0; f < frames; f++) tmp[f] = bands[f * BANDS + b];
      float lo = Highlights.percentile(tmp, 20), hi = Highlights.percentile(tmp, 99);
      float span = Math.max(1e-3f, hi - lo);
      for (int f = 0; f < frames; f++) {
        float v = (bands[f * BANDS + b] - lo) / span;
        bands[f * BANDS + b] = Math.max(0f, Math.min(1f, v));
      }
    }
    float top = Highlights.percentile(rms, 98);
    rmsScale = top > 1e-5f ? 1f / top : 1f;
  }

  /** Streaming FFT: one analysis every 1/30 s over the last N samples. */
  private static final class Spectrum {
    final int n, hop;
    final float[] ring, window, re, im;
    final int[] bandLo, bandHi;
    int pos, sinceHop, frame;
    double sumSq;
    int sumCount;

    Spectrum(int rate) {
      n = rate > 32000 ? 2048 : 1024;
      hop = Math.max(1, rate / FPS);
      ring = new float[n];
      window = new float[n];
      re = new float[n];
      im = new float[n];
      for (int i = 0; i < n; i++) window[i] = (float) (.5 - .5 * Math.cos(2 * Math.PI * i / (n - 1)));
      bandLo = new int[BANDS];
      bandHi = new int[BANDS];
      double fLo = 45, fHi = Math.min(14000, rate / 2.0 - 100);
      for (int b = 0; b < BANDS; b++) {
        double a = fLo * Math.pow(fHi / fLo, b / (double) BANDS);
        double z = fLo * Math.pow(fHi / fLo, (b + 1) / (double) BANDS);
        bandLo[b] = Math.max(1, (int) Math.floor(a * n / rate));
        bandHi[b] = Math.max(bandLo[b] + 1, (int) Math.ceil(z * n / rate));
      }
    }

    void push(float s, AudioData a) {
      ring[pos] = s;
      pos = (pos + 1) % n;
      sumSq += s * s;
      sumCount++;
      if (++sinceHop < hop) return;
      sinceHop = 0;
      if (frame >= a.frames) return;
      a.rms[frame] = (float) Math.sqrt(sumSq / Math.max(1, sumCount));
      sumSq = 0;
      sumCount = 0;
      for (int i = 0; i < n; i++) {
        re[i] = ring[(pos + i) % n] * window[i];
        im[i] = 0;
      }
      fft(re, im);
      for (int b = 0; b < BANDS; b++) {
        float m = 0;
        for (int k = bandLo[b]; k < bandHi[b] && k < n / 2; k++) m = Math.max(m, re[k] * re[k] + im[k] * im[k]);
        a.bands[frame * BANDS + b] = (float) (10 * Math.log10(m + 1e-10));
      }
      frame++;
    }

    static void fft(float[] re, float[] im) {
      int n = re.length;
      for (int i = 1, j = 0; i < n; i++) {
        int bit = n >> 1;
        for (; (j & bit) != 0; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
          float t = re[i]; re[i] = re[j]; re[j] = t;
          t = im[i]; im[i] = im[j]; im[j] = t;
        }
      }
      for (int len = 2; len <= n; len <<= 1) {
        double ang = -2 * Math.PI / len;
        float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
        for (int i = 0; i < n; i += len) {
          float cr = 1, ci = 0;
          for (int j = 0; j < len / 2; j++) {
            int u = i + j, v = i + j + len / 2;
            float vr = re[v] * cr - im[v] * ci, vi = re[v] * ci + im[v] * cr;
            re[v] = re[u] - vr; im[v] = im[u] - vi;
            re[u] += vr; im[u] += vi;
            float nr = cr * wr - ci * wi;
            ci = cr * wi + ci * wr;
            cr = nr;
          }
        }
      }
    }
  }

  // ---------------------------------------------------------------- cache

  private static void write(AudioData a, File f) {
    try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
      o.writeInt(VERSION);
      o.writeInt(a.frames);
      o.writeBoolean(a.hasAudio);
      o.writeFloat(a.rmsScale);
      for (float v : a.rms) o.writeFloat(v);
      for (float v : a.bands) o.writeByte(Math.round(v * 255) & 255);
    } catch (IOException ignored) {
      f.delete();
    }
  }

  private static AudioData read(File f) {
    if (!f.exists()) return null;
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
      if (in.readInt() != VERSION) return null;
      AudioData a = new AudioData();
      a.frames = in.readInt();
      a.hasAudio = in.readBoolean();
      a.rmsScale = in.readFloat();
      a.rms = new float[a.frames];
      a.bands = new float[a.frames * BANDS];
      for (int i = 0; i < a.frames; i++) a.rms[i] = in.readFloat();
      for (int i = 0; i < a.bands.length; i++) a.bands[i] = (in.readByte() & 255) / 255f;
      return a;
    } catch (Exception e) {
      return null;
    }
  }
}
