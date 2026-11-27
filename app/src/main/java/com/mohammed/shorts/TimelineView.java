package com.mohammed.shorts;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Thumbnail strip with a draggable selection. Drag a handle to change the length, drag inside to
 * move the whole part, tap/drag outside to scrub, pinch to zoom for frame-precise trimming.
 * Time always flows left to right, also in the Arabic layout.
 */
final class TimelineView extends View {
  interface Listener {
    void selectionChanged(long startMs, long endMs, boolean finished);

    void seek(long ms, boolean finished);

    void handleSelected(int handle);
  }

  static final long MIN_MS = 1000, MAX_MS = 180_000;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF tmp = new RectF();
  private final Path clip = new Path();
  private final ScaleGestureDetector scale;
  private final float d;

  private Listener listener;
  private long duration = 1, start = 0, end = 1, playhead = 0;
  private long viewStart = 0, viewEnd = 1;
  private Bitmap[] thumbs = new Bitmap[0];
  private float[] heat;
  private List<Clip> suggestions = new ArrayList<>(), queued = new ArrayList<>();
  private int activeHandle = -1; // 0 start, 1 end
  private int dragMode = 0; // 1 start, 2 end, 3 move, 4 scrub, 5 pan
  private float downX, lastX;
  private long downStart, downEnd, downViewStart;
  private boolean scaling;

  TimelineView(Context c) {
    super(c);
    d = c.getResources().getDisplayMetrics().density;
    setLayoutDirection(LAYOUT_DIRECTION_LTR);
    frame.setStyle(Paint.Style.STROKE);
    frame.setStrokeWidth(3 * d);
    scale =
        new ScaleGestureDetector(
            c,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScaleBegin(ScaleGestureDetector g) {
                scaling = true;
                return true;
              }

              public boolean onScale(ScaleGestureDetector g) {
                long span = viewEnd - viewStart;
                long next = (long) (span / g.getScaleFactor());
                next = Math.max(Math.min(duration, 4000), Math.min(duration, next));
                long focus = timeAt(g.getFocusX());
                float f = (g.getFocusX() - pad()) / Math.max(1f, getWidth() - 2 * pad());
                setView(focus - (long) (next * f), next);
                return true;
              }
            });
  }

  void setListener(Listener l) {
    listener = l;
  }

  void setDuration(long ms) {
    duration = Math.max(1, ms);
    viewStart = 0;
    viewEnd = duration;
    invalidate();
  }

  void setThumbs(Bitmap[] b) {
    thumbs = b;
    invalidate();
  }

  void setHeat(float[] h) {
    heat = h;
    invalidate();
  }

  void setSuggestions(List<Clip> s) {
    suggestions = new ArrayList<>(s);
    invalidate();
  }

  void setQueued(List<Clip> q) {
    queued = new ArrayList<>(q);
    invalidate();
  }

  void setSelection(long s, long e) {
    start = Math.max(0, s);
    end = Math.min(duration, Math.max(start + 1, e));
    ensureVisible();
    invalidate();
  }

  void setPlayhead(long ms) {
    playhead = ms;
    invalidate();
  }

  void setActiveHandle(int h) {
    activeHandle = h;
    invalidate();
  }

  int activeHandle() {
    return activeHandle;
  }

  long start() {
    return start;
  }

  long end() {
    return end;
  }

  /** Zoom so the selection fills ~70% of the strip (used after picking a suggestion). */
  void focusSelection() {
    long len = end - start;
    long span = Math.min(duration, Math.max(len * 10 / 7, 4000));
    setView(start - (span - len) / 2, span);
  }

  void zoomOut() {
    setView(0, duration);
  }

  private void ensureVisible() {
    if (start < viewStart || end > viewEnd) {
      long span = viewEnd - viewStart;
      if (end - start > span) span = Math.min(duration, (end - start) * 10 / 7);
      setView(start - (span - (end - start)) / 2, span);
    }
  }

  private void setView(long s, long span) {
    span = Math.max(1, Math.min(duration, span));
    s = Math.max(0, Math.min(duration - span, s));
    viewStart = s;
    viewEnd = s + span;
    invalidate();
  }

  private float pad() {
    return 18 * d;
  }

  private float x(long ms) {
    return pad() + (ms - viewStart) / (float) (viewEnd - viewStart) * (getWidth() - 2 * pad());
  }

  private long timeAt(float px) {
    float f = (px - pad()) / Math.max(1f, getWidth() - 2 * pad());
    return Math.max(0, Math.min(duration, viewStart + (long) (f * (viewEnd - viewStart))));
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(MeasureSpec.getSize(w), (int) (104 * d));
  }

  @Override
  protected void onDraw(Canvas c) {
    float w = getWidth();
    float top = 10 * d, bottom = 70 * d, r = 12 * d;
    float left = pad(), right = w - pad();

    // Strip background + thumbnails, clipped to a rounded rectangle.
    paint.setShader(null);
    paint.setColor(Ui.CARD);
    tmp.set(left, top, right, bottom);
    c.drawRoundRect(tmp, r, r, paint);
    c.save();
    clip.reset();
    clip.addRoundRect(tmp, r, r, Path.Direction.CW);
    c.clipPath(clip);
    if (thumbs.length > 0) {
      float seg = duration / (float) thumbs.length;
      for (int i = 0; i < thumbs.length; i++) {
        Bitmap b = thumbs[i];
        float x0 = x((long) (i * seg)), x1 = x((long) ((i + 1) * seg));
        if (x1 < left || x0 > right) continue;
        if (b == null || b.isRecycled()) continue;
        // centre-crop each thumbnail into its cell
        float cellW = x1 - x0, cellH = bottom - top;
        float bs = Math.max(cellW / b.getWidth(), cellH / b.getHeight());
        float bw = b.getWidth() * bs, bh = b.getHeight() * bs;
        c.save();
        c.clipRect(x0, top, x1 + 1, bottom);
        tmp.set(x0 + (cellW - bw) / 2, top + (cellH - bh) / 2, x0 + (cellW + bw) / 2, top + (cellH + bh) / 2);
        c.drawBitmap(b, null, tmp, paint);
        c.restore();
      }
    }
    // Dim what is outside the selection.
    paint.setColor(0xb8000000);
    float sx = x(start), ex = x(end);
    c.drawRect(left, top, sx, bottom, paint);
    c.drawRect(ex, top, right, bottom, paint);
    c.restore();

    // Selection frame with gradient.
    frame.setShader(new LinearGradient(sx, 0, Math.max(ex, sx + 1), 0, Ui.ACCENT, Ui.ACCENT2, Shader.TileMode.CLAMP));
    tmp.set(sx, top + 1.5f * d, ex, bottom - 1.5f * d);
    c.drawRoundRect(tmp, 6 * d, 6 * d, frame);
    drawHandle(c, sx, top, bottom, true, activeHandle == 0);
    drawHandle(c, ex, top, bottom, false, activeHandle == 1);

    // Interest curve under the strip.
    float hy0 = bottom + 9 * d, hy1 = hy0 + 8 * d;
    if (heat != null && heat.length > 0) {
      for (float px = left; px < right; px += 2 * d) {
        long t = timeAt(px);
        int i = (int) Math.min(heat.length - 1, t / Highlights.BIN_MS);
        float v = heat[i];
        paint.setColor(blend(Ui.ACCENT, Ui.ACCENT2, v));
        paint.setAlpha((int) (40 + 215 * v));
        float hh = (hy1 - hy0) * (.25f + .75f * v);
        c.drawRect(px, hy1 - hh, px + 1.4f * d, hy1, paint);
      }
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.4f * d);
    paint.setColor(0xccffffff);
    for (Clip s : suggestions) {
      float a = x(s.startMs), b = x(s.endMs);
      if (b < left || a > right) continue;
      tmp.set(Math.max(left, a), hy0 - 2 * d, Math.min(right, b), hy1 + 2 * d);
      c.drawRoundRect(tmp, 4 * d, 4 * d, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    // Parts already added to the export list.
    float qy = hy1 + 7 * d;
    paint.setColor(Ui.ACCENT2);
    for (Clip q : queued) {
      float a = Math.max(left, x(q.startMs)), b = Math.min(right, x(q.endMs));
      if (b <= a) continue;
      tmp.set(a, qy, b, qy + 4 * d);
      c.drawRoundRect(tmp, 2 * d, 2 * d, paint);
    }

    // Playhead.
    float px = x(playhead);
    if (px >= left - 1 && px <= right + 1) {
      paint.setColor(0xffffffff);
      c.drawRect(px - d, top - 6 * d, px + d, bottom + 4 * d, paint);
      c.drawCircle(px, top - 6 * d, 4 * d, paint);
    }
  }

  private void drawHandle(Canvas c, float x, float top, float bottom, boolean isStart, boolean active) {
    float hw = 11 * d;
    float x0 = isStart ? x - hw : x, x1 = isStart ? x : x + hw;
    paint.setShader(null);
    paint.setColor(active ? Ui.ACCENT2 : 0xffffffff);
    tmp.set(x0, top, x1, bottom);
    c.drawRoundRect(tmp, 5 * d, 5 * d, paint);
    paint.setColor(0xff1a1726);
    float cx = (x0 + x1) / 2, cy = (top + bottom) / 2;
    c.drawRoundRect(cx - 1.1f * d, cy - 8 * d, cx + 1.1f * d, cy + 8 * d, d, d, paint);
  }

  private static int blend(int a, int b, float t) {
    t = Math.max(0, Math.min(1, t));
    int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
    int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
    return 0xff000000
        | ((int) (ar + (br - ar) * t) << 16)
        | ((int) (ag + (bg - ag) * t) << 8)
        | (int) (ab + (bb - ab) * t);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    scale.onTouchEvent(e);
    if (e.getPointerCount() > 1 || scaling) {
      if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
        scaling = false;
      dragMode = 0;
      return true;
    }
    float ex = e.getX();
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        getParent().requestDisallowInterceptTouchEvent(true);
        downX = lastX = ex;
        downStart = start;
        downEnd = end;
        downViewStart = viewStart;
        float sx = x(start), exx = x(end), grab = 26 * d;
        float ds = Math.abs(ex - (sx - 5 * d)), de = Math.abs(ex - (exx + 5 * d));
        if (ds < grab && ds <= de) dragMode = 1;
        else if (de < grab) dragMode = 2;
        else if (ex > sx && ex < exx) dragMode = 3;
        else dragMode = 4;
        if (dragMode == 1 || dragMode == 2) {
          activeHandle = dragMode - 1;
          if (listener != null) listener.handleSelected(activeHandle);
          performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
        }
        if (dragMode == 4 && listener != null) listener.seek(timeAt(ex), false);
        invalidate();
        return true;
      case MotionEvent.ACTION_MOVE:
        long dt = (long) ((ex - downX) / Math.max(1f, getWidth() - 2 * pad()) * (viewEnd - viewStart));
        if (dragMode == 1) {
          start = Math.max(0, Math.min(downStart + dt, end - MIN_MS));
          start = Math.max(start, end - MAX_MS);
          notifySel(false);
          if (listener != null) listener.seek(start, false);
        } else if (dragMode == 2) {
          end = Math.min(duration, Math.max(downEnd + dt, start + MIN_MS));
          end = Math.min(end, start + MAX_MS);
          notifySel(false);
          if (listener != null) listener.seek(end, false);
        } else if (dragMode == 3) {
          long len = downEnd - downStart;
          start = Math.max(0, Math.min(duration - len, downStart + dt));
          end = start + len;
          notifySel(false);
          if (listener != null) listener.seek(start, false);
        } else if (dragMode == 4) {
          if (listener != null) listener.seek(timeAt(ex), false);
        }
        // Auto-scroll when dragging near the edges while zoomed in.
        if (dragMode >= 1 && dragMode <= 3 && viewEnd - viewStart < duration) {
          long span = viewEnd - viewStart;
          if (ex < pad() * 1.5f) setView(viewStart - span / 60, span);
          else if (ex > getWidth() - pad() * 1.5f) setView(viewStart + span / 60, span);
        }
        lastX = ex;
        invalidate();
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (dragMode >= 1 && dragMode <= 3) notifySel(true);
        if (dragMode == 4 && listener != null) listener.seek(timeAt(ex), true);
        if (dragMode == 1 && listener != null) listener.seek(start, true);
        if (dragMode == 2 && listener != null) listener.seek(end, true);
        if (dragMode == 3 && listener != null) listener.seek(start, true);
        dragMode = 0;
        return true;
    }
    return super.onTouchEvent(e);
  }

  private void notifySel(boolean finished) {
    if (listener != null) listener.selectionChanged(start, end, finished);
  }
}
