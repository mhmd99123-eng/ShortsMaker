package com.mohammed.shorts;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.database.Cursor;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.SeekParameters;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pick the part, tune the look with a live 9:16 preview, find highlights, export. */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class EditorActivity extends Activity {
  private static final long[] LENGTHS = {15_000, 30_000, 60_000, 90_000, 120_000, 180_000};
  private static final String[] LENGTH_LABELS = {"15ث", "30ث", "60ث", "90ث", "2د", "3د"};

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newFixedThreadPool(2);

  private Uri uri;
  private String name = "Video";
  private Clip.Info info;
  private ShortStyle style;
  private ExoPlayer player;
  private boolean effectsOn = true;

  private TextureView texture;
  private FrameLayout previewBox;
  private ImageView playIcon;
  private TimelineView timeline;
  private TextView selLabel, timeLabel, handleLabel;
  private final List<TextView> lengthChips = new ArrayList<>();
  private TextView customChip;
  private long clipLen = 60_000;

  private final TextView[] tabs = new TextView[4];
  private FrameLayout tabBody;
  private View smartView, lookView, clipsView, exportView;

  // smart search
  private Highlights.Signal signal;
  private volatile boolean analysing, cancelAnalysis;
  private Ui.Progress analyseBar;
  private TextView analyseText, analyseButton;
  private LinearLayout results;
  private List<Clip> suggestions = new ArrayList<>();

  // queue
  private final List<Clip> queue = new ArrayList<>();
  private LinearLayout queueList;
  private TextView queueTitle, exportButtonTop, exportButtonBig;

  // look
  private final Map<String, LinearLayout> sliders = new HashMap<>();
  private final List<TextView> bgChips = new ArrayList<>();
  private LinearLayout swatches;

  // export settings
  private int quality = 1; // 0 720p, 1 1080p, 2 1440p
  private boolean cap30 = false;

  // export overlay
  private LinearLayout sheet;
  private Ui.Progress sheetBar;
  private TextView sheetText, sheetAction, sheetCancel;

  @Override
  protected void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    uri = readUri(getIntent());
    if (uri == null) {
      Ui.toast(this, "ما وصلني فيديو");
      finish();
      return;
    }
    style = ShortStyle.load(this);
    style.outW = 720;
    style.outH = 1280;
    SharedPreferences p = getSharedPreferences("editor", 0);
    clipLen = p.getLong("clipLen", 60_000);
    quality = p.getInt("quality", 1);
    cap30 = p.getBoolean("cap30", false);
    buildUi();
    io.execute(
        () -> {
          try {
            Clip.Info i = Clip.Info.read(this, uri);
            String n = displayName(uri);
            main.post(() -> ready(i, n));
          } catch (Exception e) {
            main.post(
                () -> {
                  Ui.toast(this, e.getMessage() == null ? "ما قدرت أفتح الفيديو" : e.getMessage());
                  finish();
                });
          }
        });
  }

  private static Uri readUri(Intent i) {
    if (i == null) return null;
    if (Intent.ACTION_SEND.equals(i.getAction())) {
      @SuppressWarnings("deprecation")
      Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (u != null) return u;
    }
    return i.getData();
  }

  private String displayName(Uri u) {
    try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
    } catch (Exception ignored) {
    }
    return "Video";
  }

  // ---------------------------------------------------------------- layout

  private void buildUi() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    LinearLayout col = Ui.column(this);
    root.addView(col, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    int pad = Ui.dp(this, 14);

    // Top bar
    LinearLayout bar = Ui.row(this);
    bar.setPadding(pad, Ui.dp(this, 30), pad, Ui.dp(this, 6));
    bar.addView(Ui.iconButton(this, "back", "رجوع", this::finish), new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
    TextView t = Ui.title(this, "تعديل الشورت", 18);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
    tl.setMarginStart(Ui.dp(this, 12));
    bar.addView(t, tl);
    exportButtonTop = Ui.primary(this, "تصدير", "export", this::startExport);
    exportButtonTop.setTextSize(14);
    exportButtonTop.setMinHeight(Ui.dp(this, 44));
    bar.addView(exportButtonTop, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
    col.addView(bar);

    // Preview (exactly 9:16)
    int screenH = getResources().getDisplayMetrics().heightPixels;
    int ph = (int) (screenH * .40f), pw = ph * 9 / 16;
    previewBox = new FrameLayout(this);
    previewBox.setBackground(Ui.shape(Ui.SURFACE, Ui.dp(this, 18)));
    previewBox.setClipToOutline(true);
    previewBox.setElevation(Ui.dp(this, 10));
    texture = new TextureView(this);
    previewBox.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    playIcon = new ImageView(this);
    playIcon.setImageDrawable(new Ui.Glyph("play", 0xffffffff));
    playIcon.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
    playIcon.setBackground(Ui.shape(0x66000000, Ui.dp(this, 32)));
    previewBox.addView(playIcon, new FrameLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64), Gravity.CENTER));
    timeLabel = Ui.text(this, "0:00", 11, 0xffffffff);
    timeLabel.setPadding(Ui.dp(this, 8), Ui.dp(this, 3), Ui.dp(this, 8), Ui.dp(this, 3));
    timeLabel.setBackground(Ui.shape(0x88000000, Ui.dp(this, 10)));
    FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    tlp.bottomMargin = Ui.dp(this, 8);
    previewBox.addView(timeLabel, tlp);
    previewBox.setOnClickListener(v -> togglePlay());
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(pw, ph);
    plp.gravity = Gravity.CENTER_HORIZONTAL;
    plp.topMargin = Ui.dp(this, 4);
    col.addView(previewBox, plp);

    // Selection label + timeline
    selLabel = Ui.text(this, "", 13, Ui.TEXT);
    selLabel.setGravity(Gravity.CENTER);
    col.addView(selLabel, Ui.lp(-1, -2, Ui.dp(this, 10)));
    timeline = new TimelineView(this);
    col.addView(timeline, Ui.lp(-1, -2, Ui.dp(this, 2)));
    timeline.setListener(
        new TimelineView.Listener() {
          public void selectionChanged(long s, long e, boolean finished) {
            onSelection(s, e, finished);
          }

          public void seek(long ms, boolean finished) {
            if (player == null) return;
            player.setSeekParameters(finished ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
            player.seekTo(ms);
            timeline.setPlayhead(ms);
          }

          public void handleSelected(int h) {
            updateHandleLabel();
          }
        });

    // Nudge row: frame-precise trimming
    LinearLayout nudge = Ui.row(this);
    nudge.setPadding(pad, 0, pad, 0);
    nudge.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    ImageView left = Ui.iconButton(this, "left", "رجوع إطار", () -> nudge(-1));
    left.setOnLongClickListener(v -> { nudgeMs(-1000); return true; });
    ImageView right = Ui.iconButton(this, "right", "تقديم إطار", () -> nudge(1));
    right.setOnLongClickListener(v -> { nudgeMs(1000); return true; });
    handleLabel = Ui.ghost(this, "", null, this::switchHandle);
    handleLabel.setTextSize(13);
    nudge.addView(left, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
    LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1);
    hl.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
    nudge.addView(handleLabel, hl);
    nudge.addView(right, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
    TextView playPart = Ui.ghost(this, "الجزء", "play", this::playSelection);
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42));
    pp.setMarginStart(Ui.dp(this, 8));
    nudge.addView(playPart, pp);
    TextView zoom = Ui.ghost(this, "تكبير", null, this::toggleZoom);
    LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42));
    zp.setMarginStart(Ui.dp(this, 8));
    nudge.addView(zoom, zp);
    col.addView(nudge, Ui.lp(-1, -2, Ui.dp(this, 2)));

    // Length chips
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = Ui.row(this);
    chips.setPadding(pad, 0, pad, 0);
    for (int i = 0; i < LENGTHS.length; i++) {
      long len = LENGTHS[i];
      TextView c = Ui.chip(this, LENGTH_LABELS[i], () -> setLength(len));
      lengthChips.add(c);
      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
      cl.setMarginEnd(Ui.dp(this, 8));
      chips.addView(c, cl);
    }
    customChip = Ui.chip(this, "حر", () -> Ui.toast(this, "اسحب أطراف الشريط لأي طول لين 3 دقايق"));
    chips.addView(customChip);
    hs.addView(chips);
    col.addView(hs, Ui.lp(-1, -2, Ui.dp(this, 10)));

    // Tabs
    LinearLayout tabRow = Ui.row(this);
    tabRow.setPadding(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
    tabRow.setBackground(Ui.outlined(Ui.SURFACE, Ui.STROKE, Ui.dp(this, 22), Ui.dp(this, 1)));
    String[] names = {"✨ ذكي", "الشكل", "المقاطع", "التصدير"};
    for (int i = 0; i < 4; i++) {
      int idx = i;
      TextView tab = Ui.text(this, names[i], 13, Ui.MUTED);
      tab.setGravity(Gravity.CENTER);
      tab.setOnClickListener(v -> showTab(idx));
      tabs[i] = tab;
      tabRow.addView(tab, new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1));
    }
    LinearLayout.LayoutParams trp = Ui.lp(-1, -2, Ui.dp(this, 12));
    trp.setMargins(pad, Ui.dp(this, 12), pad, 0);
    col.addView(tabRow, trp);

    ScrollView scroll = new ScrollView(this);
    tabBody = new FrameLayout(this);
    tabBody.setPadding(pad, Ui.dp(this, 10), pad, Ui.dp(this, 24));
    scroll.addView(tabBody);
    col.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

    smartView = buildSmart();
    lookView = buildLook();
    clipsView = buildClips();
    exportView = buildExport();
    showTab(0);

    sheet = buildSheet();
    FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    root.addView(sheet, sp);
    sheet.setVisibility(View.GONE);

    for (int i = 0; i < col.getChildCount(); i++) Ui.enter(col.getChildAt(i), i);
  }

  private void showTab(int i) {
    for (int k = 0; k < 4; k++) {
      if (k == i) {
        tabs[k].setBackground(Ui.gradient(Ui.dp(this, 19)));
        tabs[k].setTextColor(0xffffffff);
        Ui.bold(tabs[k]);
      } else {
        tabs[k].setBackground(null);
        tabs[k].setTextColor(Ui.MUTED);
        tabs[k].setTypeface(Typeface.DEFAULT);
      }
    }
    View v = i == 0 ? smartView : i == 1 ? lookView : i == 2 ? clipsView : exportView;
    tabBody.removeAllViews();
    tabBody.addView(v);
    v.setAlpha(0f);
    v.setTranslationY(Ui.dp(this, 10));
    v.animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(Ui.EASE).start();
  }

  // ---------------------------------------------------------------- smart tab

  private View buildSmart() {
    LinearLayout c = Ui.column(this);
    LinearLayout card = Ui.card(this);
    TextView h = Ui.title(this, "دوّر لي أفضل اللقطات", 17);
    card.addView(h);
    card.addView(
        Ui.text(this, "يحلل الصوت والحماس والحركة وتغيّر المشاهد، ويقترح أقوى المقاطع بالطول اللي اخترته. ويبدأ ويوقف عند سكتة عشان ما يقطع الكلام.", 13, Ui.MUTED),
        Ui.lp(-1, -2, Ui.dp(this, 6)));
    analyseButton = Ui.primary(this, "ابحث الحين", "magic", this::analyse);
    card.addView(analyseButton, Ui.lp(-1, Ui.dp(this, 50), Ui.dp(this, 12)));
    analyseBar = new Ui.Progress(this);
    analyseBar.setVisibility(View.GONE);
    card.addView(analyseBar, Ui.lp(-1, Ui.dp(this, 8), Ui.dp(this, 12)));
    analyseText = Ui.text(this, "", 12, Ui.MUTED);
    analyseText.setVisibility(View.GONE);
    card.addView(analyseText, Ui.lp(-1, -2, Ui.dp(this, 6)));
    c.addView(card);
    results = Ui.column(this);
    c.addView(results, Ui.lp(-1, -2, Ui.dp(this, 10)));
    return c;
  }

  private void analyse() {
    if (info == null) return;
    if (analysing) {
      cancelAnalysis = true;
      return;
    }
    if (signal != null) {
      repick();
      return;
    }
    analysing = true;
    cancelAnalysis = false;
    analyseButton.setText("إيقاف");
    analyseBar.setVisibility(View.VISIBLE);
    analyseText.setVisibility(View.VISIBLE);
    analyseBar.set(0, false);
    analyseText.setText("أسمع الفيديو وأشوفه… 0٪");
    long started = SystemClock.elapsedRealtime();
    io.execute(
        () -> {
          try {
            Highlights.Signal s =
                Highlights.analyze(
                    this,
                    uri,
                    info.durationMs,
                    new Highlights.Listener() {
                      long lastPost;

                      public void progress(float f) {
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastPost < 120) return;
                        lastPost = now;
                        main.post(
                            () -> {
                              if (!analysing) return;
                              analyseBar.set(f, false);
                              int pct = Math.round(f * 100);
                              String left = "";
                              long el = now - started;
                              if (f > .06f) {
                                long sec = (long) (el * (1 - f) / f / 1000);
                                left = sec < 60 ? " · باقي " + Math.max(1, sec) + " ث" : " · باقي " + (sec / 60 + 1) + " د";
                              }
                              analyseText.setText("أسمع الفيديو وأشوفه… " + pct + "٪" + left);
                            });
                      }

                      public boolean cancelled() {
                        return cancelAnalysis || isDestroyed();
                      }
                    });
            main.post(
                () -> {
                  analysing = false;
                  signal = s;
                  analyseBar.setVisibility(View.GONE);
                  analyseText.setText(s.hasAudio ? "تم ✓ — اخترت لك أقوى المقاطع" : "تم ✓ — الفيديو بدون صوت، اعتمدت على الصورة");
                  analyseButton.setText("حدّث الاقتراحات");
                  float[] heat = Highlights.interest(s);
                  timeline.setHeat(Highlights.normalise(heat, 5, 98));
                  repick();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  analysing = false;
                  analyseBar.setVisibility(View.GONE);
                  analyseButton.setText("ابحث الحين");
                  analyseText.setText(cancelAnalysis ? "توقف البحث" : "ما قدرت أحلل الفيديو: " + e.getMessage());
                });
          }
        });
  }

  private void repick() {
    if (signal == null) return;
    suggestions = Highlights.pick(signal, Math.min(clipLen, info.durationMs), 5);
    timeline.setSuggestions(suggestions);
    results.removeAllViews();
    if (suggestions.isEmpty()) return;
    LinearLayout head = Ui.row(this);
    TextView ht = Ui.title(this, "الاقتراحات (" + Ui.duration(clipLen) + ")", 16);
    head.addView(ht, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.ghost(this, "أضف الكل", "plus", () -> {
      for (Clip c : suggestions) addToQueue(c, false);
      Ui.toast(this, "انضافت للمقاطع");
    }));
    results.addView(head);
    for (int i = 0; i < suggestions.size(); i++) {
      View card = suggestionCard(i, suggestions.get(i));
      results.addView(card, Ui.lp(-1, -2, Ui.dp(this, 10)));
      Ui.enter(card, i);
    }
    // Jump straight to the best one.
    Clip best = suggestions.get(0);
    applyClip(best);
  }

  private View suggestionCard(int index, Clip c) {
    LinearLayout card = Ui.card(this);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    ImageView thumb = new ImageView(this);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    thumb.setBackground(Ui.shape(Ui.SURFACE, Ui.dp(this, 10)));
    thumb.setClipToOutline(true);
    card.addView(thumb, new LinearLayout.LayoutParams(Ui.dp(this, 92), Ui.dp(this, 52)));
    io.execute(
        () -> {
          Bitmap b = frameAt(c.startMs + Math.min(2000, c.length() / 3), 240, 136);
          main.post(() -> { if (b != null) thumb.setImageBitmap(b); });
        });
    LinearLayout texts = Ui.column(this);
    texts.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 8), 0);
    TextView t = Ui.text(this, "اللقطة " + (index + 1) + (index == 0 ? " · الأقوى 🔥" : ""), 14, Ui.TEXT);
    Ui.bold(t);
    texts.addView(t);
    TextView when = Ui.text(this, Ui.time(c.startMs) + " ← " + Ui.time(c.endMs) + " · " + Ui.duration(c.length()), 12, Ui.MUTED);
    when.setTextDirection(View.TEXT_DIRECTION_RTL);
    texts.addView(when);
    Ui.Progress strength = new Ui.Progress(this);
    texts.addView(strength, Ui.lp(-1, Ui.dp(this, 5), Ui.dp(this, 6)));
    strength.post(() -> strength.set(Math.max(.08f, c.score), false));
    card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView add = Ui.iconButton(this, "plus", "أضف للتصدير", () -> addToQueue(c, true));
    card.addView(add, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
    card.setOnClickListener(v -> { applyClip(c); playSelection(); });
    Ui.pressable(card);
    return card;
  }

  // ---------------------------------------------------------------- look tab

  private View buildLook() {
    LinearLayout c = Ui.column(this);
    c.addView(Ui.text(this, "قوالب سريعة", 13, Ui.MUTED));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout presets = Ui.row(this);
    String[] pn = {"افتراضي", "سينمائي", "ناعم", "حيوي", "إطار عائم", "ملء الشاشة"};
    for (int i = 0; i < pn.length; i++) {
      int k = i;
      TextView chip = Ui.chip(this, pn[i], () -> applyPreset(k));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(Ui.dp(this, 8));
      presets.addView(chip, lp);
    }
    hs.addView(presets);
    c.addView(hs, Ui.lp(-1, -2, Ui.dp(this, 6)));

    LinearLayout bg = Ui.card(this);
    LinearLayout modes = Ui.row(this);
    modes.addView(Ui.text(this, "الخلفية", 14, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
    String[] bn = {"غباش", "لون"};
    for (int i = 0; i < 2; i++) {
      int k = i;
      TextView chip = Ui.chip(this, bn[i], () -> setBgMode(k));
      bgChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginStart(Ui.dp(this, 8));
      modes.addView(chip, lp);
    }
    bg.addView(modes);
    swatches = Ui.row(this);
    int[] colors = {0xff101018, 0xff000000, 0xffffffff, 0xff1e1b4b, 0xff0f766e, 0xff7c2d12, 0xff831843, 0xfffacc15};
    for (int col : colors) {
      View s = new View(this);
      s.setBackground(Ui.outlined(col, Ui.STROKE, Ui.dp(this, 16), Ui.dp(this, 2)));
      s.setOnClickListener(v -> { style.bgColor = col; afterLook(true); });
      Ui.pressable(s);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32));
      lp.setMarginEnd(Ui.dp(this, 8));
      swatches.addView(s, lp);
    }
    bg.addView(swatches, Ui.lp(-1, -2, Ui.dp(this, 10)));
    bg.addView(slider("blur", "قوة الغباش", 0, 1, style.blur, v -> pct(v), (v) -> style.blur = v));
    bg.addView(slider("dim", "تغميق الخلفية", 0, .85f, style.dim, v -> pct(v / .85f), (v) -> style.dim = v));
    bg.addView(slider("sat", "حيوية الألوان", .5f, 1.8f, style.saturation, v -> pct((v - .5f) / 1.3f), (v) -> style.saturation = v));
    c.addView(bg, Ui.lp(-1, -2, Ui.dp(this, 12)));

    LinearLayout fg = Ui.card(this);
    fg.addView(Ui.text(this, "الفيديو بالنص", 14, Ui.TEXT));
    fg.addView(slider("zoom", "الحجم", .6f, 2.2f, style.zoom, v -> Math.round(v * 100) + "٪", (v) -> style.zoom = v));
    fg.addView(slider("y", "الموضع (تحت ← فوق)", -1, 1, style.offsetY, v -> signed(v), (v) -> style.offsetY = v));
    fg.addView(slider("x", "يمين/يسار (عند التكبير)", -1, 1, style.offsetX, v -> signed(v), (v) -> style.offsetX = v));
    fg.addView(slider("radius", "انحناء الزوايا", 0, .08f, style.radius, v -> pct(v / .08f), (v) -> style.radius = v));
    fg.addView(slider("shadow", "الظل", 0, 1, style.shadow, v -> pct(v), (v) -> style.shadow = v));
    c.addView(fg, Ui.lp(-1, -2, Ui.dp(this, 12)));

    TextView reset = Ui.ghost(this, "رجّع الشكل الافتراضي", "reset", () -> applyPreset(0));
    c.addView(reset, Ui.lp(-1, Ui.dp(this, 46), Ui.dp(this, 12)));
    syncBgChips();
    return c;
  }

  interface Setter {
    void set(float v);
  }

  private LinearLayout slider(String key, String label, float min, float max, float value, java.util.function.Function<Float, String> fmt, Setter s) {
    LinearLayout v = Ui.slider(this, label, min, max, value, fmt, (x, fromUser) -> { s.set(x); afterLook(fromUser); });
    v.setTag(R_KEY, new float[] {min, max});
    sliders.put(key, v);
    return v;
  }

  private static final int R_KEY = 0x7f0b0001;

  private static String pct(float v) {
    return Math.round(Math.max(0, Math.min(1, v)) * 100) + "٪";
  }

  private static String signed(float v) {
    int n = Math.round(v * 100);
    return n == 0 ? "الوسط" : (n > 0 ? "+" : "") + n;
  }

  private void setSlider(String key, float value) {
    LinearLayout v = sliders.get(key);
    float[] r = (float[]) v.getTag(R_KEY);
    Ui.setSlider(v, r[0], r[1], value);
  }

  private void applyPreset(int k) {
    ShortStyle d = new ShortStyle();
    float blur = d.blur, dim = d.dim, sat = d.saturation, zoom = d.zoom, radius = d.radius, shadow = d.shadow, y = 0, x = 0;
    switch (k) {
      case 1: blur = .8f; dim = .5f; sat = 1.05f; zoom = 1f; radius = 0; shadow = .7f; break;
      case 2: blur = .95f; dim = .12f; sat = 1.25f; zoom = 1f; radius = 0; shadow = .3f; break;
      case 3: blur = .6f; dim = .08f; sat = 1.6f; zoom = 1f; radius = 0; shadow = .5f; break;
      case 4: blur = .7f; dim = .35f; sat = 1.2f; zoom = .9f; radius = .045f; shadow = .9f; break;
      case 5: blur = .5f; dim = .2f; sat = 1.1f; zoom = 1.7f; radius = 0; shadow = .4f; break;
      default: break;
    }
    style.bgMode = ShortStyle.BG_BLUR;
    setSlider("blur", blur);
    setSlider("dim", dim);
    setSlider("sat", sat);
    setSlider("zoom", zoom);
    setSlider("y", y);
    setSlider("x", x);
    setSlider("radius", radius);
    setSlider("shadow", shadow);
    style.blur = blur; style.dim = dim; style.saturation = sat; style.zoom = zoom;
    style.offsetY = y; style.offsetX = x; style.radius = radius; style.shadow = shadow;
    syncBgChips();
    afterLook(true);
  }

  private void setBgMode(int m) {
    style.bgMode = m;
    syncBgChips();
    afterLook(true);
  }

  private void syncBgChips() {
    for (int i = 0; i < bgChips.size(); i++) Ui.select(bgChips.get(i), i == style.bgMode);
    if (swatches != null) {
      swatches.setAlpha(style.bgMode == ShortStyle.BG_COLOR ? 1f : .35f);
    }
    LinearLayout blur = sliders.get("blur");
    if (blur != null) blur.setAlpha(style.bgMode == ShortStyle.BG_BLUR ? 1f : .35f);
  }

  private void afterLook(boolean save) {
    refreshPreview();
    if (save) style.save(this);
  }

  /** Paused player shows no new frames; re-seek so the change is visible right away. */
  private final Runnable refresh = () -> {
    if (player != null && !player.isPlaying()) {
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(player.getCurrentPosition());
    }
  };

  private void refreshPreview() {
    main.removeCallbacks(refresh);
    main.postDelayed(refresh, 60);
  }

  // ---------------------------------------------------------------- clips tab

  private View buildClips() {
    LinearLayout c = Ui.column(this);
    LinearLayout head = Ui.row(this);
    queueTitle = Ui.title(this, "", 16);
    head.addView(queueTitle, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.ghost(this, "أضف الجزء الحالي", "plus", () -> addToQueue(new Clip(timeline.start(), timeline.end(), -1), true)));
    c.addView(head);
    c.addView(Ui.text(this, "كل مقطع هنا يطلع شورت مستقل، وتصدّرها كلها مرة وحدة.", 12, Ui.MUTED), Ui.lp(-1, -2, Ui.dp(this, 6)));
    queueList = Ui.column(this);
    c.addView(queueList, Ui.lp(-1, -2, Ui.dp(this, 8)));
    renderQueue();
    return c;
  }

  private void addToQueue(Clip c, boolean announce) {
    for (Clip q : queue)
      if (Math.abs(q.startMs - c.startMs) < 300 && Math.abs(q.endMs - c.endMs) < 300) {
        if (announce) Ui.toast(this, "هذا المقطع موجود من قبل");
        return;
      }
    queue.add(c);
    Collections.sort(queue, (a, b) -> Long.compare(a.startMs, b.startMs));
    renderQueue();
    if (announce) Ui.toast(this, "انضاف ✓ (" + queue.size() + ")");
  }

  private void renderQueue() {
    if (queueTitle == null) return;
    queueTitle.setText("المقاطع (" + queue.size() + ")");
    tabs[2].setText(queue.isEmpty() ? "المقاطع" : "المقاطع " + queue.size());
    timeline.setQueued(queue);
    String label = queue.size() > 1 ? "تصدير " + queue.size() : "تصدير";
    exportButtonTop.setText(label);
    if (exportButtonBig != null)
      exportButtonBig.setText(queue.size() > 1 ? "صدّر " + queue.size() + " شورتات" : "صدّر الشورت");
    queueList.removeAllViews();
    if (queue.isEmpty()) {
      TextView e = Ui.text(this, "ما فيه مقاطع مضافة. إذا صدّرت الحين بيطلع الجزء المحدد بالشريط.", 13, Ui.MUTED);
      e.setGravity(Gravity.CENTER);
      e.setPadding(Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22));
      e.setBackground(Ui.outlined(Ui.SURFACE, Ui.STROKE, Ui.dp(this, 18), Ui.dp(this, 1)));
      queueList.addView(e, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    for (int i = 0; i < queue.size(); i++) {
      Clip c = queue.get(i);
      LinearLayout row = Ui.card(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      TextView num = Ui.text(this, String.valueOf(i + 1), 13, 0xffffffff);
      num.setGravity(Gravity.CENTER);
      num.setBackground(Ui.gradient(Ui.dp(this, 14)));
      row.addView(num, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
      TextView t = Ui.text(this, Ui.time(c.startMs) + " ← " + Ui.time(c.endMs) + "  ·  " + Ui.duration(c.length()), 14, Ui.TEXT);
      t.setTextDirection(View.TEXT_DIRECTION_RTL);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
      tl.setMarginStart(Ui.dp(this, 12));
      row.addView(t, tl);
      row.addView(Ui.iconButton(this, "play", "عاين", () -> { applyClip(c); playSelection(); }), new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));
      ImageView del = Ui.iconButton(this, "trash", "احذف", () -> { queue.remove(c); renderQueue(); });
      ((Ui.Glyph) del.getDrawable()).paint.setColor(Ui.DANGER);
      LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38));
      dl.setMarginStart(Ui.dp(this, 6));
      row.addView(del, dl);
      queueList.addView(row, Ui.lp(-1, -2, i == 0 ? 0 : Ui.dp(this, 8)));
    }
  }

  // ---------------------------------------------------------------- export tab

  private View buildExport() {
    LinearLayout c = Ui.column(this);
    LinearLayout q = Ui.card(this);
    q.addView(Ui.text(this, "الدقة", 14, Ui.TEXT));
    LinearLayout qr = Ui.row(this);
    String[] qn = {"720p", "1080p ⭐", "1440p"};
    List<TextView> qc = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      int k = i;
      TextView chip = Ui.chip(this, qn[i], null);
      chip.setOnClickListener(v -> {
        quality = k;
        for (int j = 0; j < qc.size(); j++) Ui.select(qc.get(j), j == k);
        saveEditorPrefs();
      });
      qc.add(chip);
      Ui.select(chip, i == quality);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) lp.setMarginStart(Ui.dp(this, 8));
      qr.addView(chip, lp);
    }
    q.addView(qr, Ui.lp(-1, -2, Ui.dp(this, 8)));
    q.addView(Ui.text(this, "عدد الإطارات", 14, Ui.TEXT), Ui.lp(-1, -2, Ui.dp(this, 14)));
    LinearLayout fr = Ui.row(this);
    String[] fn = {"مثل الأصل", "30 إطار"};
    List<TextView> fc = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      boolean k = i == 1;
      TextView chip = Ui.chip(this, fn[i], null);
      chip.setOnClickListener(v -> {
        cap30 = k;
        for (int j = 0; j < fc.size(); j++) Ui.select(fc.get(j), (j == 1) == k);
        saveEditorPrefs();
      });
      fc.add(chip);
      Ui.select(chip, (i == 1) == cap30);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      if (i > 0) lp.setMarginStart(Ui.dp(this, 8));
      fr.addView(chip, lp);
    }
    q.addView(fr, Ui.lp(-1, -2, Ui.dp(this, 8)));
    q.addView(Ui.text(this, "ينحفظ بالاستديو في مجلد Movies/ShortsMaker. الصوت الأصلي يبقى كما هو.", 12, Ui.MUTED), Ui.lp(-1, -2, Ui.dp(this, 14)));
    c.addView(q);
    exportButtonBig = Ui.primary(this, "صدّر الشورت", "export", this::startExport);
    c.addView(exportButtonBig, Ui.lp(-1, Ui.dp(this, 56), Ui.dp(this, 14)));
    return c;
  }

  private void saveEditorPrefs() {
    getSharedPreferences("editor", 0).edit().putLong("clipLen", clipLen).putInt("quality", quality).putBoolean("cap30", cap30).apply();
  }

  private void startExport() {
    if (info == null) return;
    if (ExportService.status.running) {
      showSheet();
      return;
    }
    List<Clip> clips = queue.isEmpty() ? Collections.singletonList(new Clip(timeline.start(), timeline.end(), -1)) : new ArrayList<>(queue);
    if (player != null) player.pause();
    style.save(this);
    int w = quality == 0 ? 720 : quality == 1 ? 1080 : 1440;
    int h = w * 16 / 9;
    Intent i =
        new Intent(this, ExportService.class)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("clips", Clip.encode(clips))
            .putExtra("name", name)
            .putExtra("cap30", cap30)
            .putExtra("w", w)
            .putExtra("h", h);
    startForegroundService(i);
    ExportService.status.running = true;
    ExportService.status.total = clips.size();
    ExportService.status.index = 0;
    ExportService.status.percent = 0;
    ExportService.status.error = null;
    ExportService.status.outputs.clear();
    ExportService.status.message = "جارٍ التجهيز…";
    showSheet();
  }

  // ---------------------------------------------------------------- export sheet

  private LinearLayout buildSheet() {
    LinearLayout s = Ui.column(this);
    s.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 26));
    GradientDrawable g = Ui.outlined(0xf2121019, Ui.STROKE, Ui.dp(this, 28), Ui.dp(this, 1));
    s.setBackground(g);
    s.setElevation(Ui.dp(this, 24));
    s.setClickable(true);
    TextView h = Ui.title(this, "التصدير", 18);
    s.addView(h);
    sheetBar = new Ui.Progress(this);
    s.addView(sheetBar, Ui.lp(-1, Ui.dp(this, 10), Ui.dp(this, 14)));
    sheetText = Ui.text(this, "", 14, Ui.MUTED);
    s.addView(sheetText, Ui.lp(-1, -2, Ui.dp(this, 10)));
    LinearLayout row = Ui.row(this);
    sheetAction = Ui.primary(this, "شغّل", "play", this::openOutput);
    sheetCancel = Ui.ghost(this, "إلغاء", null, this::cancelOrClose);
    row.addView(sheetAction, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1));
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1);
    cl.setMarginStart(Ui.dp(this, 10));
    row.addView(sheetCancel, cl);
    s.addView(row, Ui.lp(-1, -2, Ui.dp(this, 16)));
    return s;
  }

  private void showSheet() {
    if (sheet.getVisibility() != View.VISIBLE) {
      sheet.setVisibility(View.VISIBLE);
      sheet.setTranslationY(Ui.dp(this, 300));
      sheet.animate().translationY(0).setDuration(420).setInterpolator(Ui.EASE).start();
    }
    main.removeCallbacks(sheetPoll);
    main.post(sheetPoll);
  }

  private void hideSheet() {
    main.removeCallbacks(sheetPoll);
    sheet.animate().translationY(sheet.getHeight() + Ui.dp(this, 40)).setDuration(300).setInterpolator(Ui.EASE)
        .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
  }

  private final Runnable sheetPoll = new Runnable() {
    public void run() {
      ExportService.Status st = ExportService.status;
      if (st.running) {
        float overall = (st.index + st.percent / 100f) / Math.max(1, st.total);
        sheetBar.set(overall, st.percent == 0 && st.index == 0);
        sheetText.setText(st.message);
        sheetAction.setVisibility(View.GONE);
        sheetCancel.setText("إلغاء");
        main.postDelayed(this, 300);
      } else {
        sheetBar.set(st.error == null ? 1f : 0f, false);
        sheetText.setText(st.message);
        sheetText.setTextColor(st.error == null ? Ui.TEXT : Ui.DANGER);
        sheetAction.setVisibility(st.outputs.isEmpty() ? View.GONE : View.VISIBLE);
        sheetAction.setText(st.outputs.size() > 1 ? "شغّل الأول" : "شغّل");
        sheetCancel.setText("تم");
        if (st.error == null && !st.outputs.isEmpty()) {
          queue.clear();
          renderQueue();
        }
      }
    }
  };

  private void cancelOrClose() {
    if (ExportService.status.running) {
      startService(new Intent(this, ExportService.class).setAction("cancel"));
      ExportService.status.running = false;
      ExportService.status.message = "تم الإلغاء";
      main.postDelayed(sheetPoll, 150);
    } else {
      sheetText.setTextColor(Ui.MUTED);
      hideSheet();
    }
  }

  private void openOutput() {
    if (ExportService.status.outputs.isEmpty()) return;
    try {
      startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(ExportService.status.outputs.get(0), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    } catch (Exception e) {
      Ui.toast(this, "ما لقيت تطبيق يشغّل الفيديو");
    }
  }

  // ---------------------------------------------------------------- player & selection

  private void ready(Clip.Info i, String n) {
    if (isDestroyed()) return;
    info = i;
    name = n;
    timeline.setDuration(i.durationMs);
    long len = Math.min(clipLen, i.durationMs);
    timeline.setSelection(0, len);
    timeline.setActiveHandle(0);
    onSelection(0, len, true);
    syncLengthChips();
    updateHandleLabel();
    setupPlayer();
    loadThumbs();
    if (ExportService.status.running) showSheet();
  }

  private void setupPlayer() {
    player = new ExoPlayer.Builder(this).build();
    if (effectsOn) player.setVideoEffects(Collections.singletonList(new ShortFrameEffect(style)));
    player.setVideoTextureView(texture);
    player.setMediaItem(MediaItem.fromUri(uri));
    player.addListener(
        new Player.Listener() {
          @Override
          public void onIsPlayingChanged(boolean playing) {
            playIcon.setImageDrawable(new Ui.Glyph(playing ? "pause" : "play", 0xffffffff));
            playIcon.animate().alpha(playing ? 0f : 1f).scaleX(playing ? .8f : 1f).scaleY(playing ? .8f : 1f).setDuration(220).start();
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            if (effectsOn) {
              // Some devices cannot run custom effects in playback; keep editing usable.
              effectsOn = false;
              long pos = player.getCurrentPosition();
              player.release();
              setupPlayer();
              player.seekTo(pos);
              Ui.toast(EditorActivity.this, "المعاينة بدون الغباش على هذا الجهاز — التصدير يطلع طبيعي");
            } else {
              Ui.toast(EditorActivity.this, "تعذّر تشغيل الفيديو: " + error.getErrorCodeName());
            }
          }
        });
    player.prepare();
    player.seekTo(timeline.start());
    main.removeCallbacks(tick);
    main.post(tick);
  }

  private final Runnable tick = new Runnable() {
    public void run() {
      if (player == null) return;
      long pos = player.getCurrentPosition();
      if (player.isPlaying() && pos >= timeline.end()) {
        player.setSeekParameters(SeekParameters.EXACT);
        player.seekTo(timeline.start());
        pos = timeline.start();
      }
      timeline.setPlayhead(pos);
      timeLabel.setText(Ui.time(pos - timeline.start()) + " / " + Ui.time(timeline.end() - timeline.start()));
      main.postDelayed(this, 33);
    }
  };

  private void togglePlay() {
    if (player == null) return;
    if (player.isPlaying()) player.pause();
    else {
      long pos = player.getCurrentPosition();
      if (pos < timeline.start() || pos >= timeline.end() - 100) player.seekTo(timeline.start());
      player.play();
    }
  }

  private void playSelection() {
    if (player == null) return;
    player.setSeekParameters(SeekParameters.EXACT);
    player.seekTo(timeline.start());
    player.play();
  }

  private void applyClip(Clip c) {
    timeline.setSelection(c.startMs, c.endMs);
    timeline.focusSelection();
    onSelection(c.startMs, c.endMs, true);
    if (player != null) {
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(c.startMs);
    }
  }

  private void onSelection(long s, long e, boolean finished) {
    selLabel.setText(Ui.timeFine(s) + "  ←  " + Ui.timeFine(e) + "     ·     " + Ui.duration(e - s));
    selLabel.setTextDirection(View.TEXT_DIRECTION_RTL);
    if (finished) syncLengthChips();
  }

  private void setLength(long len) {
    if (info == null) return;
    clipLen = len;
    saveEditorPrefs();
    long l = Math.min(len, info.durationMs);
    long s = timeline.start();
    if (s + l > info.durationMs) s = Math.max(0, info.durationMs - l);
    timeline.setSelection(s, s + l);
    onSelection(s, s + l, true);
    if (signal != null) repick();
  }

  private void syncLengthChips() {
    long len = timeline.end() - timeline.start();
    boolean any = false;
    for (int i = 0; i < LENGTHS.length; i++) {
      boolean on = Math.abs(len - LENGTHS[i]) < 250 || (info != null && LENGTHS[i] >= info.durationMs && Math.abs(len - info.durationMs) < 250 && LENGTHS[i] == clipLen);
      Ui.select(lengthChips.get(i), on);
      any |= on;
    }
    Ui.select(customChip, !any);
  }

  private void updateHandleLabel() {
    int h = timeline.activeHandle();
    handleLabel.setText(h == 1 ? "تعديل: النهاية ⇆" : "تعديل: البداية ⇆");
  }

  private void switchHandle() {
    timeline.setActiveHandle(timeline.activeHandle() == 1 ? 0 : 1);
    updateHandleLabel();
    if (player != null) {
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(timeline.activeHandle() == 1 ? timeline.end() - 1 : timeline.start());
    }
  }

  private void nudge(int frames) {
    float fps = info == null ? 30 : info.fps;
    nudgeMs(Math.round(frames * 1000f / fps));
  }

  private void nudgeMs(long delta) {
    if (info == null) return;
    long s = timeline.start(), e = timeline.end();
    if (timeline.activeHandle() == 1) e = Math.max(s + TimelineView.MIN_MS, Math.min(Math.min(info.durationMs, s + TimelineView.MAX_MS), e + delta));
    else s = Math.max(Math.max(0, e - TimelineView.MAX_MS), Math.min(e - TimelineView.MIN_MS, s + delta));
    timeline.setSelection(s, e);
    onSelection(s, e, true);
    if (player != null) {
      player.pause();
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(timeline.activeHandle() == 1 ? Math.max(s, e - 1) : s);
    }
  }

  private boolean zoomed;

  private void toggleZoom() {
    zoomed = !zoomed;
    if (zoomed) timeline.focusSelection();
    else timeline.zoomOut();
  }

  private void loadThumbs() {
    io.execute(() -> {
      int n = 14;
      Bitmap[] thumbs = new Bitmap[n];
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(this, uri);
        for (int i = 0; i < n && !isDestroyed(); i++) {
          long t = (long) ((i + .5) * info.durationMs / n) * 1000;
          thumbs[i] = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
          if (i % 3 == 2 || i == n - 1) {
            Bitmap[] copy = thumbs.clone();
            main.post(() -> timeline.setThumbs(copy));
          }
        }
      } catch (Exception ignored) {
      } finally {
        try { r.release(); } catch (Exception ignored) {}
      }
    });
  }

  private Bitmap frameAt(long ms, int w, int h) {
    MediaMetadataRetriever r = new MediaMetadataRetriever();
    try {
      r.setDataSource(this, uri);
      return r.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h);
    } catch (Exception e) {
      return null;
    } finally {
      try { r.release(); } catch (Exception ignored) {}
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (player != null) player.pause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (sheet != null && ExportService.status.running) showSheet();
  }

  @Override
  protected void onDestroy() {
    cancelAnalysis = true;
    main.removeCallbacksAndMessages(null);
    if (player != null) player.release();
    player = null;
    io.shutdownNow();
    super.onDestroy();
  }
}
