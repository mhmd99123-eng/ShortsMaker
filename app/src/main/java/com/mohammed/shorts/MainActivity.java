package com.mohammed.shorts;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Size;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home: pick a video, see the Shorts made so far. */
public final class MainActivity extends Activity {
  private static final int PICK = 11;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private LinearLayout recent;
  private TextView recentTitle;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout root = Ui.column(this);
    int p = Ui.dp(this, 20);
    root.setPadding(p, Ui.dp(this, 34), p, p);
    scroll.addView(root);
    setContentView(scroll);

    TextView hello = Ui.text(this, "أهلًا محمد 👋", 15, Ui.MUTED);
    root.addView(hello);
    TextView title = Ui.title(this, "صانع الشورت", 34);
    root.addView(title, Ui.lp(-2, -2, Ui.dp(this, 2)));
    title.post(
        () ->
            title
                .getPaint()
                .setShader(
                    new LinearGradient(
                        0, 0, title.getWidth(), 0, 0xffffffff, Ui.ACCENT2, Shader.TileMode.CLAMP)));
    TextView sub =
        Ui.text(this, "حوّل أي جزء من فيديو عرضي إلى شورت عمودي بخلفية مغبّشة — بدون قص.", 15, Ui.MUTED);
    root.addView(sub, Ui.lp(-1, -2, Ui.dp(this, 6)));

    View hero = hero();
    root.addView(hero, Ui.lp(-1, Ui.dp(this, 230), Ui.dp(this, 22)));

    TextView pick = Ui.primary(this, "اختر فيديو", "video", this::pick);
    root.addView(pick, Ui.lp(-1, Ui.dp(this, 58), Ui.dp(this, 18)));

    LinearLayout steps = Ui.row(this);
    steps.addView(step("١", "اختر الجزء", "أو خلّه يدوّر أفضل اللقطات"), new LinearLayout.LayoutParams(0, -2, 1));
    steps.addView(Ui.space(this, Ui.dp(this, 8)));
    steps.addView(step("٢", "اضبط الشكل", "غباش، حجم، زوايا"), new LinearLayout.LayoutParams(0, -2, 1));
    steps.addView(Ui.space(this, Ui.dp(this, 8)));
    steps.addView(step("٣", "صدّر", "١٠٨٠×١٩٢٠ للاستديو"), new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(steps, Ui.lp(-1, -2, Ui.dp(this, 16)));

    recentTitle = Ui.title(this, "آخر الشورتات", 19);
    root.addView(recentTitle, Ui.lp(-2, -2, Ui.dp(this, 26)));
    recent = Ui.column(this);
    root.addView(recent, Ui.lp(-1, -2, Ui.dp(this, 10)));

    for (int i = 0; i < root.getChildCount(); i++) Ui.enter(root.getChildAt(i), i);
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED)
      requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, 3);
  }

  @Override
  protected void onResume() {
    super.onResume();
    loadRecent();
  }

  @Override
  protected void onDestroy() {
    io.shutdownNow();
    super.onDestroy();
  }

  /** A phone frame with a moving "video" and blurred bands: shows the idea at a glance. */
  private View hero() {
    return new View(this) {
      final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
      final RectF r = new RectF();
      float t = 0;
      final ValueAnimator anim = ValueAnimator.ofFloat(0, 1);

      {
        setBackground(Ui.outlined(Ui.SURFACE, Ui.STROKE, Ui.dp(getContext(), 26), Ui.dp(getContext(), 1)));
        anim.setDuration(6000);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(null);
        anim.addUpdateListener(
            x -> {
              t = (float) x.getAnimatedValue();
              invalidate();
            });
      }

      @Override
      protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        anim.start();
      }

      @Override
      protected void onDetachedFromWindow() {
        anim.cancel();
        super.onDetachedFromWindow();
      }

      @Override
      protected void onDraw(Canvas c) {
        float h = getHeight() - Ui.dp(getContext(), 36), w = h * 9 / 16f;
        float cx = getWidth() / 2f, top = Ui.dp(getContext(), 18);
        r.set(cx - w / 2, top, cx + w / 2, top + h);
        float rad = Ui.dp(getContext(), 18);
        double a = t * Math.PI * 2;
        int c1 = blend(Ui.ACCENT, 0xffff7ab6, (float) (Math.sin(a) * .5 + .5));
        int c2 = blend(Ui.ACCENT2, 0xff4ade80, (float) (Math.cos(a) * .5 + .5));
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, c1, c2, Shader.TileMode.CLAMP));
        paint.setAlpha(110);
        c.drawRoundRect(r, rad, rad, paint);
        // the sharp landscape video in the middle
        float vh = w * 9 / 16f;
        float vy = top + (h - vh) / 2;
        paint.setAlpha(255);
        paint.setShader(new LinearGradient(r.left, vy, r.right, vy + vh, c1, c2, Shader.TileMode.CLAMP));
        c.drawRect(r.left, vy, r.right, vy + vh, paint);
        paint.setShader(null);
        paint.setColor(0x33ffffff);
        float sun = (float) (r.left + w * (.25 + .5 * (Math.sin(a) * .5 + .5)));
        c.drawCircle(sun, vy + vh * .45f, vh * .18f, paint);
        paint.setColor(0xffffffff);
        Path tri = new Path();
        float s = vh * .16f, mx = cx, my = vy + vh / 2;
        tri.moveTo(mx - s * .6f, my - s);
        tri.lineTo(mx + s, my);
        tri.lineTo(mx - s * .6f, my + s);
        tri.close();
        c.drawPath(tri, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        paint.setColor(0x55ffffff);
        c.drawRoundRect(r, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        // floating labels
        paint.setColor(Ui.TEXT);
        paint.setTextSize(Ui.dp(getContext(), 12));
        paint.setTextAlign(Paint.Align.CENTER);
        float lx = r.right + (getWidth() - r.right) / 2;
        float bob = (float) Math.sin(a * 2) * Ui.dp(getContext(), 3);
        c.drawText("16:9", r.left / 2, my + bob, paint);
        c.drawText("9:16", lx, my - bob, paint);
        paint.setColor(Ui.MUTED);
        c.drawText("→", (r.left / 2 + r.left) / 2 + Ui.dp(getContext(), 6), my, paint);
      }

      int blend(int x, int y, float f) {
        int xr = (x >> 16) & 255, xg = (x >> 8) & 255, xb = x & 255;
        int yr = (y >> 16) & 255, yg = (y >> 8) & 255, yb = y & 255;
        return 0xff000000
            | ((int) (xr + (yr - xr) * f) << 16)
            | ((int) (xg + (yg - xg) * f) << 8)
            | (int) (xb + (yb - xb) * f);
      }
    };
  }

  private View step(String n, String head, String body) {
    LinearLayout card = Ui.card(this);
    card.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
    TextView num = Ui.text(this, n, 14, 0xffffffff);
    num.setGravity(Gravity.CENTER);
    num.setBackground(Ui.gradient(Ui.dp(this, 14)));
    card.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
    TextView h = Ui.text(this, head, 14, Ui.TEXT);
    Ui.bold(h);
    card.addView(h, Ui.lp(-2, -2, Ui.dp(this, 8)));
    card.addView(Ui.text(this, body, 12, Ui.MUTED), Ui.lp(-2, -2, Ui.dp(this, 2)));
    return card;
  }

  private void pick() {
    Intent i;
    if (Build.VERSION.SDK_INT >= 33) {
      i = new Intent(MediaStore.ACTION_PICK_IMAGES).setType("video/*");
    } else {
      i =
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .addCategory(Intent.CATEGORY_OPENABLE)
              .setType("video/*");
    }
    try {
      startActivityForResult(i, PICK);
    } catch (Exception e) {
      startActivityForResult(
          new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*"),
          PICK);
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (Exception ignored) {
      // Photo-picker links are already readable for this session.
    }
    startActivity(
        new Intent(this, EditorActivity.class)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
  }

  private void loadRecent() {
    io.execute(
        () -> {
          List<Uri> uris = new ArrayList<>();
          List<String> names = new ArrayList<>();
          List<Long> lengths = new ArrayList<>();
          String[] cols = {
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION
          };
          try (Cursor c =
              getContentResolver()
                  .query(
                      MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                      cols,
                      MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                      new String[] {"Movies/ShortsMaker%"},
                      MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext() && uris.size() < 12) {
              uris.add(ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
              names.add(c.getString(1));
              lengths.add(c.getLong(2));
            }
          } catch (Exception ignored) {
          }
          List<Bitmap> thumbs = new ArrayList<>();
          for (Uri u : uris) {
            Bitmap t = null;
            try {
              t = getContentResolver().loadThumbnail(u, new Size(270, 480), null);
            } catch (Exception ignored) {
            }
            thumbs.add(t);
          }
          main.post(() -> showRecent(uris, lengths, thumbs));
        });
  }

  private void showRecent(List<Uri> uris, List<Long> lengths, List<Bitmap> thumbs) {
    if (isDestroyed()) return;
    recent.removeAllViews();
    if (uris.isEmpty()) {
      TextView empty = Ui.text(this, "لسّا ما سويت شورت. اختر فيديو وابدأ ✨", 14, Ui.MUTED);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 24));
      empty.setBackground(Ui.outlined(Ui.SURFACE, Ui.STROKE, Ui.dp(this, 20), Ui.dp(this, 1)));
      recent.addView(empty, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    LinearLayout row = null;
    for (int i = 0; i < uris.size(); i++) {
      if (i % 3 == 0) {
        row = Ui.row(this);
        recent.addView(row, Ui.lp(-1, -2, i == 0 ? 0 : Ui.dp(this, 10)));
      }
      Uri u = uris.get(i);
      FrameLayout cell = new FrameLayout(this);
      cell.setBackground(Ui.shape(Ui.CARD, Ui.dp(this, 16)));
      cell.setClipToOutline(true);
      ImageView img = new ImageView(this);
      img.setScaleType(ImageView.ScaleType.CENTER_CROP);
      if (thumbs.get(i) != null) img.setImageBitmap(thumbs.get(i));
      cell.addView(img, new FrameLayout.LayoutParams(-1, -1));
      TextView len = Ui.text(this, Ui.time(lengths.get(i)), 11, 0xffffffff);
      len.setPadding(Ui.dp(this, 7), Ui.dp(this, 2), Ui.dp(this, 7), Ui.dp(this, 2));
      len.setBackground(Ui.shape(0x99000000, Ui.dp(this, 9)));
      FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
      lp.setMargins(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 6));
      cell.addView(len, lp);
      cell.setOnClickListener(
          v -> {
            try {
              startActivity(
                  new Intent(Intent.ACTION_VIEW)
                      .setDataAndType(u, "video/mp4")
                      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
              Ui.toast(this, "ما لقيت تطبيق يشغّل الفيديو");
            }
          });
      Ui.pressable(cell);
      int w = (getResources().getDisplayMetrics().widthPixels - Ui.dp(this, 60)) / 3;
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(w, w * 16 / 9);
      if (i % 3 != 0) cp.setMarginStart(Ui.dp(this, 10));
      row.addView(cell, cp);
      Ui.enter(cell, i);
    }
  }
}
