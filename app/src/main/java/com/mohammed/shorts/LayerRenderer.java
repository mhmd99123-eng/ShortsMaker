package com.mohammed.shorts;

import android.graphics.*;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws layers with a plain Canvas. The same code paints the preview and the export, so what you
 * see is what you get. Sizes are relative to the frame width W.
 */
final class LayerRenderer {
  /** Gives the renderer the current media time. */
  interface Clock {
    long mediaMs(long presentationTimeUs);

    long clipStartMs();
  }

  private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final RectF r = new RectF();
  private final Path path = new Path();
  private final Map<String, Bitmap> images = new HashMap<>();
  private final Map<String, Object[]> videoFrames = new HashMap<>(); // id -> {index, bitmap}
  private final Map<String, Object[]> layouts = new HashMap<>(); // id -> {key, layout}
  private float[] bandBuf = new float[64];

  // ---------------------------------------------------------------- measuring

  private StaticLayout layout(Layer l, int W) {
    String key = l.text + "|" + l.font + "|" + l.scale + "|" + l.align + "|" + l.boxWidth + "|" + W;
    Object[] cached = layouts.get(l.id);
    if (cached != null && key.equals(cached[0])) return (StaticLayout) cached[1];
    tp.setTypeface(Fonts.get(l.font));
    tp.setTextSize(Math.max(4, l.scale * W));
    String text = l.text.isEmpty() ? " " : l.text;
    int width;
    if (l.boxWidth > 0) {
      width = Math.max(1, Math.round(l.boxWidth * W));
    } else {
      int maxW = (int) (W * .92f);
      float natural = 0;
      for (String line : text.split("\n")) natural = Math.max(natural, tp.measureText(line));
      width = (int) Math.max(1, Math.min(maxW, Math.ceil(natural) + 2));
    }
    Layout.Alignment a =
        l.align == 0 ? Layout.Alignment.ALIGN_CENTER : l.align == 1 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
    StaticLayout sl =
        StaticLayout.Builder.obtain(text, 0, text.length(), new TextPaint(tp), width)
            .setAlignment(a)
            .setIncludePad(false)
            .setLineSpacing(0, 1.05f)
            .build();
    layouts.put(l.id, new Object[] {key, sl});
    return sl;
  }

  static float textPadding(Layer l) {
    return l.scale * (l.bg != 0 ? .35f : .12f);
  }

  /** Unrotated size of a layer in pixels for a frame of width W: {w, h}. */
  float[] size(Layer l, int W) {
    if (l.type == Layer.TEXT) {
      StaticLayout sl = layout(l, W);
      float pad = textPadding(l) * W;
      return new float[] {sl.getWidth() + pad * 2, sl.getHeight() + pad * 2};
    }
    float w = l.scale * W;
    return new float[] {w, w * l.aspect};
  }

  /** True if (px, py) in frame pixels lands on the layer (rotation aware). */
  boolean hit(Layer l, int W, int H, float px, float py, float slop) {
    float[] s = size(l, W);
    double a = Math.toRadians(-l.rotation);
    float dx = px - l.x * W, dy = py - l.y * H;
    float lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
    float ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
    return Math.abs(lx) <= s[0] / 2 + slop && Math.abs(ly) <= s[1] / 2 + slop;
  }

  // ---------------------------------------------------------------- drawing

  void draw(Canvas c, int W, int H, List<Layer> layers, long mediaMs, long clipMs, AudioData audio) {
    for (Layer l : layers) {
      int save = c.save();
      c.translate(l.x * W, l.y * H);
      c.rotate(l.rotation);
      switch (l.type) {
        case Layer.TEXT:
          drawText(c, l, W);
          break;
        case Layer.IMAGE:
          drawBitmap(c, l, W, image(l));
          break;
        case Layer.VIDEO:
          drawBitmap(c, l, W, videoFrame(l, clipMs));
          break;
        case Layer.WAVE:
          drawWave(c, l, W, mediaMs, audio);
          break;
      }
      c.restoreToCount(save);
    }
  }

  private void drawText(Canvas c, Layer l, int W) {
    StaticLayout sl = layout(l, W);
    float th = l.scale * W;
    float w = sl.getWidth(), h = sl.getHeight();
    int alpha = Math.round(255 * l.opacity);
    if (l.bg != 0) {
      float pad = textPadding(l) * W;
      p.setShader(null);
      p.setStyle(Paint.Style.FILL);
      p.setColor(l.bgColor);
      p.setAlpha(Math.round((l.bg == 2 ? .55f : 1f) * alpha));
      r.set(-w / 2 - pad, -h / 2 - pad * .8f, w / 2 + pad, h / 2 + pad * .8f);
      c.drawRoundRect(r, th * .3f, th * .3f, p);
    }
    c.translate(-w / 2, -h / 2);
    TextPaint paint = sl.getPaint();
    paint.setTypeface(Fonts.get(l.font));
    paint.setTextSize(Math.max(4, th));
    if (l.stroke > 0) {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeJoin(Paint.Join.ROUND);
      paint.setStrokeWidth(l.stroke * th * 2);
      paint.setColor(l.strokeColor);
      paint.setAlpha(alpha);
      paint.clearShadowLayer();
      if (l.shadow) paint.setShadowLayer(th * .12f, 0, th * .05f, (alpha * 6 / 10) << 24);
      sl.draw(c);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(l.color);
    paint.setAlpha(alpha);
    if (l.shadow && l.stroke <= 0) paint.setShadowLayer(th * .12f, 0, th * .05f, (alpha * 6 / 10) << 24);
    else paint.clearShadowLayer();
    sl.draw(c);
  }

  private void drawBitmap(Canvas c, Layer l, int W, Bitmap b) {
    if (b == null) return;
    float w = l.scale * W, h = w * l.aspect;
    r.set(-w / 2, -h / 2, w / 2, h / 2);
    p.setShader(null);
    p.setColor(0xffffffff);
    p.setAlpha(Math.round(255 * l.opacity));
    if (l.radius > 0) {
      path.reset();
      path.addRoundRect(r, l.radius * w, l.radius * w, Path.Direction.CW);
      c.save();
      c.clipPath(path);
      c.drawBitmap(b, null, r, p);
      c.restore();
    } else {
      c.drawBitmap(b, null, r, p);
    }
  }

  private Bitmap image(Layer l) {
    Bitmap b = images.get(l.path);
    if (b == null && new File(l.path).exists()) {
      b = BitmapFactory.decodeFile(l.path);
      if (b != null) images.put(l.path, b);
    }
    return b;
  }

  private Bitmap videoFrame(Layer l, long clipMs) {
    if (l.frames <= 0) return null;
    int i = (int) ((Math.max(0, clipMs) * l.fps / 1000f) % l.frames);
    Object[] cur = videoFrames.get(l.id);
    if (cur != null && (int) cur[0] == i) return (Bitmap) cur[1];
    Bitmap b = BitmapFactory.decodeFile(new File(l.path, String.format(java.util.Locale.US, "f%05d.jpg", i)).getAbsolutePath());
    if (b == null) return cur == null ? null : (Bitmap) cur[1];
    if (cur != null && cur[1] != null) ((Bitmap) cur[1]).recycle();
    videoFrames.put(l.id, new Object[] {i, b});
    return b;
  }

  private void drawWave(Canvas c, Layer l, int W, long mediaMs, AudioData audio) {
    float w = l.scale * W, h = w * l.aspect;
    int n = Math.max(4, Math.min(64, l.bars));
    if (bandBuf.length < n) bandBuf = new float[n];
    float[] v = bandBuf;
    if (audio != null && audio.hasAudio) audio.bandsAt(mediaMs, v);
    else java.util.Arrays.fill(v, 0, n, 0f);
    if (l.waveStyle == 2 || l.waveStyle == 5) {
      // Low bands on the outside, high in the middle reads like a voice wave.
      float[] m = new float[n];
      for (int i = 0; i < n; i++) {
        int k = i < n / 2 ? i * 2 : (n - 1 - i) * 2 + 1;
        m[i] = v[Math.min(n - 1, k)];
      }
      System.arraycopy(m, 0, v, 0, n);
    }
    for (int i = 0; i < n; i++) v[i] = Math.min(1f, (float) Math.pow(v[i], 1.4) * l.sensitivity * 1.15f);
    int alpha = Math.round(255 * l.opacity);
    p.setStyle(Paint.Style.FILL);
    if (l.gradient) {
      p.setShader(new LinearGradient(-w / 2, 0, w / 2, 0, new int[] {Ui.ACCENT, 0xffff6ab0, Ui.ACCENT2}, null, Shader.TileMode.CLAMP));
      p.setColor(0xffffffff);
    } else {
      p.setShader(null);
      p.setColor(l.color);
    }
    p.setAlpha(alpha);
    float minBar = h * .04f;
    switch (l.waveStyle) {
      case 0: { // bars from the bottom
        float gap = w / n * .32f, bw = w / n - gap;
        for (int i = 0; i < n; i++) {
          float bh = Math.max(minBar, v[i] * h);
          float x0 = -w / 2 + i * (bw + gap) + gap / 2;
          r.set(x0, h / 2 - bh, x0 + bw, h / 2);
          c.drawRoundRect(r, bw / 2, bw / 2, p);
        }
        break;
      }
      case 1: { // mirrored bars
        float gap = w / n * .35f, bw = w / n - gap;
        for (int i = 0; i < n; i++) {
          float bh = Math.max(minBar, v[i] * h);
          float x0 = -w / 2 + i * (bw + gap) + gap / 2;
          r.set(x0, -bh / 2, x0 + bw, bh / 2);
          c.drawRoundRect(r, bw / 2, bw / 2, p);
        }
        break;
      }
      case 2: { // smooth line wave
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, h * .045f));
        p.setStrokeCap(Paint.Cap.ROUND);
        for (int pass = 0; pass < 2; pass++) {
          path.reset();
          float sign = pass == 0 ? 1 : -1;
          for (int i = 0; i < n; i++) {
            float x = -w / 2 + w * i / (n - 1f);
            float y = sign * v[i] * h / 2 * (float) Math.sin(Math.PI * i / (n - 1f) * 1f);
            if (i == 0) path.moveTo(x, y);
            else {
              float px = -w / 2 + w * (i - 1) / (n - 1f);
              path.quadTo(px, y, (px + x) / 2, y);
              path.lineTo(x, y);
            }
          }
          p.setAlpha(pass == 0 ? alpha : alpha / 2);
          c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        break;
      }
      case 3: { // circular
        float rad = Math.min(w, h) * .28f;
        float maxLen = Math.min(w, h) / 2 - rad;
        float bw = (float) (2 * Math.PI * rad / n) * .55f;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(bw);
        for (int i = 0; i < n; i++) {
          double a = 2 * Math.PI * i / n - Math.PI / 2;
          float len = Math.max(bw, v[i] * maxLen);
          float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
          c.drawLine(cx * rad, cy * rad, cx * (rad + len), cy * (rad + len), p);
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, bw * .5f));
        float level = audio != null ? audio.level(mediaMs) : 0;
        c.drawCircle(0, 0, rad * (.82f + .1f * level), p);
        p.setStyle(Paint.Style.FILL);
        break;
      }
      case 4: { // dots
        int rows = 8;
        float cw = w / n, ch = h / rows, dot = Math.min(cw, ch) * .36f;
        for (int i = 0; i < n; i++) {
          int lit = Math.max(1, Math.round(v[i] * rows));
          for (int j = 0; j < lit; j++) {
            float x = -w / 2 + cw * (i + .5f), y = h / 2 - ch * (j + .5f);
            c.drawCircle(x, y, dot, p);
          }
        }
        break;
      }
      default: { // filled spectrum
        path.reset();
        path.moveTo(-w / 2, 0);
        for (int i = 0; i < n; i++) {
          float x = -w / 2 + w * i / (n - 1f);
          path.lineTo(x, -Math.max(minBar / 2, v[i] * h / 2));
        }
        for (int i = n - 1; i >= 0; i--) {
          float x = -w / 2 + w * i / (n - 1f);
          path.lineTo(x, Math.max(minBar / 2, v[i] * h / 2));
        }
        path.close();
        c.drawPath(path, p);
        break;
      }
    }
    p.setShader(null);
  }

  void release() {
    for (Bitmap b : images.values()) b.recycle();
    images.clear();
    for (Object[] o : videoFrames.values()) if (o[1] != null) ((Bitmap) o[1]).recycle();
    videoFrames.clear();
    layouts.clear();
  }
}
