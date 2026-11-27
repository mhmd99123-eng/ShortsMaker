package com.mohammed.shorts;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.*;
import java.util.Locale;

/** Small view toolkit so every screen shares one modern, dark look. */
final class Ui {
  static final int BG = 0xff000000,
      SURFACE = 0xff0c0b12,
      CARD = 0xff16141e,
      STROKE = 0xff2a2636,
      TEXT = 0xfff4f2fa,
      MUTED = 0xff8f8aa3,
      ACCENT = 0xff8b6cff,
      ACCENT2 = 0xff22d3ee,
      DANGER = 0xffff5c7a;

  static final PathInterpolator EASE = new PathInterpolator(.2f, 0f, 0f, 1f);

  static int dp(Context c, float n) {
    return (int) (n * c.getResources().getDisplayMetrics().density + .5f);
  }

  static GradientDrawable shape(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(radius);
    return g;
  }

  static GradientDrawable outlined(int color, int stroke, int radius, int strokeWidth) {
    GradientDrawable g = shape(color, radius);
    g.setStroke(strokeWidth, stroke);
    return g;
  }

  static GradientDrawable gradient(int radius) {
    GradientDrawable g =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {ACCENT, ACCENT2});
    g.setCornerRadius(radius);
    return g;
  }

  static TextView text(Context a, String s, float size, int color) {
    TextView v = new TextView(a);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    return v;
  }

  static TextView title(Context a, String s, float size) {
    TextView v = text(a, s, size, TEXT);
    v.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
    return v;
  }

  static void bold(TextView t) {
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
  }

  static void pressable(View v) {
    v.setOnTouchListener(
        (view, e) -> {
          if (e.getAction() == MotionEvent.ACTION_DOWN)
            view.animate().scaleX(.95f).scaleY(.95f).setDuration(90).start();
          else if (e.getAction() == MotionEvent.ACTION_UP
              || e.getAction() == MotionEvent.ACTION_CANCEL)
            view.animate().scaleX(1).scaleY(1).setDuration(220).setInterpolator(EASE).start();
          return false;
        });
  }

  /** Big gradient pill. */
  static TextView primary(Activity a, String label, String icon, Runnable action) {
    TextView v = text(a, label, 16, 0xffffffff);
    bold(v);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(a, 54));
    v.setPadding(dp(a, 20), 0, dp(a, 20), 0);
    v.setBackground(gradient(dp(a, 27)));
    v.setElevation(dp(a, 6));
    if (icon != null) icon(v, icon, 0xffffffff, 22);
    v.setOnClickListener(x -> action.run());
    pressable(v);
    return v;
  }

  /** Quiet rounded button. */
  static TextView ghost(Activity a, String label, String icon, Runnable action) {
    TextView v = text(a, label, 14, TEXT);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(a, 44));
    v.setPadding(dp(a, 14), 0, dp(a, 14), 0);
    v.setBackground(outlined(CARD, STROKE, dp(a, 22), dp(a, 1)));
    if (icon != null) icon(v, icon, ACCENT2, 18);
    v.setOnClickListener(x -> action.run());
    pressable(v);
    return v;
  }

  static ImageView iconButton(Activity a, String icon, String description, Runnable action) {
    ImageView v = new ImageView(a);
    v.setImageDrawable(new Glyph(icon, TEXT));
    v.setPadding(dp(a, 11), dp(a, 11), dp(a, 11), dp(a, 11));
    v.setBackground(shape(CARD, dp(a, 22)));
    v.setContentDescription(description);
    v.setOnClickListener(x -> action.run());
    pressable(v);
    return v;
  }

  static void icon(TextView v, String icon, int color, int sizeDp) {
    Glyph g = new Glyph(icon, color);
    g.setBounds(0, 0, dp(v.getContext(), sizeDp), dp(v.getContext(), sizeDp));
    v.setCompoundDrawablesRelative(g, null, null, null);
    v.setCompoundDrawablePadding(dp(v.getContext(), 8));
  }

  /** Selectable chip; call {@link #select} to change state with a small animation. */
  static TextView chip(Activity a, String label, Runnable action) {
    TextView v = text(a, label, 13, MUTED);
    v.setGravity(Gravity.CENTER);
    v.setMinHeight(dp(a, 38));
    v.setPadding(dp(a, 14), 0, dp(a, 14), 0);
    select(v, false);
    v.setOnClickListener(x -> action.run());
    pressable(v);
    return v;
  }

  static void select(TextView chip, boolean on) {
    Context c = chip.getContext();
    if (on) {
      chip.setBackground(gradient(dp(c, 19)));
      chip.setTextColor(0xffffffff);
    } else {
      chip.setBackground(outlined(CARD, STROKE, dp(c, 19), dp(c, 1)));
      chip.setTextColor(MUTED);
    }
  }

  interface FloatChange {
    void changed(float value, boolean fromUser);
  }

  /** Label, live value and a slider in one card row. Value range is [min, max]. */
  static LinearLayout slider(
      Activity a,
      String label,
      float min,
      float max,
      float value,
      java.util.function.Function<Float, String> format,
      FloatChange change) {
    LinearLayout box = column(a);
    box.setPadding(dp(a, 4), dp(a, 6), dp(a, 4), dp(a, 2));
    LinearLayout top = row(a);
    TextView name = text(a, label, 14, TEXT);
    TextView shown = text(a, format.apply(value), 13, ACCENT2);
    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(shown);
    SeekBar bar = new SeekBar(a);
    bar.setMax(1000);
    bar.setProgress(Math.round((value - min) / (max - min) * 1000));
    bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
    bar.setThumbTintList(ColorStateList.valueOf(0xffffffff));
    bar.setProgressBackgroundTintList(ColorStateList.valueOf(STROKE));
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            float v = min + (max - min) * p / 1000f;
            shown.setText(format.apply(v));
            change.changed(v, user);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {
            change.changed(min + (max - min) * s.getProgress() / 1000f, true);
          }
        });
    box.setTag(bar);
    box.addView(top);
    box.addView(bar, new LinearLayout.LayoutParams(-1, dp(a, 36)));
    return box;
  }

  static void setSlider(LinearLayout slider, float min, float max, float value) {
    ((SeekBar) slider.getTag()).setProgress(Math.round((value - min) / (max - min) * 1000));
  }

  static LinearLayout card(Activity a) {
    LinearLayout v = column(a);
    v.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
    v.setBackground(outlined(CARD, STROKE, dp(a, 20), dp(a, 1)));
    return v;
  }

  static LinearLayout column(Context a) {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  static LinearLayout row(Context a) {
    LinearLayout v = new LinearLayout(a);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  static LinearLayout.LayoutParams lp(int w, int h, int topMargin) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
    p.topMargin = topMargin;
    return p;
  }

  static View space(Context c, int w) {
    View v = new View(c);
    v.setLayoutParams(new LinearLayout.LayoutParams(w, 1));
    return v;
  }

  /** Slide-up + fade entrance, staggered by index. */
  static void enter(View v, int index) {
    v.setAlpha(0f);
    v.setTranslationY(dp(v.getContext(), 22));
    v.animate()
        .alpha(1f)
        .translationY(0)
        .setStartDelay(60L * index)
        .setDuration(520)
        .setInterpolator(EASE)
        .start();
  }

  static void toast(Context c, String s) {
    Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
  }

  static String time(long ms) {
    long s = Math.max(0, ms / 1000);
    return s >= 3600
        ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
  }

  /** 1:23.4 — tenth of a second, for precise editing labels. */
  static String timeFine(long ms) {
    ms = Math.max(0, ms);
    return String.format(Locale.US, "%d:%02d.%d", ms / 60000, ms / 1000 % 60, ms / 100 % 10);
  }

  static String duration(long ms) {
    long s = Math.round(ms / 1000.0);
    if (s < 60) return s + " ث";
    return s % 60 == 0 ? (s / 60) + " د" : String.format(Locale.US, "%d:%02d د", s / 60, s % 60);
  }

  /** Animated gradient progress bar that "breathes" while busy. */
  static final class Progress extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float value = 0f, shimmer = 0f;
    private boolean indeterminate = false;
    private final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

    Progress(Context c) {
      super(c);
      track.setColor(STROKE);
      anim.setDuration(1400);
      anim.setRepeatCount(ValueAnimator.INFINITE);
      anim.addUpdateListener(
          x -> {
            shimmer = (float) x.getAnimatedValue();
            invalidate();
          });
    }

    void set(float v, boolean indeterminate) {
      this.indeterminate = indeterminate;
      if (indeterminate) {
        if (!anim.isStarted()) anim.start();
      } else {
        anim.cancel();
        ValueAnimator a = ValueAnimator.ofFloat(value, Math.max(0, Math.min(1, v)));
        a.setDuration(260);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(
            x -> {
              value = (float) x.getAnimatedValue();
              invalidate();
            });
        a.start();
      }
    }

    @Override
    protected void onDetachedFromWindow() {
      anim.cancel();
      super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
      float h = getHeight(), w = getWidth(), r = h / 2;
      c.drawRoundRect(0, 0, w, h, r, r, track);
      float start, end;
      if (indeterminate) {
        start = (shimmer * 1.4f - .4f) * w;
        end = start + w * .4f;
      } else {
        start = 0;
        end = w * value;
      }
      start = Math.max(0, start);
      end = Math.min(w, end);
      if (end <= start) return;
      fill.setShader(new LinearGradient(start, 0, Math.max(end, start + 1), 0, ACCENT, ACCENT2, Shader.TileMode.CLAMP));
      boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
      if (rtl) c.drawRoundRect(w - end, 0, w - start, h, r, r, fill);
      else c.drawRoundRect(start, 0, end, h, r, r, fill);
    }
  }

  /** Line icons drawn in code so the app needs no image assets. */
  static final class Glyph extends Drawable {
    final String type;
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Glyph(String t, int color) {
      type = t;
      paint.setColor(color);
    }

    @Override
    public int getIntrinsicWidth() {
      return 72;
    }

    @Override
    public int getIntrinsicHeight() {
      return 72;
    }

    public void draw(Canvas c) {
      Rect b = getBounds();
      c.save();
      c.translate(b.left, b.top);
      c.scale(b.width() / 24f, b.height() / 24f);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.9f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      Path p = new Path();
      switch (type) {
        case "back":
          c.drawLine(5, 12, 19, 12, paint);
          c.drawLine(19, 12, 13, 6, paint);
          c.drawLine(19, 12, 13, 18, paint);
          break;
        case "play":
          paint.setStyle(Paint.Style.FILL);
          p.moveTo(8, 5);
          p.lineTo(19, 12);
          p.lineTo(8, 19);
          p.close();
          c.drawPath(p, paint);
          break;
        case "pause":
          paint.setStyle(Paint.Style.FILL);
          c.drawRoundRect(6.5f, 5, 10, 19, 1.5f, 1.5f, paint);
          c.drawRoundRect(14, 5, 17.5f, 19, 1.5f, 1.5f, paint);
          break;
        case "magic":
          c.drawLine(4, 20, 14, 10, paint);
          p.moveTo(17, 3);
          p.lineTo(18, 6);
          p.lineTo(21, 7);
          p.lineTo(18, 8);
          p.lineTo(17, 11);
          p.lineTo(16, 8);
          p.lineTo(13, 7);
          p.lineTo(16, 6);
          p.close();
          c.drawPath(p, paint);
          c.drawPoint(8, 5, paint);
          c.drawPoint(20, 15, paint);
          break;
        case "plus":
          c.drawLine(12, 5, 12, 19, paint);
          c.drawLine(5, 12, 19, 12, paint);
          break;
        case "trash":
          c.drawLine(4, 7, 20, 7, paint);
          c.drawRoundRect(6.5f, 7, 17.5f, 20, 2, 2, paint);
          c.drawLine(10, 4, 14, 4, paint);
          c.drawLine(10, 11, 10, 16, paint);
          c.drawLine(14, 11, 14, 16, paint);
          break;
        case "export":
          c.drawLine(12, 4, 12, 15, paint);
          c.drawLine(12, 4, 7.5f, 8.5f, paint);
          c.drawLine(12, 4, 16.5f, 8.5f, paint);
          c.drawLine(5, 14, 5, 19, paint);
          c.drawLine(5, 19, 19, 19, paint);
          c.drawLine(19, 19, 19, 14, paint);
          break;
        case "video":
          c.drawRoundRect(3, 6, 16, 18, 3, 3, paint);
          p.moveTo(16, 10.5f);
          p.lineTo(21, 7.5f);
          p.lineTo(21, 16.5f);
          p.lineTo(16, 13.5f);
          c.drawPath(p, paint);
          break;
        case "phone":
          c.drawRoundRect(7, 2.5f, 17, 21.5f, 2.5f, 2.5f, paint);
          c.drawLine(10.5f, 18.5f, 13.5f, 18.5f, paint);
          break;
        case "reset":
          c.drawArc(4, 4, 20, 20, 200, 290, false, paint);
          c.drawLine(4.6f, 8, 5, 3.5f, paint);
          c.drawLine(4.6f, 8, 9, 8.6f, paint);
          break;
        case "check":
          c.drawLine(5, 12.5f, 10, 17.5f, paint);
          c.drawLine(10, 17.5f, 19, 7, paint);
          break;
        case "left":
          c.drawLine(15, 5, 8, 12, paint);
          c.drawLine(8, 12, 15, 19, paint);
          break;
        case "right":
          c.drawLine(9, 5, 16, 12, paint);
          c.drawLine(16, 12, 9, 19, paint);
          break;
        default:
          c.drawCircle(12, 12, 8, paint);
      }
      c.restore();
    }

    public void setAlpha(int a) {
      paint.setAlpha(a);
    }

    public void setColorFilter(ColorFilter f) {
      paint.setColorFilter(f);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
