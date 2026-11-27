package com.mohammed.shorts;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
 * bottom toolbar whose tools open in a resizable panel.
 */
@androidx.annotation.OptIn(markerClass = UnstableApi.class)
public final class EditorActivity extends Activity {
  private static final long[] LENGTHS = {15_000, 30_000, 60_000, 90_000, 120_000, 180_000};
  private static final String[] LENGTH_LABELS = {"15ث", "30ث", "60ث", "90ث", "2د", "3د"};
  private static final String[] TOOL_NAMES = {"قص", "ذكي", "إضافة", "الشكل", "الاسم", "المقاطع"};
  private static final String[] TOOL_ICONS = {"scissors", "magic", "plus", "sliders", "text", "stack"};
  private static final float PANEL_MINI = .26f, PANEL_MID = .40f, PANEL_MAX = .60f;
  private static final float TRIM_PANEL_HEIGHT = .30f;

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
  private View topBar;
  private LinearLayout timelineRows;
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
  private ValueAnimator panelAnim, timelineAnim;
  private int panelHeight;
  private boolean fullscreen, panelReducedForPreview;
  private View[] tools = new View[6];

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
    previewArea = new FrameLayout(this) {
      @Override
      protected void onMeasure(int widthSpec, int heightSpec) {
        fitPreview(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, heightSpec);
      }
    };
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
    col.addView(previewArea, new LinearLayout.LayoutParams(-1, 0, 1));
    setupPreviewTouch();

    timelineRows = Ui.column(this);
    col.addView(timelineRows, new LinearLayout.LayoutParams(-1, -2));

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
    timelineRows.addView(info);

    timeline = new TimelineView(this);
    overview = new TimelineView.Overview(this, timeline);
    timelineRows.addView(overview, Ui.lp(-1, -2, dp(6)));
    timelineRows.addView(timeline);
    timeline.setListener(
        new TimelineView.Listener() {
          public void selectionChanged(long s, long e, boolean finished) {
            onSelection(s, e, finished);
          }

          public void seek(long ms, boolean finished) {
            if (player == null) return;
            player.setSeekParameters(finished ? SeekParameters.EXACT : SeekParameters.CLOSEST_SYNC);
            player.seekTo(ms);
            clock.seek(ms);
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
    LinearLayout head = Ui.column(this);
    View grip = new View(this);
    grip.setBackground(Ui.shape(Ui.MUTED, dp(2)));
    LinearLayout.LayoutParams gripLp = Ui.lp(dp(32), dp(4), dp(8));
    gripLp.gravity = Gravity.CENTER_HORIZONTAL;
    head.addView(grip, gripLp);
    LinearLayout titleRow = Ui.row(this);
    titleRow.setPadding(dp(18), dp(2), dp(10), 0);
    panelTitle = Ui.title(this, "", 16);
    titleRow.addView(panelTitle, new LinearLayout.LayoutParams(0, -2, 1));
    titleRow.addView(Ui.iconButton(this, "close", "إغلاق", this::closePanel), new LinearLayout.LayoutParams(dp(36), dp(36)));
    head.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));
    panel.addView(head);
    setupPanelDrag(head);
    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    panelBody = new FrameLayout(this);
    panelBody.setPadding(dp(16), dp(8), dp(16), dp(16));
    scroll.addView(panelBody);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-1, 0);
    pl.setMargins(dp(8), 0, dp(8), 0);
    panel.setVisibility(View.GONE);
    col.addView(panel, pl);

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
    tools[2] = layersPanel = Ui.column(this);
    tools[3] = buildLook();
    tools[4] = buildMarkCard();
    tools[5] = buildClips();
    loadLayers();

    buildBubble();
    buildSheet();
    renderQueue();

    Ui.enter(topBar, 0);
    Ui.enter(previewArea, 1);
    Ui.enter(toolbar, 2);
  }

  private void fitPreview(int areaWidth, int areaHeight) {
    int aw = Math.max(0, areaWidth - dp(24));
    int ah = Math.max(0, areaHeight - dp(fullscreen ? 0 : 8));
    int h = Math.min(ah, aw * 16 / 9), w = h * 9 / 16;
    // Update the child before measuring it, including every frame of the panel animation.
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) previewBox.getLayoutParams();
    lp.width = w;
    lp.height = h;
  }

  // ---------------------------------------------------------------- panel

  private void openTool(int i) {
    if (fullscreen) toggleFullscreen();
    else if (selected != null && openTool == 2 && editingLayer) select(null);
    if (openTool == i) {
      closePanel();
      return;
    }
    boolean wasOpen = openTool >= 0;
    openTool = i;
    panelReducedForPreview = false;
    panelTitle.setText(i == 1 ? "✨ أفضل اللقطات" : i == 2 ? "إضافة" : i == 4 ? "اسم القناة" : TOOL_NAMES[i]);
    if (i == 2) renderLayersPanel();
    panelBody.removeAllViews();
    View v = tools[i];
    panelBody.addView(v);
    v.setAlpha(0f);
    v.setTranslationY(dp(14));
    v.animate().alpha(1f).translationY(0).setDuration(280).setInterpolator(Ui.EASE).start();
    for (int k = 0; k < toolButtons.size(); k++) styleTool(k, k == i);
    panelHeight = panelPixels(i == 0 ? TRIM_PANEL_HEIGHT : i == 1 ? PANEL_MID : PANEL_MINI);
    animatePanel(panelHeight, wasOpen ? 220 : 380);
    updateTimelineRows(true);
    hideBubble();
  }

  private void closePanel() {
    if (openTool < 0) return;
    openTool = -1;
    panelReducedForPreview = false;
    panelHeight = 0;
    for (int k = 0; k < toolButtons.size(); k++) styleTool(k, false);
    animatePanel(0, 320);
    updateTimelineRows(true);
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

  private int panelPixels(float fraction) {
    return Math.round(getResources().getDisplayMetrics().heightPixels * fraction);
  }

  private void setupPanelDrag(View header) {
    int slop = ViewConfiguration.get(this).getScaledTouchSlop();
    header.setOnTouchListener(new View.OnTouchListener() {
      float downY;
      int startHeight;
      boolean dragging;
      VelocityTracker velocity;

      @Override
      public boolean onTouch(View v, MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
          downY = e.getRawY();
          dragging = false;
          startHeight = panel.getHeight();
          velocity = VelocityTracker.obtain();
        }
        if (velocity == null) return false;
        MotionEvent screenEvent = MotionEvent.obtain(e);
        screenEvent.offsetLocation(e.getRawX() - e.getX(), e.getRawY() - e.getY());
        velocity.addMovement(screenEvent);
        screenEvent.recycle();
        if (action == MotionEvent.ACTION_MOVE) {
          float dy = e.getRawY() - downY;
          if (!dragging && Math.abs(dy) > slop) {
            dragging = true;
            cancelPanelAnimation();
            startHeight = panel.getHeight();
          }
          if (dragging) setPanelHeight(Math.round(LayerResize.clamp(startHeight - dy, 0, panelPixels(PANEL_MAX))));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
          velocity.computeCurrentVelocity(1000);
          float vy = velocity.getYVelocity();
          velocity.recycle();
          velocity = null;
          if (dragging && openTool >= 0) {
            if (action == MotionEvent.ACTION_UP) snapPanel(vy);
            else animatePanel(panelHeight, 220);
          }
        }
        return true;
      }
    });
  }

  private void snapPanel(float velocityY) {
    int height = panel.getLayoutParams().height;
    int mini = panelPixels(PANEL_MINI), mid = panelPixels(PANEL_MID), max = panelPixels(PANEL_MAX);
    float projected = height;
    if (Math.abs(velocityY) > dp(600)) projected -= velocityY * .15f;
    if (height < mini || projected < mini) {
      closePanel();
      return;
    }
    panelHeight = projected < (mini + mid) / 2f ? mini : projected < (mid + max) / 2f ? mid : max;
    animatePanel(panelHeight, 220);
  }

  private void cancelPanelAnimation() {
    if (panelAnim == null) return;
    panelAnim.removeAllListeners();
    panelAnim.cancel();
    panelAnim = null;
  }

  private void setPanelHeight(int height) {
    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) panel.getLayoutParams();
    if (lp.height == height) return;
    lp.height = height;
    lp.topMargin = Math.min(height, dp(8));
    panel.setLayoutParams(lp);
  }

  private void animatePanel(int target, long ms) {
    cancelPanelAnimation();
    int start = panel.getLayoutParams().height;
    if (ms == 0 || start == target) {
      setPanelHeight(target);
      panel.setVisibility(target == 0 ? View.GONE : View.VISIBLE);
      return;
    }
    panel.setVisibility(View.VISIBLE);
    panelAnim = ValueAnimator.ofInt(start, target);
    panelAnim.setDuration(ms);
    panelAnim.setInterpolator(Ui.EASE);
    panelAnim.addUpdateListener(a -> setPanelHeight((int) a.getAnimatedValue()));
    panelAnim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        panelAnim = null;
        if (target == 0) panel.setVisibility(View.GONE);
      }
    });
    panelAnim.start();
  }

  private void updateTimelineRows(boolean animate) {
    boolean show = !fullscreen && openTool <= 0;
    if (timelineAnim != null) {
      timelineAnim.removeAllListeners();
      timelineAnim.cancel();
      timelineAnim = null;
    } else if (show == (timelineRows.getVisibility() == View.VISIBLE)) return;

    if (!animate) {
      finishTimelineAnimation(show);
      return;
    }
    boolean visible = timelineRows.getVisibility() == View.VISIBLE;
    int height = timelineRows.getLayoutParams().height;
    int start = visible ? (height >= 0 ? height : timelineRows.getHeight()) : 0;
    float startAlpha = visible ? timelineRows.getAlpha() : 0;
    int target = 0;
    if (show) {
      timelineRows.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
      target = timelineRows.getMeasuredHeight();
    }
    setTimelineRows(start, startAlpha);
    timelineRows.setVisibility(View.VISIBLE);
    timelineAnim = ValueAnimator.ofInt(start, target);
    timelineAnim.setDuration(220);
    timelineAnim.setInterpolator(Ui.EASE);
    timelineAnim.addUpdateListener(a -> {
      float t = a.getAnimatedFraction();
      setTimelineRows((int) a.getAnimatedValue(), startAlpha + ((show ? 1f : 0f) - startAlpha) * t);
    });
    timelineAnim.addListener(new AnimatorListenerAdapter() {
      @Override
      public void onAnimationEnd(Animator animation) {
        timelineAnim = null;
        finishTimelineAnimation(show);
      }
    });
    timelineAnim.start();
  }

  private void setTimelineRows(int height, float alpha) {
    ViewGroup.LayoutParams lp = timelineRows.getLayoutParams();
    lp.height = height;
    timelineRows.setLayoutParams(lp);
    timelineRows.setAlpha(alpha);
  }

  private void finishTimelineAnimation(boolean show) {
    timelineRows.setVisibility(show ? View.VISIBLE : View.GONE);
    setTimelineRows(ViewGroup.LayoutParams.WRAP_CONTENT, show ? 1f : 0f);
  }

  private void reducePanelForPreview() {
    if (openTool < 0 || panelReducedForPreview) return;
    panelReducedForPreview = true;
    int mini = panelPixels(PANEL_MINI);
    animatePanel(panelHeight <= mini ? 0 : mini, 220);
  }

  private void restorePanelAfterPreview() {
    if (!panelReducedForPreview) return;
    panelReducedForPreview = false;
    animatePanel(panelHeight, 220);
  }

  private void toggleFullscreen() {
    fullscreen = !fullscreen;
    if (fullscreen) {
      openTool = -1;
      panelHeight = 0;
      panelReducedForPreview = false;
      for (int k = 0; k < toolButtons.size(); k++) styleTool(k, false);
      animatePanel(0, 0);
    }
    int vis = fullscreen ? View.GONE : View.VISIBLE;
    for (View v : new View[] {topBar, toolbar}) v.setVisibility(vis);
    updateTimelineRows(false);
    hideBubble();
    if (fullscreen) Ui.toast(this, "ضغطتين على الفيديو للرجوع");
  }

  // ---------------------------------------------------------------- preview touch

  /** Media time for layers in the preview, extrapolated between UI ticks so waves stay smooth. */
  static final class PreviewClock implements LayerRenderer.Clock {
    private volatile long pos, at, clipStart;
    private volatile boolean playing;

    void update(long p, boolean isPlaying, long start) {
      pos = p;
      at = SystemClock.elapsedRealtime();
      playing = isPlaying;
      clipStart = start;
    }

    void seek(long p) {
      pos = p;
      at = SystemClock.elapsedRealtime();
    }

    public long mediaMs(long pts) {
      return playing ? pos + (SystemClock.elapsedRealtime() - at) : pos;
    }

    public long clipStartMs() {
      return clipStart;
    }
  }

  private final PreviewClock clock = new PreviewClock();
  private final LayerRenderer uiRenderer = new LayerRenderer();
  private View selectionView;
  private boolean snappedX, snappedY;

  private static final float FRAME_ASPECT = 16f / 9f;
  private static final float MIN_LINE_WIDTH = .15f, MAX_TEXT_SCALE = .4f;
  private static final float MAX_LAYER_WIDTH = 2f, MAX_LAYER_HEIGHT = 2f * FRAME_ASPECT;

  private enum LayerHandle {
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
    LEFT(-1, 0), RIGHT(1, 0),
    BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1),
    ROTATE(0, -1);

    final int sx, sy;

    LayerHandle(int sx, int sy) {
      this.sx = sx;
      this.sy = sy;
    }
  }

  private static final LayerHandle[] LAYER_HANDLES = LayerHandle.values();

  private LayerResize.Box layerBox(Layer l) {
    // Measure at the GL preview's rendering resolution so wrapping and the handles agree.
    float[] size = uiRenderer.size(l, style.outW);
    return new LayerResize.Box(l.x, l.y * FRAME_ASPECT, size[0] / style.outW, size[1] / style.outW);
  }

  private float minLayerSize() {
    return dp(24) / (float) Math.max(1, previewBox.getWidth());
  }

  private float handleY(LayerHandle handle, float halfHeight) {
    return handle.sy * halfHeight - (handle == LayerHandle.ROTATE ? dp(24) : 0);
  }

  /** Draws the selected box and handles in the layer's local axes. */
  private View buildSelectionView() {
    return new View(this) {
      final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      final DashPathEffect dash = new DashPathEffect(new float[] {dp(6), dp(4)}, 0);

      @Override
      protected void onDraw(Canvas c) {
        int w = previewBox.getWidth(), h = previewBox.getHeight();
        if (w == 0 || h == 0) return;
        c.save();
        c.translate(previewBox.getLeft(), previewBox.getTop());
        p.setStyle(Paint.Style.STROKE);
        if (snappedX || snappedY) {
          p.setStrokeWidth(dp(1));
          p.setColor(0xccffd84a);
          if (snappedX) c.drawLine(w / 2f, 0, w / 2f, h, p);
          if (snappedY) c.drawLine(0, h / 2f, w, h / 2f, p);
        }
        Layer l = selected;
        if (l != null && layers.contains(l)) {
          LayerResize.Box box = layerBox(l);
          float halfW = box.width * w / 2 + dp(4), halfH = box.height * w / 2 + dp(4);
          c.translate(l.x * w, l.y * h);
          c.rotate(l.rotation);
          p.setStrokeWidth(dp(1.6f));
          p.setColor(Ui.ACCENT2);
          p.setPathEffect(dash);
          c.drawRoundRect(-halfW, -halfH, halfW, halfH, dp(6), dp(6), p);
          p.setPathEffect(null);

          p.setColor(Color.WHITE);
          c.drawLine(0, -halfH, 0, handleY(LayerHandle.ROTATE, halfH), p);
          p.setStyle(Paint.Style.FILL);
          for (LayerHandle handle : LAYER_HANDLES) {
            float x = handle.sx * halfW, y = handleY(handle, halfH);
            if (handle == LayerHandle.ROTATE || (handle.sx != 0 && handle.sy != 0)) {
              c.drawCircle(x, y, dp(4), p);
            } else {
              int rx = dp(handle.sx == 0 ? 7 : 3), ry = dp(handle.sy == 0 ? 7 : 3);
              c.drawRoundRect(x - rx, y - ry, x + rx, y + ry, dp(3), dp(3), p);
            }
          }
        }
        c.restore();
      }
    };
  }

  private LayerHandle handleAt(float x, float y) {
    Layer l = selected;
    if (l == null || !layers.contains(l)) return null;
    int w = previewBox.getWidth(), h = previewBox.getHeight();
    LayerResize.Box box = layerBox(l);
    float dx = x - l.x * w, dy = y - l.y * h;
    double a = Math.toRadians(l.rotation);
    float localX = (float) (dx * Math.cos(a) + dy * Math.sin(a));
    float localY = (float) (-dx * Math.sin(a) + dy * Math.cos(a));
    float halfW = box.width * w / 2 + dp(4), halfH = box.height * w / 2 + dp(4);
    float radius = dp(28) / 2f, nearest = radius * radius;
    LayerHandle hit = null;
    for (LayerHandle handle : LAYER_HANDLES) {
      float hx = localX - handle.sx * halfW, hy = localY - handleY(handle, halfH);
      float distance = hx * hx + hy * hy;
      if (distance <= nearest) {
        hit = handle;
        nearest = distance;
      }
    }
    return hit;
  }

  private void setupPreviewTouch() {
    selectionView = buildSelectionView();
    // Leave room for handles that extend outside the rounded video frame.
    previewArea.addView(selectionView, new FrameLayout.LayoutParams(-1, -1));
    previewBox.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> selectionView.invalidate());
    previewArea.setOnTouchListener(new PreviewTouch());
  }

  private final class PreviewTouch implements View.OnTouchListener {
    private Layer layer;
    private LayerHandle handle;
    private boolean mark, moved, twoFinger, trackTaps;
    private long backgroundTap = -1;
    private int pointer, secondPointer;
    private LayerResize.Box startBox;
    private float startX, startY, startScale, startRotation, startLineWidth;
    private float lastX, lastY, dragX, dragY, startDistance, startAngle, rotateX, rotateY;

    private final GestureDetector taps = new GestureDetector(EditorActivity.this,
        new GestureDetector.SimpleOnGestureListener() {
          @Override
          public boolean onDown(MotionEvent e) {
            return true;
          }

          @Override
          public boolean onSingleTapConfirmed(MotionEvent e) {
            if (e.getDownTime() != backgroundTap) return false;
            if (selected != null) select(null);
            else togglePlay();
            return true;
          }

          @Override
          public boolean onDoubleTap(MotionEvent e) {
            if (layer != null && layer.type == Layer.TEXT) {
              select(layer);
              if (layerText != null) {
                layerText.requestFocus();
                layerText.setSelection(layerText.getText().length());
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .showSoftInput(layerText, 0);
              }
            } else toggleFullscreen();
            return true;
          }
        });

    @Override
    public boolean onTouch(View v, MotionEvent e) {
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) return begin(v, e);
      if (trackTaps) taps.onTouchEvent(e);
      switch (action) {
        case MotionEvent.ACTION_POINTER_DOWN:
          if (e.getPointerCount() == 2) beginPinch(e, 0, 1);
          break;
        case MotionEvent.ACTION_MOVE:
          move(v, e);
          break;
        case MotionEvent.ACTION_POINTER_UP:
          liftPointer(e);
          break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          finish();
          break;
      }
      return true;
    }

    private boolean begin(View v, MotionEvent e) {
      int w = previewBox.getWidth(), h = previewBox.getHeight();
      if (w == 0 || h == 0) return false;
      float x = e.getX() - previewBox.getLeft(), y = e.getY() - previewBox.getTop();
      handle = handleAt(x, y);
      if (handle == null && (x < 0 || x > w || y < 0 || y > h)) return false;
      v.getParent().requestDisallowInterceptTouchEvent(true);
      pointer = e.getPointerId(0);
      moved = twoFinger = false;
      layer = handle != null ? selected : layerAt(x, y);
      mark = layer == null && style.markOn && hitsMark(x, y, w, h);
      if (layer != null && layer != selected) select(layer);
      resetDrag(e.getRawX(), e.getRawY());
      if (handle == LayerHandle.ROTATE) {
        rotateX = (x - layer.x * w) / w;
        rotateY = (y - layer.y * h) / w;
      }
      if (layer != null || mark) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);

      backgroundTap = layer == null && !mark ? e.getDownTime() : -1;
      trackTaps = handle == null;
      if (trackTaps) taps.onTouchEvent(e);
      else {
        MotionEvent cancel = MotionEvent.obtain(e);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        taps.onTouchEvent(cancel);
        cancel.recycle();
      }
      return true;
    }

    private void resetDrag(float x, float y) {
      lastX = x;
      lastY = y;
      dragX = dragY = 0;
      startBox = layer != null ? layerBox(layer) : null;
      startX = layer != null ? startBox.x : style.markX;
      startY = layer != null ? startBox.y : style.markY * FRAME_ASPECT;
      startScale = layer != null ? layer.scale : style.markSize;
      startRotation = layer != null ? layer.rotation : 0;
      startLineWidth = layer != null ? layer.boxWidth : 0;
    }

    private void beginPinch(MotionEvent e, int a, int b) {
      if (layer == null && !mark) {
        layer = selected;
        mark = layer == null && style.markOn;
      }
      if (layer == null && !mark) return;
      backgroundTap = -1;
      handle = null;
      twoFinger = moved = true;
      pointer = e.getPointerId(a);
      secondPointer = e.getPointerId(b);
      float dx = e.getRawX(b) - e.getRawX(a), dy = e.getRawY(b) - e.getRawY(a);
      startDistance = (float) Math.max(1, Math.hypot(dx, dy));
      startAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
      resetDrag((e.getRawX(a) + e.getRawX(b)) / 2, (e.getRawY(a) + e.getRawY(b)) / 2);
      reducePanelForPreview();
    }

    private void liftPointer(MotionEvent e) {
      if (!twoFinger) return;
      int up = e.getActionIndex(), id = e.getPointerId(up);
      if (id != pointer && id != secondPointer) return;
      int keep = up == 0 ? 1 : 0;
      if (e.getPointerCount() > 2) {
        beginPinch(e, keep, up == 2 ? 1 : 2);
        return;
      }
      twoFinger = false;
      pointer = e.getPointerId(keep);
      resetDrag(e.getRawX(keep), e.getRawY(keep));
    }

    private void move(View v, MotionEvent e) {
      if (layer == null && !mark) return;
      int w = previewBox.getWidth(), a = e.findPointerIndex(pointer);
      if (w == 0 || a < 0) return;
      float x = e.getRawX(a), y = e.getRawY(a);
      float factor = 1, angle = 0;
      if (twoFinger) {
        int b = e.findPointerIndex(secondPointer);
        if (b < 0) return;
        float dx = e.getRawX(b) - x, dy = e.getRawY(b) - y;
        x += dx / 2;
        y += dy / 2;
        factor = (float) Math.hypot(dx, dy) / startDistance;
        angle = (float) Math.toDegrees(Math.atan2(dy, dx)) - startAngle;
      }
      if (!moved) {
        if (Math.hypot(x - lastX, y - lastY) <= dp(5)) return;
        moved = true;
        reducePanelForPreview();
      }
      // Track motion on the screen so a changing preview size or origin cannot move the layer.
      dragX += (x - lastX) / w;
      dragY += (y - lastY) / w;
      lastX = x;
      lastY = y;

      if (handle != null) {
        dragHandle();
        pushLayers(false);
        return;
      }
      if (twoFinger) scalePinch(factor, angle);
      moveBody(v);
    }

    private void dragHandle() {
      if (handle == LayerHandle.ROTATE) {
        float angle = (float) Math.toDegrees(Math.atan2(rotateY + dragY, rotateX + dragX));
        layer.rotation = LayerResize.snapRotation(angle + 90, 45);
        return;
      }
      int sx = handle.sx, sy = handle.sy;
      float min = minLayerSize();
      LayerResize.Box box;
      if (layer.type == Layer.TEXT) {
        float previousWidth = layer.boxWidth, previousScale = layer.scale;
        box = LayerResize.drag(startBox, startRotation, sx, sy, dragX, dragY, sy != 0,
            0, 0, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        if (sy == 0) {
          box = LayerResize.limitCentre(startBox, box, FRAME_ASPECT);
          float pad = 2 * LayerRenderer.textPadding(layer);
          float minWidth = Math.max(MIN_LINE_WIDTH, min - pad);
          layer.boxWidth = Math.abs(box.width - startBox.width) < .0001f ? startLineWidth
              : LayerResize.clamp(box.width - pad, minWidth, 1f);
        } else {
          setTextScale(layer, startScale * box.height / startBox.height, min);
        }
        LayerResize.Box measured = layerBox(layer);
        box = LayerResize.anchor(startBox, startRotation, sx, sy, measured.width, measured.height);
        // Reflow can change the size discontinuously, so keep the last valid text box at an edge.
        if (box.x < 0f || box.x > 1f || box.y < 0f || box.y > FRAME_ASPECT) {
          layer.boxWidth = previousWidth;
          layer.scale = previousScale;
          return;
        }
      } else {
        box = LayerResize.drag(startBox, startRotation, sx, sy, dragX, dragY, layer.lockAspect,
            min, min, Math.max(MAX_LAYER_WIDTH, startBox.width), Math.max(MAX_LAYER_HEIGHT, startBox.height));
        box = LayerResize.limitCentre(startBox, box, FRAME_ASPECT);
        layer.scale = box.width;
        if (!layer.lockAspect && (sx == 0 || sy == 0)) layer.aspect = box.height / box.width;
      }
      layer.x = box.x;
      layer.y = box.y / FRAME_ASPECT;
    }

    private void scalePinch(float factor, float angle) {
      if (layer == null) {
        style.markSize = LayerResize.clamp(startScale * factor, .02f, .11f);
        setSlider("markSize", style.markSize);
      } else {
        float min = minLayerSize();
        if (layer.type == Layer.TEXT) setTextScale(layer, startScale * factor, min);
        else layer.scale = LayerResize.clamp(startScale * factor, Math.max(min, min / layer.aspect),
            Math.max(MAX_LAYER_WIDTH, startScale));
        layer.rotation = LayerResize.snapRotation(startRotation + angle, 90);
      }
    }

    private void moveBody(View v) {
      float nx = startX + dragX, ny = (startY + dragY) / FRAME_ASPECT;
      boolean sx = Math.abs(nx - .5f) < .02f, sy = Math.abs(ny - .5f) < .015f;
      if ((sx && !snappedX) || (sy && !snappedY)) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
      snappedX = sx;
      snappedY = sy;
      if (sx) nx = .5f;
      if (sy) ny = .5f;
      if (layer != null) {
        layer.x = LayerResize.clamp(nx, 0, 1);
        layer.y = LayerResize.clamp(ny, 0, 1);
        pushLayers(false);
      } else {
        style.markX = LayerResize.clamp(nx, .03f, .97f);
        style.markY = LayerResize.clamp(ny, .02f, .98f);
        selectionView.invalidate();
        refreshPreview();
      }
    }

    private void finish() {
      snappedX = snappedY = false;
      selectionView.invalidate();
      restorePanelAfterPreview();
      if (moved && layer != null) {
        pushLayers(true);
        if (openTool == 2 && editingLayer) renderLayersPanel();
      } else if (moved && mark) style.save(EditorActivity.this);
    }
  }

  private void setTextScale(Layer l, float scale, float minSize) {
    l.scale = LayerResize.clamp(scale, .015f, MAX_TEXT_SCALE);
    LayerResize.Box box = layerBox(l);
    float shortEdge = Math.min(box.width, box.height);
    if (shortEdge < minSize) {
      l.scale = Math.min(MAX_TEXT_SCALE, l.scale * minSize / shortEdge);
    }
  }

  private Layer layerAt(float x, float y) {
    int w = previewBox.getWidth(), h = previewBox.getHeight();
    float px = x * style.outW / w, py = y * style.outH / h;
    float slop = style.outW / (float) w;
    if (selected != null && uiRenderer.hit(selected, style.outW, style.outH, px, py, dp(14) * slop)) return selected;
    for (int i = layers.size() - 1; i >= 0; i--) {
      Layer l = layers.get(i);
      if (uiRenderer.hit(l, style.outW, style.outH, px, py, dp(10) * slop)) return l;
    }
    return null;
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
            AudioData audio = awaitAudio();
            Highlights.Signal s =
                Highlights.analyze(
                    this,
                    uri,
                    info.durationMs,
                    audio,
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
    if (!main.hasCallbacks(refresh)) main.postDelayed(refresh, 60);
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

  // ---------------------------------------------------------------- layers (text, stickers, images, video, waves)

  private static final int PICK_IMAGE = 21, PICK_VIDEO = 22, PICK_FONT = 23;
  private static final String[] STICKERS = {
    "🔥", "😂", "😱", "🤯", "😍", "👀", "👇", "👆", "👉", "✅", "❌", "⚠️",
    "💯", "⭐", "❤️", "💡", "🎯", "🚀", "🎮", "📌", "⏰", "💰", "🏆", "🤔",
    "😎", "🙏", "👏", "💀", "😭", "🤫", "📢", "🔔", "➡️", "⬇️", "🆕", "❓"
  };
  private static final int[] COLORS = {
    0xffffffff, 0xff000000, 0xffffd84a, 0xffff5c7a, 0xff22d3ee, 0xff8b6cff,
    0xff4ade80, 0xffff8a3d, 0xffff6ab0, 0xff3d8bff, 0xff9ca3af, 0xff7c2d12
  };

  private final List<Layer> layers = new ArrayList<>();
  private Layer selected;
  private boolean editingLayer, stickerMode;
  private LinearLayout layersPanel;
  private EditText layerText;
  private TextView videoProgress;
  private volatile AudioData audio;
  private final java.util.concurrent.CountDownLatch audioReady = new java.util.concurrent.CountDownLatch(1);

  private void loadLayers() {
    layers.clear();
    layers.addAll(Layer.load(this));
    pushLayers(false);
  }

  private final Runnable saveLayers = () -> {
    List<Layer> copy = new ArrayList<>(layers);
    io.execute(() -> {
      Layer.save(this, copy);
      Layer.cleanFiles(this, copy);
    });
  };

  /** Hands the current layers to the GL renderer and refreshes the preview. */
  private void pushLayers(boolean save) {
    style.layers = Collections.unmodifiableList(new ArrayList<>(layers));
    style.layersVersion++;
    if (selectionView != null) selectionView.invalidate();
    refreshPreview();
    if (save) {
      main.removeCallbacks(saveLayers);
      main.postDelayed(saveLayers, 400);
    }
  }

  private void loadAudio() {
    io.execute(() -> {
      try {
        audio = AudioData.load(this, uri, info.durationMs, new Highlights.Listener() {
          public void progress(float f) {}
          public boolean cancelled() { return isDestroyed(); }
        });
        style.audio = audio;
        main.post(this::refreshPreview);
      } catch (Exception ignored) {
      } finally {
        audioReady.countDown();
      }
    });
  }

  private AudioData awaitAudio() throws InterruptedException {
    audioReady.await();
    return audio;
  }

  private void select(Layer l) {
    selected = l;
    selectionView.invalidate();
    if (l == null) {
      editingLayer = false;
      if (openTool == 2) renderLayersPanel();
      return;
    }
    editingLayer = true;
    stickerMode = false;
    if (openTool != 2) openTool(2);
    else renderLayersPanel();
  }

  private void addLayer(Layer l) {
    layers.add(l);
    pushLayers(true);
    select(l);
  }

  private void renderLayersPanel() {
    if (layersPanel == null) return;
    layersPanel.removeAllViews();
    layerText = null;
    if (editingLayer && selected != null && layers.contains(selected)) {
      layersPanel.addView(buildLayerEditor(selected));
      return;
    }
    if (stickerMode) {
      layersPanel.addView(buildStickerPicker());
      return;
    }
    LinearLayout tiles = Ui.row(this);
    tiles.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    addTile(tiles, "نص", "text", () -> addLayer(Layer.text("اكتب هنا")));
    addTile(tiles, "ملصق", "smile", () -> { stickerMode = true; renderLayersPanel(); });
    addTile(tiles, "صورة", "image", () -> pick(PICK_IMAGE, "image/*"));
    addTile(tiles, "فيديو", "video", () -> pick(PICK_VIDEO, "video/*"));
    addTile(tiles, "موجة", "wave", () -> addLayer(Layer.wave()));
    layersPanel.addView(tiles);
    videoProgress = Ui.text(this, "", 13, Ui.ACCENT2);
    videoProgress.setVisibility(View.GONE);
    layersPanel.addView(videoProgress, Ui.lp(-1, -2, dp(10)));

    TextView h = Ui.text(this, layers.isEmpty() ? "" : "الطبقات (اللي تحت تطلع فوق)", 13, Ui.MUTED);
    layersPanel.addView(h, Ui.lp(-1, -2, dp(14)));
    if (layers.isEmpty()) {
      TextView e = Ui.text(this, "أضف نص أو ملصق أو صورة أو فيديو أو موجة صوتية.\nتقدر تسحبها فوق المعاينة، وتكبّرها وتلفّها بإصبعين.", 13, Ui.MUTED);
      e.setGravity(Gravity.CENTER);
      e.setPadding(dp(12), dp(20), dp(12), dp(20));
      e.setBackground(Ui.outlined(Ui.CARD, Ui.STROKE, dp(18), dp(1)));
      layersPanel.addView(e, Ui.lp(-1, -2, dp(8)));
      return;
    }
    for (int i = 0; i < layers.size(); i++) {
      Layer l = layers.get(i);
      LinearLayout row = Ui.card(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(8), dp(8), dp(8));
      ImageView ic = new ImageView(this);
      ic.setImageDrawable(new Ui.Glyph(l.type == Layer.TEXT ? "text" : l.type == Layer.IMAGE ? "image" : l.type == Layer.VIDEO ? "video" : "wave", Ui.ACCENT2));
      row.addView(ic, new LinearLayout.LayoutParams(dp(22), dp(22)));
      TextView t = Ui.text(this, l.label(), 14, Ui.TEXT);
      t.setSingleLine(true);
      LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
      tl.setMarginStart(dp(10));
      row.addView(t, tl);
      int idx = i;
      row.addView(Ui.iconButton(this, "up", "للأمام", () -> moveLayer(idx, 1)), new LinearLayout.LayoutParams(dp(34), dp(34)));
      LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(34), dp(34));
      dl.setMarginStart(dp(4));
      row.addView(Ui.iconButton(this, "down", "للخلف", () -> moveLayer(idx, -1)), dl);
      ImageView del = Ui.iconButton(this, "trash", "احذف", () -> removeLayer(l));
      ((Ui.Glyph) del.getDrawable()).paint.setColor(Ui.DANGER);
      LinearLayout.LayoutParams xl = new LinearLayout.LayoutParams(dp(34), dp(34));
      xl.setMarginStart(dp(4));
      row.addView(del, xl);
      row.setOnClickListener(v -> select(l));
      Ui.pressable(row);
      layersPanel.addView(row, Ui.lp(-1, -2, dp(8)));
    }
  }

  private void addTile(LinearLayout parent, String label, String icon, Runnable action) {
    LinearLayout t = Ui.column(this);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, dp(12), 0, dp(10));
    t.setBackground(Ui.outlined(Ui.CARD, Ui.STROKE, dp(18), dp(1)));
    ImageView ic = new ImageView(this);
    ic.setImageDrawable(new Ui.Glyph(icon, Ui.ACCENT2));
    t.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));
    TextView l = Ui.text(this, label, 12, Ui.TEXT);
    l.setGravity(Gravity.CENTER);
    t.addView(l, Ui.lp(-2, -2, dp(6)));
    t.setOnClickListener(v -> action.run());
    Ui.pressable(t);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMargins(dp(3), 0, dp(3), 0);
    parent.addView(t, lp);
  }

  private void moveLayer(int i, int dir) {
    int j = i + dir;
    if (j < 0 || j >= layers.size()) return;
    Collections.swap(layers, i, j);
    pushLayers(true);
    renderLayersPanel();
  }

  private void removeLayer(Layer l) {
    layers.remove(l);
    if (selected == l) selected = null;
    editingLayer = false;
    pushLayers(true);
    renderLayersPanel();
  }

  private View buildStickerPicker() {
    LinearLayout c = Ui.column(this);
    LinearLayout head = Ui.row(this);
    head.addView(Ui.ghost(this, "رجوع", "back", () -> { stickerMode = false; renderLayersPanel(); }));
    head.addView(Ui.space(this, dp(8)));
    head.addView(Ui.ghost(this, "صورة PNG شفافة", "image", () -> { stickerMode = false; pick(PICK_IMAGE, "image/*"); }));
    c.addView(head);
    LinearLayout row = null;
    for (int i = 0; i < STICKERS.length; i++) {
      if (i % 6 == 0) {
        row = Ui.row(this);
        c.addView(row, Ui.lp(-1, -2, dp(8)));
      }
      String s = STICKERS[i];
      TextView t = Ui.text(this, s, 28, Ui.TEXT);
      t.setGravity(Gravity.CENTER);
      t.setBackground(Ui.shape(Ui.CARD, dp(14)));
      t.setOnClickListener(v -> { stickerMode = false; addLayer(Layer.sticker(s)); });
      Ui.pressable(t);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1);
      lp.setMargins(dp(3), 0, dp(3), 0);
      row.addView(t, lp);
    }
    return c;
  }

  // ---------------------------------------------------------------- layer editor

  private interface LayerSetter {
    void set(float v);
  }

  private LinearLayout layerSlider(String label, float min, float max, float value, java.util.function.Function<Float, String> fmt, LayerSetter s) {
    return Ui.slider(this, label, min, max, value, fmt, (v, user) -> { s.set(v); pushLayers(user); });
  }

  private static String sizePercent(float value) {
    return Math.round(value * 100) + "٪";
  }

  private void buildSizeSliders(LinearLayout c, Layer l) {
    TextView lock = Ui.chip(this, l.lockAspect ? "🔒 تثبيت النسبة" : "🔓 تثبيت النسبة", null);
    Ui.select(lock, l.lockAspect);
    lock.setOnClickListener(v -> {
      l.lockAspect = !l.lockAspect;
      lock.setText(l.lockAspect ? "🔒 تثبيت النسبة" : "🔓 تثبيت النسبة");
      Ui.select(lock, l.lockAspect);
      pushLayers(true);
    });
    c.addView(lock, Ui.lp(-2, -2, dp(8)));

    float maxWidth = Math.max(MAX_LAYER_WIDTH, l.scale);
    float maxHeight = Math.max(MAX_LAYER_HEIGHT, l.scale * l.aspect);
    String[] labels = l.type == Layer.WAVE ? new String[] {"العرض", "الارتفاع"}
        : new String[] {"العرض", "الارتفاع", "الحجم"};
    LinearLayout[] dimensions = new LinearLayout[labels.length];
    for (int i = 0; i < labels.length; i++) {
      boolean width = i != 1, uniform = i == 2;
      dimensions[i] = Ui.slider(this, labels[i], 0, width ? maxWidth : maxHeight,
          width ? l.scale : l.scale * l.aspect,
          EditorActivity::sizePercent, (value, user) -> {
            if (!user) return;
            boolean keepAspect = l.lockAspect || uniform;
            float min = minLayerSize(), height = l.scale * l.aspect;
            LayerResize.Box box = new LayerResize.Box(0, 0, l.scale, height);
            LayerResize.Box resized = LayerResize.drag(box, 0, width ? 1 : 0, width ? 0 : 1,
                width ? value - l.scale : 0, width ? 0 : value - height,
                keepAspect, min, min, maxWidth, maxHeight);
            l.scale = resized.width;
            if (!keepAspect) l.aspect = resized.height / resized.width;
            Ui.setSlider(dimensions[0], 0, maxWidth, l.scale);
            Ui.setSlider(dimensions[1], 0, maxHeight, l.scale * l.aspect);
            if (dimensions.length > 2) Ui.setSlider(dimensions[2], 0, maxWidth, l.scale);
            pushLayers(true);
          });
      c.addView(dimensions[i]);
    }
  }

  private LinearLayout chipRow(String[] labels, int current, java.util.function.IntConsumer pick) {
    LinearLayout row = Ui.row(this);
    List<TextView> chips = new ArrayList<>();
    for (int i = 0; i < labels.length; i++) {
      int k = i;
      TextView chip = Ui.chip(this, labels[i], null);
      chip.setOnClickListener(v -> {
        for (int j = 0; j < chips.size(); j++) Ui.select(chips.get(j), j == k);
        pick.accept(k);
      });
      Ui.select(chip, i == current);
      chips.add(chip);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(dp(8));
      row.addView(chip, lp);
    }
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    hs.addView(row);
    LinearLayout wrap = Ui.column(this);
    wrap.addView(hs);
    return wrap;
  }

  private View colorRow(int current, java.util.function.IntConsumer pick) {
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout row = Ui.row(this);
    List<View> dots = new ArrayList<>();
    for (int col : COLORS) {
      View d = new View(this);
      d.setBackground(Ui.outlined(col, col == current ? Ui.ACCENT2 : Ui.STROKE, dp(16), dp(col == current ? 3 : 1)));
      d.setOnClickListener(v -> {
        for (int j = 0; j < dots.size(); j++)
          dots.get(j).setBackground(Ui.outlined(COLORS[j], COLORS[j] == col ? Ui.ACCENT2 : Ui.STROKE, dp(16), dp(COLORS[j] == col ? 3 : 1)));
        pick.accept(col);
      });
      dots.add(d);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
      lp.setMarginEnd(dp(8));
      row.addView(d, lp);
    }
    hs.addView(row);
    return hs;
  }

  private TextView label(String s) {
    TextView t = Ui.text(this, s, 13, Ui.MUTED);
    t.setPadding(0, dp(12), 0, dp(4));
    return t;
  }

  private View buildLayerEditor(Layer l) {
    LinearLayout c = Ui.column(this);
    LinearLayout head = Ui.row(this);
    head.addView(Ui.ghost(this, "الطبقات", "back", () -> { editingLayer = false; select(null); }));
    head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    head.addView(Ui.iconButton(this, "copy", "نسخة", () -> {
      Layer d = l.copy();
      d.id = java.util.UUID.randomUUID().toString();
      d.x = Math.min(.95f, l.x + .04f);
      d.y = Math.min(.95f, l.y + .04f);
      addLayer(d);
    }), new LinearLayout.LayoutParams(dp(38), dp(38)));
    ImageView del = Ui.iconButton(this, "trash", "احذف", () -> removeLayer(l));
    ((Ui.Glyph) del.getDrawable()).paint.setColor(Ui.DANGER);
    LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(38), dp(38));
    dl.setMarginStart(dp(6));
    head.addView(del, dl);
    c.addView(head);

    if (l.type == Layer.TEXT) buildTextEditor(c, l);
    else if (l.type == Layer.WAVE) buildWaveEditor(c, l);
    else {
      buildSizeSliders(c, l);
      c.addView(layerSlider("انحناء الزوايا", 0, .5f, l.radius, v -> Math.round(v / .5f * 100) + "٪", v -> l.radius = v));
      if (l.type == Layer.VIDEO)
        c.addView(Ui.text(this, "يتكرر طول الشورت. صوت الفيديو المضاف ما ينضاف.", 12, Ui.MUTED), Ui.lp(-1, -2, dp(6)));
    }
    c.addView(layerSlider("الوضوح", .1f, 1f, l.opacity, v -> Math.round(v * 100) + "٪", v -> l.opacity = v));
    c.addView(layerSlider("الدوران", -180, 180, l.rotation, v -> Math.round(v) + "°", v -> l.rotation = v));
    LinearLayout pos = Ui.row(this);
    pos.addView(Ui.ghost(this, "بالنص", null, () -> { l.x = .5f; l.y = .5f; pushLayers(true); }), new LinearLayout.LayoutParams(0, dp(42), 1));
    LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, dp(42), 1);
    pl.setMarginStart(dp(8));
    pos.addView(Ui.ghost(this, "صفّر الدوران", null, () -> { l.rotation = 0; pushLayers(true); renderLayersPanel(); }), pl);
    c.addView(pos, Ui.lp(-1, -2, dp(10)));
    c.addView(Ui.text(this, "اسحب مقابض الإطار لتغيير الحجم أو الدوران. حركة الإصبعين تشتغل بعد.", 12, Ui.MUTED),
        Ui.lp(-1, -2, dp(10)));
    return c;
  }

  private void buildTextEditor(LinearLayout c, Layer l) {
    layerText = new EditText(this);
    layerText.setText(l.text);
    layerText.setMaxLines(4);
    layerText.setTextColor(Ui.TEXT);
    layerText.setHintTextColor(Ui.MUTED);
    layerText.setHint("اكتب النص");
    layerText.setTextSize(16);
    layerText.setPadding(dp(14), dp(10), dp(14), dp(10));
    layerText.setBackground(Ui.outlined(Ui.CARD, Ui.STROKE, dp(14), dp(1)));
    layerText.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence a, int b, int c2, int d) {}
      public void onTextChanged(CharSequence a, int b, int c2, int d) {}
      public void afterTextChanged(android.text.Editable e) { l.text = e.toString(); pushLayers(true); }
    });
    c.addView(layerText, Ui.lp(-1, -2, dp(10)));

    c.addView(label("الخط"));
    HorizontalScrollView hs = new HorizontalScrollView(this);
    hs.setHorizontalScrollBarEnabled(false);
    LinearLayout fonts = Ui.row(this);
    TextView add = Ui.chip(this, "+ استيراد خط", () -> pick(PICK_FONT, "*/*"));
    add.setTextColor(Ui.ACCENT2);
    LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
    al.setMarginEnd(dp(8));
    fonts.addView(add, al);
    List<TextView> chips = new ArrayList<>();
    for (Fonts.Entry f : Fonts.all(this)) {
      TextView chip = Ui.chip(this, "أبجد Abc", null);
      chip.setTypeface(Fonts.get(f.key));
      chip.setTextSize(15);
      chip.setContentDescription(f.name);
      chip.setOnClickListener(v -> {
        l.font = f.key;
        for (TextView x : chips) Ui.select(x, x == chip);
        pushLayers(true);
      });
      if (f.key.startsWith("file:"))
        chip.setOnLongClickListener(v -> {
          Fonts.delete(f.key);
          if (f.key.equals(l.font)) l.font = Fonts.DEFAULT;
          pushLayers(true);
          renderLayersPanel();
          Ui.toast(this, "انحذف الخط");
          return true;
        });
      Ui.select(chip, f.key.equals(l.font));
      chips.add(chip);
      LinearLayout box = Ui.column(this);
      box.setGravity(Gravity.CENTER_HORIZONTAL);
      box.addView(chip);
      TextView n = Ui.text(this, f.name, 10, Ui.MUTED);
      n.setGravity(Gravity.CENTER);
      n.setMaxWidth(dp(110));
      n.setSingleLine(true);
      box.addView(n);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(dp(8));
      fonts.addView(box, lp);
    }
    hs.addView(fonts);
    c.addView(hs);

    c.addView(label("لون النص"));
    c.addView(colorRow(l.color, col -> { l.color = col; pushLayers(true); }));
    c.addView(layerSlider("الحجم", .015f, MAX_TEXT_SCALE, l.scale, EditorActivity::sizePercent,
        v -> setTextScale(l, v, minLayerSize())));
    // The first slider step represents automatic width, stored as zero.
    float auto = MIN_LINE_WIDTH - .001f;
    c.addView(layerSlider("عرض السطر", auto, 1f, l.boxWidth == 0 ? auto : l.boxWidth,
        v -> v <= auto ? "تلقائي" : sizePercent(Math.max(MIN_LINE_WIDTH, v)),
        v -> l.boxWidth = v <= auto ? 0 : Math.max(MIN_LINE_WIDTH, v)));
    c.addView(label("المحاذاة"));
    c.addView(chipRow(new String[] {"وسط", "يمين", "يسار"}, l.align, k -> { l.align = k; pushLayers(true); }));
    c.addView(layerSlider("الحدود", 0, .25f, l.stroke, v -> Math.round(v / .25f * 100) + "٪", v -> l.stroke = v));
    c.addView(label("لون الحدود"));
    c.addView(colorRow(l.strokeColor, col -> { l.strokeColor = col; pushLayers(true); }));
    c.addView(label("الخلفية"));
    c.addView(chipRow(new String[] {"بدون", "صندوق", "شفاف"}, l.bg, k -> { l.bg = k; pushLayers(true); }));
    c.addView(label("لون الخلفية"));
    c.addView(colorRow(l.bgColor, col -> { l.bgColor = col; pushLayers(true); }));
    c.addView(label("الظل"));
    c.addView(chipRow(new String[] {"مع ظل", "بدون"}, l.shadow ? 0 : 1, k -> { l.shadow = k == 0; pushLayers(true); }));
  }

  private void buildWaveEditor(LinearLayout c, Layer l) {
    c.addView(label("الشكل"));
    c.addView(chipRow(new String[] {"أعمدة", "متناظرة", "موجة", "دائرية", "نقاط", "طيف"}, l.waveStyle, k -> {
      l.waveStyle = k;
      boolean resized = k == 3 && l.aspect < .8f;
      if (resized) l.aspect = 1f;
      pushLayers(true);
      if (resized) renderLayersPanel();
    }));
    c.addView(label("اللون"));
    c.addView(chipRow(new String[] {"تدرّج", "لون واحد"}, l.gradient ? 0 : 1, k -> { l.gradient = k == 0; pushLayers(true); }));
    c.addView(colorRow(l.color, col -> { l.color = col; l.gradient = false; pushLayers(true); renderLayersPanel(); }), Ui.lp(-1, -2, dp(8)));
    c.addView(layerSlider("عدد الأعمدة", 8, 64, l.bars, v -> String.valueOf(Math.round(v)), v -> l.bars = Math.round(v)));
    c.addView(layerSlider("الحساسية", .3f, 2.5f, l.sensitivity, v -> Math.round(v * 100) + "٪", v -> l.sensitivity = v));
    buildSizeSliders(c, l);
    if (audio == null || !audio.hasAudio)
      c.addView(Ui.text(this, audio == null ? "جارٍ قراءة صوت الفيديو…" : "الفيديو ما فيه صوت، فالموجة بتكون ساكنة.", 12, Ui.MUTED), Ui.lp(-1, -2, dp(6)));
  }

  // ---------------------------------------------------------------- pickers

  private void pick(int code, String type) {
    Intent i;
    if (code == PICK_FONT) {
      i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
          .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream"});
    } else if (Build.VERSION.SDK_INT >= 33) {
      i = new Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).setType(type);
    } else {
      i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type);
    }
    try {
      startActivityForResult(i, code);
    } catch (Exception e) {
      startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type), code);
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (res != RESULT_OK || data == null || data.getData() == null) return;
    Uri u = data.getData();
    if (req == PICK_FONT) {
      io.execute(() -> {
        try {
          String key = Fonts.importFont(this, u);
          main.post(() -> {
            if (selected != null && selected.type == Layer.TEXT) {
              selected.font = key;
              pushLayers(true);
            }
            renderLayersPanel();
            Ui.toast(this, "انضاف الخط ✓");
          });
        } catch (Exception e) {
          main.post(() -> Ui.toast(this, e.getMessage()));
        }
      });
    } else if (req == PICK_IMAGE) {
      io.execute(() -> {
        try {
          Bitmap b = android.graphics.ImageDecoder.decodeBitmap(
              android.graphics.ImageDecoder.createSource(getContentResolver(), u),
              (dec, inf, src) -> {
                int w = inf.getSize().getWidth(), h = inf.getSize().getHeight();
                float k = Math.min(1f, 1280f / Math.max(w, h));
                dec.setTargetSize(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)));
                dec.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);
              });
          java.io.File f = new java.io.File(Layer.dir(this), "img_" + java.util.UUID.randomUUID() + ".png");
          try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
          }
          Layer l = new Layer(Layer.IMAGE);
          l.path = f.getAbsolutePath();
          l.aspect = b.getHeight() / (float) b.getWidth();
          l.scale = l.aspect > 1.2f ? .42f : .6f;
          b.recycle();
          main.post(() -> addLayer(l));
        } catch (Exception e) {
          main.post(() -> Ui.toast(this, "ما قدرت أفتح الصورة"));
        }
      });
    } else if (req == PICK_VIDEO) {
      extractVideo(u);
    }
  }

  /** Turns a picked video into small JPEG frames (max 20 s at 24 fps) so it can loop on top. */
  private void extractVideo(Uri u) {
    if (videoProgress != null) {
      videoProgress.setVisibility(View.VISIBLE);
      videoProgress.setText("جارٍ تجهيز الفيديو… 0٪");
    }
    io.execute(() -> {
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      java.io.File dir = new java.io.File(Layer.dir(this), "vid_" + java.util.UUID.randomUUID());
      try {
        r.setDataSource(this, u);
        Clip.Info vi = Clip.Info.read(this, u);
        float fps = 24f;
        long len = Math.min(20_000, vi.durationMs);
        int count = Math.max(1, (int) (len * fps / 1000));
        dir.mkdirs();
        float k = Math.min(1f, 640f / Math.max(vi.width, vi.height));
        int tw = Math.max(2, Math.round(vi.width * k)), th = Math.max(2, Math.round(vi.height * k));
        String fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        int total = fc == null ? 0 : Integer.parseInt(fc.trim());
        float srcFps = total > 0 ? total * 1000f / vi.durationMs : 30f;
        int written = 0;
        int nextSrc = 0;
        boolean sequential = total > 0;
        int batch = 6;
        for (int i = 0; i < count && !isDestroyed(); ) {
          List<Bitmap> got = null;
          int target = Math.round(i * srcFps / fps);
          if (sequential) {
            try {
              MediaMetadataRetriever.BitmapParams bp = new MediaMetadataRetriever.BitmapParams();
              bp.setPreferredConfig(Bitmap.Config.RGB_565);
              int n = Math.min(batch, total - target);
              if (n <= 0) break;
              got = r.getFramesAtIndex(target, n, bp);
              if (got == null || got.isEmpty()) {
                got = null;
                sequential = false;
              }
            } catch (Exception e) {
              sequential = false;
            }
          }
          if (got != null) {
            // keep the frames that land on our 24 fps grid
            for (int j = 0; j < got.size() && i < count; j++) {
              int src = target + j;
              Bitmap b = got.get(j);
              if (Math.round(i * srcFps / fps) == src) {
                save(b, tw, th, new java.io.File(dir, String.format(Locale.US, "f%05d.jpg", i)));
                i++;
                written++;
              }
              b.recycle();
            }
            if (Math.round(i * srcFps / fps) < target + got.size()) continue;
          } else {
            Bitmap b = r.getScaledFrameAtTime(i * 1000_000L / (long) fps, MediaMetadataRetriever.OPTION_CLOSEST, tw, th);
            if (b != null) {
              save(b, tw, th, new java.io.File(dir, String.format(Locale.US, "f%05d.jpg", i)));
              b.recycle();
              written++;
            }
            i++;
          }
          int pct = Math.round(i * 100f / count);
          main.post(() -> { if (videoProgress != null) videoProgress.setText("جارٍ تجهيز الفيديو… " + pct + "٪"); });
        }
        if (written == 0) throw new Exception();
        Layer l = new Layer(Layer.VIDEO);
        l.path = dir.getAbsolutePath();
        l.frames = written;
        l.fps = fps;
        l.aspect = th / (float) tw;
        l.scale = l.aspect > 1.2f ? .4f : .6f;
        l.radius = .06f;
        main.post(() -> {
          if (videoProgress != null) videoProgress.setVisibility(View.GONE);
          addLayer(l);
        });
      } catch (Exception e) {
        main.post(() -> {
          if (videoProgress != null) videoProgress.setVisibility(View.GONE);
          Ui.toast(this, "ما قدرت أجهّز الفيديو");
        });
      } finally {
        try { r.release(); } catch (Exception ignored) {}
      }
    });
  }

  private static void save(Bitmap b, int w, int h, java.io.File f) throws java.io.IOException {
    Bitmap s = b.getWidth() == w && b.getHeight() == h ? b : Bitmap.createScaledBitmap(b, w, h, true);
    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
      s.compress(Bitmap.CompressFormat.JPEG, 84, o);
    }
    if (s != b) s.recycle();
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
    TextView label = (TextView) toolButtons.get(5).getChildAt(1);
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
    main.removeCallbacks(saveLayers);
    Layer.save(this, layers);
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
    loadAudio();
    if (ExportService.status.running) showSheet(true);
  }

  private void setupPlayer() {
    player = new ExoPlayer.Builder(this).build();
    style.clock = clock;
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
      clock.update(pos, player.isPlaying(), timeline.start());
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
    cancelPanelAnimation();
    if (timelineAnim != null) {
      timelineAnim.removeAllListeners();
      timelineAnim.cancel();
    }
    main.removeCallbacksAndMessages(null);
    if (player != null) player.release();
    player = null;
    io.shutdownNow();
    thumbsIo.shutdownNow();
    super.onDestroy();
  }
}
