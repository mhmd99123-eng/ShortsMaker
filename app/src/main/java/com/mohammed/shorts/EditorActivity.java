package com.mohammed.shorts;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Editor laid out like a mobile video editor: a big 9:16 preview, an auto-zooming timeline, and a
 * bottom toolbar whose tools open in a sliding panel (the preview shrinks to make room).
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class EditorActivity extends Activity {
  private static final long[] LENGTHS = {15_000, 30_000, 60_000, 90_000, 120_000, 180_000};
  private static final String[] LENGTH_LABELS = {"15ث", "30ث", "60ث", "90ث", "2د", "3د"};
  private static final String[] TOOL_NAMES = {"قص", "ذكي", "الشكل", "الاسم", "المقاطع"};
  private static final String[] TOOL_ICONS = {"scissors", "magic", "sliders", "text", "stack"};

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newFixedThreadPool(2);
  private final ExecutorService thumbsIo = Executors.newSingleThreadExecutor();
  private final AtomicInteger thumbsGen = new AtomicInteger();

  private Uri uri;
  private String name = "Video";
  private Clip.Info info;
  private ShortStyle style;
  private ExoPlayer player;
  private boolean effectsOn = true;

  // layout
  private FrameLayout root;
  private View topBar, infoRow;
  private FrameLayout previewArea, previewBox;
  private TextureView texture;
  private ImageView playIcon;
  private TextView counter, rangeText, lengthPill;
  private TimelineView timeline;
  private TimelineView.Overview overview;
  private LinearLayout panel;
  private TextView panelTitle;
  private FrameLayout panelBody;
  private LinearLayout toolbar;
  private final List<LinearLayout> toolButtons = new ArrayList<>();
  private int openTool = -1;
  private ValueAnimator panelAnim;
  private boolean fullscreen;
  private View[] tools = new View[5];

  // trim panel
  private final List<TextView> lengthChips = new ArrayList<>();
  private TextView customChip, startBox, endBox;
  private LinearLayout startCard, endCard;
  private long clipLen = 60_000;

  // floating frame nudge
  private LinearLayout bubble;
  private TextView bubbleTime;

  // smart
  private Highlights.Signal signal;
  private volatile boolean analysing, cancelAnalysis;
  private Ui.Progress analyseBar;
  private TextView analyseText, analyseButton;
  private LinearLayout results;
  private List<Clip> suggestions = new ArrayList<>();

  // queue
  private final List<Clip> queue = new ArrayList<>();
  private LinearLayout queueList;
  private TextView queueTitle, exportButtonTop;

  // look + name
  private final Map<String, LinearLayout> sliders = new HashMap<>();
  private final List<TextView> bgChips = new ArrayList<>();
  private final List<TextView> markChips = new ArrayList<>();
  private LinearLayout swatches, markControls;

  // export
  private int quality = 1;
  private boolean cap30 = false;
  private View scrim;
  private LinearLayout sheet, sheetSettings, sheetProgress;
  private TextView sheetSummary, sheetText, sheetAction, sheetCancel;
  private Ui.Progress sheetBar;

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

  private int dp(float v) {
    return Ui.dp(this, v);
  }

  // ================================================================ layout

  private void buildUi() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    LinearLayout col = Ui.column(this);
    root.addView(col, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);

    // Top bar
    LinearLayout bar = Ui.row(this);
    bar.setPadding(dp(14), dp(30), dp(14), dp(4));
    bar.addView(Ui.iconButton(this, "back", "رجوع", this::onBackPressed), new LinearLayout.LayoutParams(dp(42), dp(42)));
    TextView t = Ui.title(this, "تعديل الشورت", 17);
    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
    tl.setMarginStart(dp(12));
    bar.addView(t, tl);
    bar.addView(Ui.iconButton(this, "full", "ملء الشاشة", this::toggleFullscreen), new LinearLayout.LayoutParams(dp(42), dp(42)));
    exportButtonTop = Ui.primary(this, "تصدير", "export", this::openExportSheet);
    exportButtonTop.setTextSize(14);
    exportButtonTop.setMinHeight(dp(42));
    LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-2, dp(42));
    ep.setMarginStart(dp(8));
    bar.addView(exportButtonTop, ep);
    topBar = bar;
    col.addView(bar);

    // Preview fills whatever space is left and keeps 9:16.
    previewArea = new FrameLayout(this);
    previewBox = new FrameLayout(this);
    previewBox.setBackground(Ui.shape(Ui.SURFACE, dp(18)));
    previewBox.setClipToOutline(true);
    texture = new TextureView(this);
    previewBox.addView(texture, new FrameLayout.LayoutParams(-1, -1));
    playIcon = new ImageView(this);
    playIcon.setImageDrawable(new Ui.Glyph("play", 0xffffffff));
    playIcon.setPadding(dp(16), dp(16), dp(16), dp(16));
    playIcon.setBackground(Ui.shape(0x66000000, dp(32)));
    previewBox.addView(playIcon, new FrameLayout.LayoutParams(dp(62), dp(62), Gravity.CENTER));
    counter = Ui.text(this, "", 12, 0xffffffff);
    counter.setPadding(dp(10), dp(4), dp(10), dp(4));
    counter.setBackground(Ui.shape(0x88000000, dp(12)));
    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    cp.bottomMargin = dp(10);
    previewBox.addView(counter, cp);
    previewArea.addView(previewBox, new FrameLayout.LayoutParams(dp(200), dp(356), Gravity.CENTER));
    previewArea.addOnLayoutChangeListener((v, l, tp, r, bt, ol, ot, or, ob) -> fitPreview());
    col.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));
    setupPreviewTouch();

    // Selection info: length pill + exact range
    LinearLayout info = Ui.row(this);
    info.setPadding(dp(16), dp(8), dp(16), 0);
    lengthPill = Ui.ghost(this, "", "timer", () -> openTool(0));
    lengthPill.setTextSize(13);
    lengthPill.setMinHeight(dp(34));
    info.addView(lengthPill, new LinearLayout.LayoutParams(-2, dp(34)));
    rangeText = Ui.text(this, "", 13, Ui.MUTED);
    rangeText.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    rangeText.setTextDirection(View.TEXT_DIRECTION_LTR);
    info.addView(rangeText, new LinearLayout.LayoutParams(0, -2, 1));
    infoRow = info;
    col.addView(info);

    timeline = new TimelineView(this);
    overview = new TimelineView.Overview(this, timeline);
    col.addView(overview, Ui.lp(-1, -2, dp(6)));
    col.addView(timeline);
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

          public void handleTouched(int h, boolean released) {
            showBubble();
          }

          public void viewChanged(long s, long e) {
            requestDetailThumbs();
          }
        });
    overview.setJump(
        (ms, finished) -> {
          timeline.centerOn(ms);
          if (player != null) {
            player.setSeekParameters(finished ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
            player.seekTo(ms);
          }
        });

    // Sliding tool panel
    panel = Ui.column(this);
    panel.setBackground(Ui.outlined(Ui.SURFACE, Ui.STROKE, dp(24), dp(1)));
    LinearLayout head = Ui.row(this);
    head.setPadding(dp(18), dp(10), dp(10), 0);
    panelTitle = Ui.title(this, "", 16);
    head.addView(panelTitle, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.iconButton(this, "close", "إغلاق", this::closePanel), new LinearLayout.LayoutParams(dp(36), dp(36)));
    panel.addView(head);
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    panelBody = new FrameLayout(this);
    panelBody.setPadding(dp(16), dp(8), dp(16), dp(16));
    scroll.addView(panelBody);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0);
    pl.setMargins(dp(8), dp(8), dp(8), 0);
    col.addView(panel, pl);
    // swipe the header down to close
    head.setOnTouchListener(new View.OnTouchListener() {
      float y0;
      public boolean onTouch(View v, MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) y0 = e.getRawY();
        else if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getRawY() - y0 > dp(40)) closePanel();
        return true;
      }
    });

    // Bottom toolbar
    toolbar = Ui.row(this);
    toolbar.setPadding(dp(6), dp(8), dp(6), dp(18));
    for (int i = 0; i < TOOL_NAMES.length; i++) {
      int k = i;
      LinearLayout b = Ui.column(this);
      b.setGravity(Gravity.CENTER);
      ImageView ic = new ImageView(this);
      ic.setImageDrawable(new Ui.Glyph(TOOL_ICONS[i], Ui.MUTED));
      b.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
      TextView l = Ui.text(this, TOOL_NAMES[i], 12, Ui.MUTED);
      l.setGravity(Gravity.CENTER);
      b.addView(l, Ui.lp(-2, -2, dp(4)));
      b.setPadding(0, dp(8), 0, dp(8));
      b.setOnClickListener(v -> openTool(k));
      Ui.pressable(b);
      toolButtons.add(b);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
      lp.setMargins(dp(3), 0, dp(3), 0);
      toolbar.addView(b, lp);
    }
    col.addView(toolbar, Ui.lp(-1, -2, dp(4)));

    tools[0] = buildTrim();
    tools[1] = buildSmart();
    tools[2] = buildLook();
    tools[3] = buildMarkCard();
    tools[4] = buildClips();

    buildBubble();
    buildSheet();
    renderQueue();

    for (int i = 0; i < col.getChildCount(); i++) Ui.enter(col.getChildAt(i), i);
  }

  private void fitPreview() {
    int aw = previewArea.getWidth() - dp(24), ah = previewArea.getHeight() - dp(fullscreen ? 0 : 8);
    if (aw <= 0 || ah <= 0) return;
    int h = Math.min(ah, aw * 16 / 9), w = h * 9 / 16;
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewBox.getLayoutParams();
    if (lp.width != w || lp.height != h) {
      lp.width = w;
      lp.height = h;
      previewBox.setLayoutParams(lp);
    }
  }

  // ---------------------------------------------------------------- panel

  private void openTool(int i) {
    if (fullscreen) toggleFullscreen();
    if (openTool == i) {
      closePanel();
      return;
    }
    boolean wasOpen = openTool >= 0;
    openTool = i;
    panelTitle.setText(i == 1 ? "✨ أفضل اللقطات" : i == 3 ? "اسم القناة" : TOOL_NAMES[i]);
    panelBody.removeAllViews();
    View v = tools[i];
    panelBody.addView(v);
    v.setAlpha(0f);
    v.setTranslationY(dp(14));
    v.animate().alpha(1f).translationY(0).setDuration(280).setInterpolator(Ui.EASE).start();
    for (int k = 0; k < toolButtons.size(); k++) styleTool(k, k == i);
    int target = (int) (getResources().getDisplayMetrics().heightPixels * (i == 0 ? .30f : .40f));
    animatePanel(target, wasOpen ? 220 : 380);
    hideBubble();
  }

  private void closePanel() {
    if (openTool < 0) return;
    openTool = -1;
    for (int k = 0; k < toolButtons.size(); k++) styleTool(k, false);
    animatePanel(0, 320);
  }

  private void styleTool(int k, boolean on) {
    LinearLayout b = toolButtons.get(k);
    ImageView ic = (ImageView) b.getChildAt(0);
    TextView l = (TextView) b.getChildAt(1);
    ((Ui.Glyph) ic.getDrawable()).paint.setColor(on ? 0xffffffff : Ui.MUTED);
    ic.invalidate();
    l.setTextColor(on ? 0xffffffff : Ui.MUTED);
    b.setBackground(on ? Ui.gradient(dp(18)) : null);
  }

  private void animatePanel(int target, long ms) {
    if (panelAnim != null) panelAnim.cancel();
    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
    panelAnim = ValueAnimator.ofInt(lp.height, target);
    panelAnim.setDuration(ms);
    panelAnim.setInterpolator(Ui.EASE);
    panelAnim.addUpdateListener(a -> {
      lp.height = (int) a.getAnimatedValue();
      panel.setLayoutParams(lp);
    });
    panelAnim.start();
  }

  private void toggleFullscreen() {
    fullscreen = !fullscreen;
    if (fullscreen && openTool >= 0) {
      openTool = -1;
      for (int k = 0; k < toolButtons.size(); k++) styleTool(k, false);
      LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
      lp.height = 0;
      panel.setLayoutParams(lp);
    }
    int vis = fullscreen ? View.GONE : View.VISIBLE;
    for (View v : new View[] {topBar, infoRow, overview, timeline, toolbar}) v.setVisibility(vis);
    hideBubble();
    if (fullscreen) Ui.toast(this, "ضغطتين على الفيديو للرجوع");
  }

  // ---------------------------------------------------------------- preview touch

  private boolean draggingMark, pinching;
  private float touchX, touchY, startMarkX, startMarkY;

  /** Tap: play/pause · double tap: full screen · drag the name: move it · pinch: resize it. */
  private void setupPreviewTouch() {
    GestureDetector taps =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              public boolean onDown(MotionEvent e) {
                return true;
              }

              public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!draggingMark) togglePlay();
                return true;
              }

              public boolean onDoubleTap(MotionEvent e) {
                toggleFullscreen();
                return true;
              }
            });
    ScaleGestureDetector pinch =
        new ScaleGestureDetector(
            this,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              public boolean onScaleBegin(ScaleGestureDetector g) {
                if (!style.markOn) return false;
                pinching = true;
                return true;
              }

              public boolean onScale(ScaleGestureDetector g) {
                style.markSize = Math.max(.02f, Math.min(.11f, style.markSize * g.getScaleFactor()));
                if (sliders.containsKey("markSize")) setSlider("markSize", style.markSize);
                refreshPreview();
                return true;
              }

              public void onScaleEnd(ScaleGestureDetector g) {
                style.save(EditorActivity.this);
              }
            });
    previewBox.setOnTouchListener(
        (v, e) -> {
          pinch.onTouchEvent(e);
          if (!pinching) taps.onTouchEvent(e);
          float w = v.getWidth(), h = v.getHeight();
          switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
              v.getParent().requestDisallowInterceptTouchEvent(true);
              touchX = e.getX();
              touchY = e.getY();
              pinching = false;
              startMarkX = style.markX;
              startMarkY = style.markY;
              draggingMark = style.markOn && hitsMark(e.getX(), e.getY(), w, h);
              if (draggingMark) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
              break;
            case MotionEvent.ACTION_MOVE:
              if (draggingMark && !pinching && e.getPointerCount() == 1) {
                float nx = startMarkX + (e.getX() - touchX) / w;
                float ny = startMarkY + (e.getY() - touchY) / h;
                if (Math.abs(nx - .5f) < .025f) nx = .5f;
                style.markX = Math.max(.03f, Math.min(.97f, nx));
                style.markY = Math.max(.02f, Math.min(.98f, ny));
                refreshPreview();
              }
              break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              if (draggingMark || pinching) style.save(this);
              main.postDelayed(() -> draggingMark = false, 350);
              break;
          }
          return true;
        });
  }

  private boolean hitsMark(float x, float y, float w, float h) {
    String t = style.markText == null ? "" : style.markText.trim();
    float textH = style.markSize * w;
    float halfW = Math.max(textH, textH * .33f * t.length()) + dp(22);
    float halfH = textH + dp(22);
    return Math.abs(x - style.markX * w) < halfW && Math.abs(y - style.markY * h) < halfH;
  }

  // ---------------------------------------------------------------- floating frame nudge

  private final Runnable hideBubbleLater = this::hideBubble;

  private void buildBubble() {
    bubble = Ui.row(this);
    bubble.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    bubble.setPadding(dp(4), dp(4), dp(4), dp(4));
    bubble.setBackground(Ui.outlined(0xf21a1726, Ui.ACCENT2, dp(22), dp(1)));
    bubble.setElevation(dp(12));
    ImageView l = Ui.iconButton(this, "left", "رجوع إطار", () -> nudge(-1));
    l.setOnLongClickListener(v -> { nudgeMs(-1000); return true; });
    ImageView r = Ui.iconButton(this, "right", "تقديم إطار", () -> nudge(1));
    r.setOnLongClickListener(v -> { nudgeMs(1000); return true; });
    bubbleTime = Ui.text(this, "", 13, Ui.TEXT);
    bubbleTime.setGravity(Gravity.CENTER);
    bubbleTime.setTextDirection(View.TEXT_DIRECTION_LTR);
    bubble.addView(l, new LinearLayout.LayoutParams(dp(36), dp(36)));
    bubble.addView(bubbleTime, new LinearLayout.LayoutParams(dp(74), -2));
    bubble.addView(r, new LinearLayout.LayoutParams(dp(36), dp(36)));
    bubble.setVisibility(View.GONE);
    root.addView(bubble, new FrameLayout.LayoutParams(-2, -2));
  }

  private void showBubble() {
    if (fullscreen) return;
    int h = timeline.activeHandle();
    bubbleTime.setText(Ui.timeFine(h == 1 ? timeline.end() : timeline.start()));
    int[] tl = new int[2], rl = new int[2];
    timeline.getLocationInWindow(tl);
    root.getLocationInWindow(rl);
    bubble.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
    float bw = bubble.getMeasuredWidth(), bh = bubble.getMeasuredHeight();
    float x = tl[0] - rl[0] + timeline.handleX(h) - bw / 2;
    x = Math.max(dp(8), Math.min(root.getWidth() - bw - dp(8), x));
    float y = tl[1] - rl[1] - bh - dp(2);
    bubble.setX(x);
    bubble.setY(y);
    if (bubble.getVisibility() != View.VISIBLE) {
      bubble.setVisibility(View.VISIBLE);
      bubble.setAlpha(0f);
      bubble.setScaleX(.85f);
      bubble.setScaleY(.85f);
      bubble.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(Ui.EASE).start();
    }
    main.removeCallbacks(hideBubbleLater);
    main.postDelayed(hideBubbleLater, 3500);
  }

  private void hideBubble() {
    main.removeCallbacks(hideBubbleLater);
    if (bubble == null || bubble.getVisibility() != View.VISIBLE) return;
    bubble.animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(160).withEndAction(() -> bubble.setVisibility(View.GONE)).start();
  }

  // ---------------------------------------------------------------- trim tool

  private View buildTrim() {
    LinearLayout c = Ui.column(this);
    c.addView(Ui.text(this, "المدة", 13, Ui.MUTED));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout chips = Ui.row(this);
    for (int i = 0; i < LENGTHS.length; i++) {
      long len = LENGTHS[i];
      TextView chip = Ui.chip(this, LENGTH_LABELS[i], () -> setLength(len));
      lengthChips.add(chip);
      LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
      cl.setMarginEnd(dp(8));
      chips.addView(chip, cl);
    }
    customChip = Ui.chip(this, "حر", () -> Ui.toast(this, "اسحب أطراف الشريط لأي طول لين 3 دقايق"));
    chips.addView(customChip);
    hs.addView(chips);
    c.addView(hs, Ui.lp(-1, -2, dp(6)));

    LinearLayout edges = Ui.row(this);
    startCard = edgeCard("البداية", 0);
    endCard = edgeCard("النهاية", 1);
    startBox = (TextView) startCard.getTag();
    endBox = (TextView) endCard.getTag();
    edges.addView(startCard, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, -2, 1);
    el.setMarginStart(dp(10));
    edges.addView(endCard, el);
    c.addView(edges, Ui.lp(-1, -2, dp(14)));

    LinearLayout actions = Ui.row(this);
    actions.addView(Ui.ghost(this, "شغّل الجزء", "play", this::playSelection), new LinearLayout.LayoutParams(0, dp(46), 1));
    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(0, dp(46), 1);
    al.setMarginStart(dp(10));
    actions.addView(Ui.ghost(this, "أضف للمقاطع", "plus", () -> addToQueue(new Clip(timeline.start(), timeline.end(), -1), true)), al);
    c.addView(actions, Ui.lp(-1, -2, dp(14)));
    return c;
  }

  /** Card showing one edge's exact time with frame buttons. Tag holds the time TextView. */
  private LinearLayout edgeCard(String label, int handle) {
    LinearLayout card = Ui.card(this);
    card.setPadding(dp(10), dp(10), dp(10), dp(10));
    TextView l = Ui.text(this, label, 12, Ui.MUTED);
    l.setGravity(Gravity.CENTER);
    card.addView(l, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout row = Ui.row(this);
    row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
    ImageView minus = Ui.iconButton(this, "left", "رجوع إطار", () -> { selectHandle(handle); nudge(-1); });
    minus.setOnLongClickListener(v -> { selectHandle(handle); nudgeMs(-1000); return true; });
    ImageView plus = Ui.iconButton(this, "right", "تقديم إطار", () -> { selectHandle(handle); nudge(1); });
    plus.setOnLongClickListener(v -> { selectHandle(handle); nudgeMs(1000); return true; });
    TextView time = Ui.title(this, "0:00.0", 17);
    time.setGravity(Gravity.CENTER);
    time.setTextDirection(View.TEXT_DIRECTION_LTR);
    row.addView(minus, new LinearLayout.LayoutParams(dp(34), dp(34)));
    row.addView(time, new LinearLayout.LayoutParams(0, -2, 1));
    row.addView(plus, new LinearLayout.LayoutParams(dp(34), dp(34)));
    card.addView(row, Ui.lp(-1, -2, dp(6)));
    card.setTag(time);
    card.setOnClickListener(v -> selectHandle(handle));
    return card;
  }

  private void selectHandle(int h) {
    timeline.setActiveHandle(h);
    syncEdgeCards();
    if (player != null) {
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(h == 1 ? Math.max(timeline.start(), timeline.end() - 1) : timeline.start());
    }
  }

  private void syncEdgeCards() {
    if (startCard == null) return;
    int h = timeline.activeHandle();
    startCard.setBackground(Ui.outlined(Ui.CARD, h == 0 ? Ui.ACCENT2 : Ui.STROKE, dp(20), dp(h == 0 ? 2 : 1)));
    endCard.setBackground(Ui.outlined(Ui.CARD, h == 1 ? Ui.ACCENT2 : Ui.STROKE, dp(20), dp(h == 1 ? 2 : 1)));
  }

  // ---------------------------------------------------------------- smart tool

  private View buildSmart() {
    LinearLayout c = Ui.column(this);
    c.addView(Ui.text(this, "يحلل الصوت والحماس والحركة وتغيّر المشاهد، ويقترح أقوى المقاطع بالطول اللي اخترته، ويبدأ ويوقف عند سكتة عشان ما يقطع الكلام.", 13, Ui.MUTED));
    analyseButton = Ui.primary(this, "ابحث عن أفضل اللقطات", "magic", this::analyse);
    c.addView(analyseButton, Ui.lp(-1, dp(50), dp(12)));
    analyseBar = new Ui.Progress(this);
    analyseBar.setVisibility(View.GONE);
    c.addView(analyseBar, Ui.lp(-1, dp(8), dp(12)));
    analyseText = Ui.text(this, "", 12, Ui.MUTED);
    analyseText.setVisibility(View.GONE);
    c.addView(analyseText, Ui.lp(-1, -2, dp(6)));
    results = Ui.column(this);
    c.addView(results, Ui.lp(-1, -2, dp(10)));
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
                              String left = "";
                              long el = now - started;
                              if (f > .06f) {
                                long sec = (long) (el * (1 - f) / f / 1000);
                                left = sec < 60 ? " · باقي " + Math.max(1, sec) + " ث" : " · باقي " + (sec / 60 + 1) + " د";
                              }
                              analyseText.setText("أسمع الفيديو وأشوفه… " + Math.round(f * 100) + "٪" + left);
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
                  analyseText.setText(s.hasAudio ? "تم ✓ — هذي أقوى المقاطع" : "تم ✓ — الفيديو بدون صوت، اعتمدت على الصورة");
                  analyseButton.setText("حدّث الاقتراحات");
                  timeline.setHeat(Highlights.normalise(Highlights.interest(s), 5, 98));
                  repick();
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  analysing = false;
                  analyseBar.setVisibility(View.GONE);
                  analyseButton.setText("ابحث عن أفضل اللقطات");
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
    head.addView(Ui.title(this, "الاقتراحات · " + Ui.duration(clipLen), 15), new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.ghost(this, "أضف الكل", "plus", () -> {
      for (Clip c : suggestions) addToQueue(c, false);
      Ui.toast(this, "انضافت للمقاطع ✓");
    }));
    results.addView(head);
    for (int i = 0; i < suggestions.size(); i++) {
      View card = suggestionCard(i, suggestions.get(i));
      results.addView(card, Ui.lp(-1, -2, dp(10)));
      Ui.enter(card, i);
    }
    applyClip(suggestions.get(0));
  }

  private View suggestionCard(int index, Clip c) {
    LinearLayout card = Ui.card(this);
    card.setOrientation(LinearLayout.HORIZONTAL);
    card.setGravity(Gravity.CENTER_VERTICAL);
    ImageView thumb = new ImageView(this);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    thumb.setBackground(Ui.shape(Ui.BG, dp(10)));
    thumb.setClipToOutline(true);
    card.addView(thumb, new LinearLayout.LayoutParams(dp(92), dp(52)));
    io.execute(() -> {
      Bitmap b = frameAt(c.startMs + Math.min(2000, c.length() / 3), 240, 136);
      main.post(() -> { if (b != null) thumb.setImageBitmap(b); });
    });
    LinearLayout texts = Ui.column(this);
    texts.setPadding(dp(12), 0, dp(8), 0);
    TextView t = Ui.text(this, "اللقطة " + (index + 1) + (index == 0 ? " · الأقوى 🔥" : ""), 14, Ui.TEXT);
    Ui.bold(t);
    texts.addView(t);
    TextView when = Ui.text(this, Ui.time(c.startMs) + " → " + Ui.time(c.endMs) + "   " + Ui.duration(c.length()), 12, Ui.MUTED);
    when.setTextDirection(View.TEXT_DIRECTION_LTR);
    when.setGravity(Gravity.START);
    texts.addView(when);
    Ui.Progress strength = new Ui.Progress(this);
    texts.addView(strength, Ui.lp(-1, dp(5), dp(6)));
    strength.post(() -> strength.set(Math.max(.08f, c.score), false));
    card.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(Ui.iconButton(this, "plus", "أضف للتصدير", () -> addToQueue(c, true)), new LinearLayout.LayoutParams(dp(40), dp(40)));
    card.setOnClickListener(v -> { applyClip(c); playSelection(); });
    Ui.pressable(card);
    return card;
  }

  // ---------------------------------------------------------------- look tool

  private View buildLook() {
    LinearLayout c = Ui.column(this);
    c.addView(Ui.text(this, "قوالب سريعة", 13, Ui.MUTED));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout presets = Ui.row(this);
    String[] pn = {"افتراضي", "سينمائي", "ناعم", "حيوي", "إطار عائم", "ملء الشاشة"};
    for (int i = 0; i < pn.length; i++) {
      int k = i;
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(dp(8));
      presets.addView(Ui.chip(this, pn[i], () -> applyPreset(k)), lp);
    }
    hs.addView(presets);
    c.addView(hs, Ui.lp(-1, -2, dp(6)));

    LinearLayout bg = Ui.card(this);
    LinearLayout modes = Ui.row(this);
    modes.addView(Ui.text(this, "الخلفية", 14, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
    String[] bn = {"غباش", "لون"};
    for (int i = 0; i < 2; i++) {
      int k = i;
      TextView chip = Ui.chip(this, bn[i], () -> setBgMode(k));
      bgChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginStart(dp(8));
      modes.addView(chip, lp);
    }
    bg.addView(modes);
    swatches = Ui.row(this);
    int[] colors = {0xff101018, 0xff000000, 0xffffffff, 0xff1e1b4b, 0xff0f766e, 0xff7c2d12, 0xff831843, 0xfffacc15};
    for (int col : colors) {
      View s = new View(this);
      s.setBackground(Ui.outlined(col, Ui.STROKE, dp(16), dp(2)));
      s.setOnClickListener(v -> { style.bgColor = col; afterLook(true); });
      Ui.pressable(s);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
      lp.setMarginEnd(dp(8));
      swatches.addView(s, lp);
    }
    bg.addView(swatches, Ui.lp(-1, -2, dp(10)));
    bg.addView(slider("blur", "قوة الغباش", 0, 1, style.blur, v -> pct(v), v -> style.blur = v));
    bg.addView(slider("dim", "تغميق الخلفية", 0, .85f, style.dim, v -> pct(v / .85f), v -> style.dim = v));
    bg.addView(slider("sat", "حيوية الألوان", .5f, 1.8f, style.saturation, v -> pct((v - .5f) / 1.3f), v -> style.saturation = v));
    c.addView(bg, Ui.lp(-1, -2, dp(12)));

    LinearLayout fg = Ui.card(this);
    fg.addView(Ui.text(this, "الفيديو بالنص", 14, Ui.TEXT));
    fg.addView(slider("zoom", "الحجم", .6f, 2.2f, style.zoom, v -> Math.round(v * 100) + "٪", v -> style.zoom = v));
    fg.addView(slider("y", "الموضع (تحت ← فوق)", -1, 1, style.offsetY, v -> signed(v), v -> style.offsetY = v));
    fg.addView(slider("x", "يمين/يسار (عند التكبير)", -1, 1, style.offsetX, v -> signed(v), v -> style.offsetX = v));
    fg.addView(slider("radius", "انحناء الزوايا", 0, .08f, style.radius, v -> pct(v / .08f), v -> style.radius = v));
    fg.addView(slider("shadow", "الظل", 0, 1, style.shadow, v -> pct(v), v -> style.shadow = v));
    c.addView(fg, Ui.lp(-1, -2, dp(12)));
    c.addView(Ui.ghost(this, "رجّع الشكل الافتراضي", "reset", () -> applyPreset(0)), Ui.lp(-1, dp(46), dp(12)));
    syncBgChips();
    return c;
  }

  interface Setter {
    void set(float v);
  }

  private static final int R_KEY = 0x7f0b0001;

  private LinearLayout slider(String key, String label, float min, float max, float value, java.util.function.Function<Float, String> fmt, Setter s) {
    LinearLayout v = Ui.slider(this, label, min, max, value, fmt, (x, fromUser) -> { s.set(x); afterLook(fromUser); });
    v.setTag(R_KEY, new float[] {min, max});
    sliders.put(key, v);
    return v;
  }

  private static String pct(float v) {
    return Math.round(Math.max(0, Math.min(1, v)) * 100) + "٪";
  }

  private static String signed(float v) {
    int n = Math.round(v * 100);
    return n == 0 ? "الوسط" : (n > 0 ? "+" : "") + n;
  }

  private void setSlider(String key, float value) {
    LinearLayout v = sliders.get(key);
    if (v == null) return;
    float[] r = (float[]) v.getTag(R_KEY);
    Ui.setSlider(v, r[0], r[1], value);
  }

  private void applyPreset(int k) {
    ShortStyle d = new ShortStyle();
    float blur = d.blur, dim = d.dim, sat = d.saturation, zoom = d.zoom, radius = d.radius, shadow = d.shadow;
    switch (k) {
      case 1: blur = .8f; dim = .5f; sat = 1.05f; shadow = .7f; break;
      case 2: blur = .95f; dim = .12f; sat = 1.25f; shadow = .3f; break;
      case 3: blur = .6f; dim = .08f; sat = 1.6f; shadow = .5f; break;
      case 4: blur = .7f; dim = .35f; sat = 1.2f; zoom = .9f; radius = .045f; shadow = .9f; break;
      case 5: blur = .5f; dim = .2f; sat = 1.1f; zoom = 1.7f; shadow = .4f; break;
      default: break;
    }
    style.bgMode = ShortStyle.BG_BLUR;
    setSlider("blur", blur);
    setSlider("dim", dim);
    setSlider("sat", sat);
    setSlider("zoom", zoom);
    setSlider("y", 0);
    setSlider("x", 0);
    setSlider("radius", radius);
    setSlider("shadow", shadow);
    style.blur = blur; style.dim = dim; style.saturation = sat; style.zoom = zoom;
    style.offsetY = 0; style.offsetX = 0; style.radius = radius; style.shadow = shadow;
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
    if (swatches != null) swatches.setAlpha(style.bgMode == ShortStyle.BG_COLOR ? 1f : .35f);
    LinearLayout blur = sliders.get("blur");
    if (blur != null) blur.setAlpha(style.bgMode == ShortStyle.BG_BLUR ? 1f : .35f);
  }

  private void afterLook(boolean save) {
    refreshPreview();
    if (save) style.save(this);
  }

  private final Runnable refresh = () -> {
    if (player != null && !player.isPlaying()) {
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(player.getCurrentPosition());
    }
  };

  /** A paused player shows no new frames; re-seek so the change is visible right away. */
  private void refreshPreview() {
    main.removeCallbacks(refresh);
    main.postDelayed(refresh, 60);
  }

  // ---------------------------------------------------------------- name tool

  private View buildMarkCard() {
    LinearLayout c = Ui.column(this);
    LinearLayout head = Ui.row(this);
    head.addView(Ui.text(this, "يظهر على الشورت", 14, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
    String[] labels = {"إظهار", "إخفاء"};
    for (int i = 0; i < 2; i++) {
      boolean on = i == 0;
      TextView chip = Ui.chip(this, labels[i], () -> { style.markOn = on; syncMark(); afterLook(true); });
      markChips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginStart(dp(8));
      head.addView(chip, lp);
    }
    c.addView(head);
    markControls = Ui.column(this);
    EditText text = new EditText(this);
    text.setSingleLine(true);
    text.setText(style.markText);
    text.setHint("اكتب الاسم");
    text.setTextColor(Ui.TEXT);
    text.setHintTextColor(Ui.MUTED);
    text.setTextSize(15);
    text.setPadding(dp(14), 0, dp(14), 0);
    text.setBackground(Ui.outlined(Ui.CARD, Ui.STROKE, dp(14), dp(1)));
    text.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence a, int b, int c2, int d) {}
      public void onTextChanged(CharSequence a, int b, int c2, int d) {}
      public void afterTextChanged(android.text.Editable e) { style.markText = e.toString(); afterLook(true); }
    });
    markControls.addView(text, Ui.lp(-1, dp(46), dp(10)));
    markControls.addView(slider("markSize", "الحجم", .02f, .11f, style.markSize, v -> Math.round(v / .042f * 100) + "٪", v -> style.markSize = v));
    markControls.addView(slider("markOpacity", "الوضوح", .2f, 1f, style.markOpacity, v -> pct(v), v -> style.markOpacity = v));
    markControls.addView(Ui.text(this, "أماكن جاهزة", 13, Ui.MUTED), Ui.lp(-1, -2, dp(8)));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout spots = Ui.row(this);
    String[] names = {"فوق يسار", "فوق الوسط", "فوق يمين", "تحت الوسط"};
    float[][] pos = {{.2f, .075f}, {.5f, .075f}, {.8f, .075f}, {.5f, .93f}};
    for (int i = 0; i < names.length; i++) {
      float[] p = pos[i];
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(dp(8));
      spots.addView(Ui.chip(this, names[i], () -> { style.markX = p[0]; style.markY = p[1]; afterLook(true); }), lp);
    }
    hs.addView(spots);
    markControls.addView(hs, Ui.lp(-1, -2, dp(6)));
    markControls.addView(Ui.text(this, "👆 اسحب الاسم بإصبعك فوق المعاينة، وكبّره أو صغّره بإصبعين.", 12, Ui.MUTED), Ui.lp(-1, -2, dp(12)));
    c.addView(markControls);
    syncMark();
    return c;
  }

  private void syncMark() {
    for (int i = 0; i < markChips.size(); i++) Ui.select(markChips.get(i), (i == 0) == style.markOn);
    if (markControls != null) markControls.setAlpha(style.markOn ? 1f : .35f);
  }

  // ---------------------------------------------------------------- clips tool

  private View buildClips() {
    LinearLayout c = Ui.column(this);
    LinearLayout head = Ui.row(this);
    queueTitle = Ui.text(this, "", 13, Ui.MUTED);
    head.addView(queueTitle, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(Ui.ghost(this, "أضف الجزء الحالي", "plus", () -> addToQueue(new Clip(timeline.start(), timeline.end(), -1), true)));
    c.addView(head);
    queueList = Ui.column(this);
    c.addView(queueList, Ui.lp(-1, -2, dp(10)));
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
    if (announce) Ui.toast(this, "انضاف للمقاطع ✓ (" + queue.size() + ")");
  }

  private void renderQueue() {
    if (queueTitle == null) return;
    queueTitle.setText("كل مقطع يطلع شورت مستقل، وتصدّرها كلها مرة وحدة.");
    TextView label = (TextView) toolButtons.get(4).getChildAt(1);
    label.setText(queue.isEmpty() ? "المقاطع" : "المقاطع " + queue.size());
    timeline.setQueued(queue);
    exportButtonTop.setText(queue.size() > 1 ? "تصدير " + queue.size() : "تصدير");
    queueList.removeAllViews();
    if (queue.isEmpty()) {
      TextView e = Ui.text(this, "ما فيه مقاطع مضافة.\nإذا صدّرت الحين بيطلع الجزء المحدد بالشريط.", 13, Ui.MUTED);
      e.setGravity(Gravity.CENTER);
      e.setPadding(dp(12), dp(22), dp(12), dp(22));
      e.setBackground(Ui.outlined(Ui.CARD, Ui.STROKE, dp(18), dp(1)));
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
      num.setBackground(Ui.gradient(dp(14)));
      row.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));
      TextView t = Ui.text(this, Ui.time(c.startMs) + " → " + Ui.time(c.endMs) + "   " + Ui.duration(c.length()), 14, Ui.TEXT);
      t.setTextDirection(View.TEXT_DIRECTION_LTR);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
      tl.setMarginStart(dp(12));
      row.addView(t, tl);
      row.addView(Ui.iconButton(this, "play", "عاين", () -> { applyClip(c); playSelection(); }), new LinearLayout.LayoutParams(dp(38), dp(38)));
      ImageView del = Ui.iconButton(this, "trash", "احذف", () -> { queue.remove(c); renderQueue(); });
      ((Ui.Glyph) del.getDrawable()).paint.setColor(Ui.DANGER);
      LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(38), dp(38));
      dl.setMarginStart(dp(6));
      row.addView(del, dl);
      queueList.addView(row, Ui.lp(-1, -2, i == 0 ? 0 : dp(8)));
    }
  }

  // ---------------------------------------------------------------- export sheet

  private void buildSheet() {
    scrim = new View(this);
    scrim.setBackgroundColor(0x99000000);
    scrim.setVisibility(View.GONE);
    scrim.setOnClickListener(v -> { if (!ExportService.status.running) hideSheet(); });
    root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

    sheet = Ui.column(this);
    sheet.setPadding(dp(20), dp(18), dp(20), dp(28));
    GradientDrawable g = Ui.outlined(0xff121019, Ui.STROKE, dp(28), dp(1));
    sheet.setBackground(g);
    sheet.setElevation(dp(24));
    sheet.setClickable(true);
    View grip = new View(this);
    grip.setBackground(Ui.shape(Ui.STROKE, dp(3)));
    LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(dp(44), dp(5));
    gl.gravity = Gravity.CENTER_HORIZONTAL;
    sheet.addView(grip, gl);
    sheet.addView(Ui.title(this, "التصدير", 19), Ui.lp(-2, -2, dp(12)));
    sheetSummary = Ui.text(this, "", 13, Ui.MUTED);
    sheet.addView(sheetSummary, Ui.lp(-1, -2, dp(4)));

    sheetSettings = Ui.column(this);
    sheetSettings.addView(Ui.text(this, "الدقة", 14, Ui.TEXT), Ui.lp(-1, -2, dp(14)));
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
      if (i > 0) lp.setMarginStart(dp(8));
      qr.addView(chip, lp);
    }
    sheetSettings.addView(qr, Ui.lp(-1, -2, dp(8)));
    sheetSettings.addView(Ui.text(this, "عدد الإطارات", 14, Ui.TEXT), Ui.lp(-1, -2, dp(14)));
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
      if (i > 0) lp.setMarginStart(dp(8));
      fr.addView(chip, lp);
    }
    sheetSettings.addView(fr, Ui.lp(-1, -2, dp(8)));
    sheetSettings.addView(Ui.text(this, "ينحفظ بالاستديو في Movies/ShortsMaker والصوت الأصلي كما هو.", 12, Ui.MUTED), Ui.lp(-1, -2, dp(12)));
    sheetSettings.addView(Ui.primary(this, "صدّر الحين", "export", this::startExport), Ui.lp(-1, dp(54), dp(16)));
    sheet.addView(sheetSettings);

    sheetProgress = Ui.column(this);
    sheetBar = new Ui.Progress(this);
    sheetProgress.addView(sheetBar, Ui.lp(-1, dp(10), dp(16)));
    sheetText = Ui.text(this, "", 14, Ui.MUTED);
    sheetProgress.addView(sheetText, Ui.lp(-1, -2, dp(10)));
    LinearLayout row = Ui.row(this);
    sheetAction = Ui.primary(this, "شغّل", "play", this::openOutput);
    sheetCancel = Ui.ghost(this, "إلغاء", null, this::cancelOrClose);
    row.addView(sheetAction, new LinearLayout.LayoutParams(0, dp(50), 1));
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, dp(50), 1);
    cl.setMarginStart(dp(10));
    row.addView(sheetCancel, cl);
    sheetProgress.addView(row, Ui.lp(-1, -2, dp(16)));
    sheet.addView(sheetProgress);

    root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    sheet.setVisibility(View.GONE);
  }

  private List<Clip> clipsToExport() {
    return queue.isEmpty() ? Collections.singletonList(new Clip(timeline.start(), timeline.end(), -1)) : new ArrayList<>(queue);
  }

  private void openExportSheet() {
    if (info == null) return;
    if (player != null) player.pause();
    hideBubble();
    if (ExportService.status.running) {
      showSheet(true);
      return;
    }
    List<Clip> clips = clipsToExport();
    long total = 0;
    for (Clip c : clips) total += c.length();
    sheetSummary.setText(clips.size() == 1
        ? "شورت واحد · " + Ui.duration(total)
        : clips.size() + " شورتات · المجموع " + Ui.duration(total));
    showSheet(false);
  }

  private void showSheet(boolean progress) {
    sheetSettings.setVisibility(progress ? View.GONE : View.VISIBLE);
    sheetProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
    if (sheet.getVisibility() != View.VISIBLE) {
      scrim.setVisibility(View.VISIBLE);
      scrim.setAlpha(0f);
      scrim.animate().alpha(1f).setDuration(250).start();
      sheet.setVisibility(View.VISIBLE);
      sheet.setTranslationY(dp(420));
      sheet.animate().translationY(0).setDuration(420).setInterpolator(Ui.EASE).start();
    }
    main.removeCallbacks(sheetPoll);
    if (progress) main.post(sheetPoll);
  }

  private void hideSheet() {
    main.removeCallbacks(sheetPoll);
    scrim.animate().alpha(0f).setDuration(220).withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    sheet.animate().translationY(sheet.getHeight() + dp(40)).setDuration(300).setInterpolator(Ui.EASE)
        .withEndAction(() -> sheet.setVisibility(View.GONE)).start();
  }

  private void startExport() {
    if (info == null || ExportService.status.running) return;
    List<Clip> clips = clipsToExport();
    style.save(this);
    int w = quality == 0 ? 720 : quality == 1 ? 1080 : 1440;
    Intent i =
        new Intent(this, ExportService.class)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra("clips", Clip.encode(clips))
            .putExtra("name", name)
            .putExtra("cap30", cap30)
            .putExtra("w", w)
            .putExtra("h", w * 16 / 9);
    startForegroundService(i);
    ExportService.Status st = ExportService.status;
    st.running = true;
    st.total = clips.size();
    st.index = 0;
    st.percent = 0;
    st.error = null;
    st.outputs.clear();
    st.message = "جارٍ التجهيز…";
    sheetText.setTextColor(Ui.MUTED);
    showSheet(true);
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
        if (st.error == null && !st.outputs.isEmpty() && !queue.isEmpty()) {
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

  private void saveEditorPrefs() {
    getSharedPreferences("editor", 0).edit().putLong("clipLen", clipLen).putInt("quality", quality).putBoolean("cap30", cap30).apply();
  }

  // ================================================================ player & selection

  private void ready(Clip.Info i, String n) {
    if (isDestroyed()) return;
    info = i;
    name = n;
    timeline.setDuration(i.durationMs);
    long len = Math.min(clipLen, i.durationMs);
    timeline.setSelection(0, len);
    timeline.focusSelection();
    onSelection(0, len, true);
    syncEdgeCards();
    setupPlayer();
    loadThumbs();
    if (ExportService.status.running) showSheet(true);
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
      overview.invalidate();
      long len = timeline.end() - timeline.start();
      long in = Math.max(0, Math.min(len, pos - timeline.start()));
      counter.setText(Ui.time(in) + " من " + Ui.time(len));
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
    rangeText.setText(Ui.timeFine(s) + "  →  " + Ui.timeFine(e));
    lengthPill.setText("المدة: " + Ui.duration(e - s));
    if (startBox != null) {
      startBox.setText(Ui.timeFine(s));
      endBox.setText(Ui.timeFine(e));
    }
    if (bubble != null && bubble.getVisibility() == View.VISIBLE) showBubble();
    if (finished) syncLengthChips();
    overview.invalidate();
  }

  private void setLength(long len) {
    if (info == null) return;
    clipLen = len;
    saveEditorPrefs();
    long l = Math.min(len, info.durationMs);
    long s = timeline.start();
    if (s + l > info.durationMs) s = Math.max(0, info.durationMs - l);
    timeline.setSelection(s, s + l);
    timeline.focusSelection();
    onSelection(s, s + l, true);
    if (signal != null) repick();
  }

  private void syncLengthChips() {
    if (customChip == null) return;
    long len = timeline.end() - timeline.start();
    boolean any = false;
    for (int i = 0; i < LENGTHS.length; i++) {
      boolean on = Math.abs(len - LENGTHS[i]) < 250;
      Ui.select(lengthChips.get(i), on);
      any |= on;
    }
    Ui.select(customChip, !any);
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
    syncEdgeCards();
    if (bubble.getVisibility() == View.VISIBLE) showBubble();
    if (player != null) {
      player.pause();
      player.setSeekParameters(SeekParameters.EXACT);
      player.seekTo(timeline.activeHandle() == 1 ? Math.max(s, e - 1) : s);
    }
  }

  // ---------------------------------------------------------------- thumbnails

  private void loadThumbs() {
    thumbsIo.execute(() -> {
      int n = 16;
      Bitmap[] thumbs = new Bitmap[n];
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(this, uri);
        for (int i = 0; i < n && !isDestroyed(); i++) {
          long t = (long) ((i + .5) * info.durationMs / n) * 1000;
          thumbs[i] = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
          if (i % 4 == 3 || i == n - 1) {
            Bitmap[] copy = thumbs.clone();
            main.post(() -> { timeline.setThumbs(copy); overview.invalidate(); });
          }
        }
      } catch (Exception ignored) {
      } finally {
        try { r.release(); } catch (Exception ignored) {}
      }
      main.post(this::requestDetailThumbs);
    });
  }

  private final Runnable detailJob = this::loadDetailThumbs;

  /** Debounced: sharper thumbnails for whatever range the timeline shows now. */
  private void requestDetailThumbs() {
    main.removeCallbacks(detailJob);
    main.postDelayed(detailJob, 280);
  }

  private void loadDetailThumbs() {
    if (info == null) return;
    long vs = timeline.viewStart(), ve = timeline.viewEnd();
    if (ve - vs >= info.durationMs * 0.7) {
      timeline.setDetailThumbs(0, 0, new Bitmap[0]);
      return;
    }
    int gen = thumbsGen.incrementAndGet();
    thumbsIo.execute(() -> {
      int n = 10;
      Bitmap[] out = new Bitmap[n];
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(this, uri);
        for (int i = 0; i < n; i++) {
          if (gen != thumbsGen.get() || isDestroyed()) return;
          long t = vs + (long) ((i + .5) * (ve - vs) / n);
          out[i] = r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 192, 108);
        }
        if (gen == thumbsGen.get()) main.post(() -> timeline.setDetailThumbs(vs, ve, out));
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

  // ================================================================ lifecycle

  @Override
  @SuppressWarnings("deprecation")
  public void onBackPressed() {
    if (fullscreen) toggleFullscreen();
    else if (sheet != null && sheet.getVisibility() == View.VISIBLE && !ExportService.status.running) hideSheet();
    else if (openTool >= 0) closePanel();
    else super.onBackPressed();
  }

  @Override
  protected void onPause() {
    super.onPause();
    if (player != null) player.pause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (sheet != null && ExportService.status.running) showSheet(true);
  }

  @Override
  protected void onDestroy() {
    cancelAnalysis = true;
    thumbsGen.incrementAndGet();
    main.removeCallbacksAndMessages(null);
    if (player != null) player.release();
    player = null;
    io.shutdownNow();
    thumbsIo.shutdownNow();
    super.onDestroy();
  }
}
