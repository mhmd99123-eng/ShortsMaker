package com.mohammed.shorts;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Look of the vertical frame. Fields are volatile because the preview's GL thread reads them on
 * every frame while the UI thread edits them, which is what makes the sliders feel live.
 */
final class ShortStyle {
  static final int BG_BLUR = 0, BG_COLOR = 1;

  volatile int outW = 1080, outH = 1920;
  /** 0 = light blur, 1 = very strong blur. */
  volatile float blur = .55f;
  /** 0 = original brightness, 1 = black. */
  volatile float dim = .28f;
  /** 1 = background colours as they are; >1 a bit richer. */
  volatile float saturation = 1.15f;
  /** Width of the sharp video relative to the frame width (1 = edge to edge). */
  volatile float zoom = 1f;
  /** -1 bottom … 0 centre … 1 top. */
  volatile float offsetY = 0f;
  /** -1 left … 1 right (only matters when zoom > 1). */
  volatile float offsetX = 0f;
  /** Corner radius as a fraction of the frame width. */
  volatile float radius = 0f;
  /** Soft shadow under the sharp video, 0…1. */
  volatile float shadow = .55f;
  volatile int bgMode = BG_BLUR;
  volatile int bgColor = 0xff101018;
  /** Channel name drawn on top of everything. Position is the centre, 0…1 from the top-left. */
  volatile boolean markOn = true;
  volatile String markText = "Mohammed";
  /** Text height as a fraction of the frame width. */
  volatile float markSize = .042f;
  volatile float markX = .2f, markY = .075f;
  volatile float markOpacity = .9f;

  /** Layers on top (immutable list; replaced as a whole when something changes). */
  volatile java.util.List<Layer> layers = java.util.Collections.emptyList();
  /** Bumped on every layer edit so the renderer knows to redraw. */
  volatile int layersVersion = 0;
  volatile AudioData audio;
  volatile LayerRenderer.Clock clock;

  ShortStyle copy() {
    ShortStyle s = new ShortStyle();
    s.outW = outW;
    s.outH = outH;
    s.blur = blur;
    s.dim = dim;
    s.saturation = saturation;
    s.zoom = zoom;
    s.offsetY = offsetY;
    s.offsetX = offsetX;
    s.radius = radius;
    s.shadow = shadow;
    s.bgMode = bgMode;
    s.bgColor = bgColor;
    s.markOn = markOn;
    s.markText = markText;
    s.markSize = markSize;
    s.markX = markX;
    s.markY = markY;
    s.markOpacity = markOpacity;
    s.layers = layers;
    s.layersVersion = layersVersion;
    s.audio = audio;
    s.clock = clock;
    return s;
  }

  void resetLook() {
    ShortStyle d = new ShortStyle();
    blur = d.blur;
    dim = d.dim;
    saturation = d.saturation;
    zoom = d.zoom;
    offsetY = d.offsetY;
    offsetX = d.offsetX;
    radius = d.radius;
    shadow = d.shadow;
    bgMode = d.bgMode;
    bgColor = d.bgColor;
  }

  private static SharedPreferences prefs(Context c) {
    return c.getSharedPreferences("style", 0);
  }

  static ShortStyle load(Context c) {
    SharedPreferences p = prefs(c);
    ShortStyle s = new ShortStyle();
    s.blur = p.getFloat("blur", s.blur);
    s.dim = p.getFloat("dim", s.dim);
    s.saturation = p.getFloat("saturation", s.saturation);
    s.zoom = p.getFloat("zoom", s.zoom);
    s.offsetY = p.getFloat("offsetY", s.offsetY);
    s.offsetX = p.getFloat("offsetX", s.offsetX);
    s.radius = p.getFloat("radius", s.radius);
    s.shadow = p.getFloat("shadow", s.shadow);
    s.bgMode = p.getInt("bgMode", s.bgMode);
    s.bgColor = p.getInt("bgColor", s.bgColor);
    s.markOn = p.getBoolean("markOn", s.markOn);
    s.markText = p.getString("markText", s.markText);
    s.markSize = p.getFloat("markSize", s.markSize);
    s.markX = p.getFloat("markX", s.markX);
    s.markY = p.getFloat("markY", s.markY);
    s.markOpacity = p.getFloat("markOpacity", s.markOpacity);
    return s;
  }

  void save(Context c) {
    prefs(c)
        .edit()
        .putFloat("blur", blur)
        .putFloat("dim", dim)
        .putFloat("saturation", saturation)
        .putFloat("zoom", zoom)
        .putFloat("offsetY", offsetY)
        .putFloat("offsetX", offsetX)
        .putFloat("radius", radius)
        .putFloat("shadow", shadow)
        .putInt("bgMode", bgMode)
        .putInt("bgColor", bgColor)
        .putBoolean("markOn", markOn)
        .putString("markText", markText)
        .putFloat("markSize", markSize)
        .putFloat("markX", markX)
        .putFloat("markY", markY)
        .putFloat("markOpacity", markOpacity)
        .apply();
  }

  /** The channel name rendered as an image; its pixel size is the name's size on the frame. */
  static android.graphics.Bitmap markBitmap(String text, float heightPx) {
    android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    p.setTypeface(android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL));
    p.setTextSize(Math.max(8, heightPx));
    p.setColor(0xffffffff);
    p.setShadowLayer(Math.max(2, heightPx * .12f), 0, heightPx * .04f, 0x99000000);
    String t = text == null || text.trim().isEmpty() ? " " : text.trim();
    android.graphics.Paint.FontMetrics fm = p.getFontMetrics();
    int pad = (int) Math.ceil(heightPx * .3f);
    int w = (int) Math.ceil(p.measureText(t)) + pad * 2;
    int h = (int) Math.ceil(fm.descent - fm.ascent) + pad * 2;
    android.graphics.Bitmap b =
        android.graphics.Bitmap.createBitmap(Math.max(2, w), Math.max(2, h), android.graphics.Bitmap.Config.ARGB_8888);
    new android.graphics.Canvas(b).drawText(t, pad, pad - fm.ascent, p);
    return b;
  }

  /**
   * Where the sharp video sits in the output, in GL pixels (origin bottom-left): {x, y, w, h}.
   * Pure maths so it can be unit tested.
   */
  static float[] foregroundRect(
      int outW, int outH, int inW, int inH, float zoom, float offsetX, float offsetY) {
    float fit = Math.min(outW / (float) inW, outH / (float) inH);
    float w = inW * fit * zoom, h = inH * fit * zoom;
    float cx = outW / 2f + offsetX * Math.abs(outW - w) / 2f;
    float cy = outH / 2f + offsetY * Math.abs(outH - h) / 2f;
    return new float[] {cx - w / 2f, cy - h / 2f, w, h};
  }
}
