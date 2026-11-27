package com.mohammed.shorts;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.transformer.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Exports a list of parts one after another, each as its own vertical Short. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class ExportService extends Service {
  static final String CHANNEL = "export";
  static final int NOTE = 81, NOTE_DONE = 82;

  /** What the editor shows while exporting. Read on the UI thread only. */
  static final class Status {
    boolean running;
    int index, total, percent;
    String message = "";
    String error;
    final List<Uri> outputs = new ArrayList<>();
  }

  static final Status status = new Status();
  static ExportService active;

  private final Handler main = new Handler(Looper.getMainLooper());
  private Transformer transformer;
  private boolean cancelled;
  private Uri source;
  private String baseName;
  private List<Clip> clips;
  private ShortStyle style;
  private boolean cap30;
  private File temp;
  private long clipStartedAt;

  @Override
  public IBinder onBind(Intent i) {
    return null;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    active = this;
    getSystemService(NotificationManager.class)
        .createNotificationChannel(
            new NotificationChannel(CHANNEL, "تصدير الشورتات", NotificationManager.IMPORTANCE_LOW));
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if ("cancel".equals(intent.getAction())) {
      cancel();
      return START_NOT_STICKY;
    }
    if (status.running) return START_NOT_STICKY;
    source = intent.getData();
    clips = Clip.decode(intent.getLongArrayExtra("clips"));
    if (source == null || clips.isEmpty()) {
      stopSelf();
      return START_NOT_STICKY;
    }
    baseName = intent.getStringExtra("name");
    cap30 = intent.getBooleanExtra("cap30", false);
    style = ShortStyle.load(this);
    style.outW = intent.getIntExtra("w", 1080);
    style.outH = intent.getIntExtra("h", 1920);
    cancelled = false;
    status.running = true;
    status.error = null;
    status.outputs.clear();
    status.total = clips.size();
    status.index = 0;
    status.percent = 0;
    status.message = "جارٍ التجهيز…";
    Notification n = notification(status.message, -1);
    if (Build.VERSION.SDK_INT >= 35)
      startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
    else startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    next();
    return START_NOT_STICKY;
  }

  private void next() {
    if (cancelled) {
      finish("تم الإلغاء", null);
      return;
    }
    if (status.index >= clips.size()) {
      finish(
          clips.size() == 1 ? "تم حفظ الشورت في الاستديو" : "تم حفظ " + clips.size() + " شورتات في الاستديو",
          null);
      return;
    }
    Clip c = clips.get(status.index);
    status.percent = 0;
    update("شورت " + (status.index + 1) + " من " + status.total + " · جارٍ البدء…");
    try {
      temp = new File(getCacheDir(), "short-" + UUID.randomUUID() + ".mp4");
      float fps = 30;
      try {
        fps = Clip.Info.read(this, source).fps;
      } catch (Exception ignored) {
      }
      boolean high = !cap30 && fps > 40;
      int bitrate = (int) Math.min(40_000_000L, (long) style.outW * style.outH * (high ? 7 : 5));
      transformer =
          new Transformer.Builder(this)
              .setVideoMimeType(MimeTypes.VIDEO_H264)
              .setAudioMimeType(MimeTypes.AUDIO_AAC)
              .setEncoderFactory(
                  new DefaultEncoderFactory.Builder(this)
                      .setEnableFallback(true)
                      .setRequestedVideoEncoderSettings(
                          new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                      .build())
              .addListener(
                  new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                      if (cancelled) {
                        finish("تم الإلغاء", null);
                        return;
                      }
                      new Thread(() -> save(c)).start();
                    }

                    @Override
                    public void onError(
                        Composition composition, ExportResult result, ExportException e) {
                      finish(null, "تعذّر التصدير على هذا الجهاز (" + e.getErrorCodeName() + ")");
                    }
                  })
              .build();
      List<Effect> video = new ArrayList<>();
      if (cap30 && fps > 40) video.add(FrameDropEffect.createDefaultFrameDropEffect(30f));
      video.add(new ShortFrameEffect(style));
      MediaItem item =
          new MediaItem.Builder()
              .setUri(source)
              .setClippingConfiguration(
                  new MediaItem.ClippingConfiguration.Builder()
                      .setStartPositionMs(c.startMs)
                      .setEndPositionMs(c.endMs)
                      .build())
              .build();
      EditedMediaItem edited =
          new EditedMediaItem.Builder(item)
              .setEffects(new Effects(Collections.emptyList(), video))
              .build();
      Composition composition =
          new Composition.Builder(
                  new EditedMediaItemSequence.Builder(Collections.singletonList(edited)).build())
              .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
              .build();
      clipStartedAt = SystemClock.elapsedRealtime();
      transformer.start(composition, temp.getAbsolutePath());
      main.post(poll);
    } catch (Exception e) {
      finish(null, "تعذّر بدء التصدير: " + e.getMessage());
    }
  }

  private final Runnable poll =
      new Runnable() {
        public void run() {
          if (!status.running || transformer == null || cancelled) return;
          ProgressHolder h = new ProgressHolder();
          if (transformer.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) {
            status.percent = Math.min(97, h.progress);
            update(
                "شورت " + (status.index + 1) + " من " + status.total + " · " + h.progress + "٪"
                    + remaining(h.progress));
          }
          main.postDelayed(this, 500);
        }
      };

  private String remaining(int percent) {
    if (percent < 4) return "";
    long elapsed = SystemClock.elapsedRealtime() - clipStartedAt;
    long left = elapsed * (100 - percent) / percent / 1000;
    return left < 60 ? " · باقي " + Math.max(1, left) + " ث" : " · باقي " + (left / 60 + 1) + " د";
  }

  /** Copies the finished file into Movies/ShortsMaker so it shows up in the gallery. */
  private void save(Clip c) {
    Uri output = null;
    try {
      String clean =
          (baseName == null ? "Video" : baseName)
              .replaceAll("\\.[^.]+$", "")
              .replaceAll("[^\\p{L}\\p{N}._ -]", "_");
      if (clean.length() > 50) clean = clean.substring(0, 50);
      String name =
          clean
              + "_short"
              + (status.total > 1 ? (status.index + 1) : "")
              + "_"
              + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
              + ".mp4";
      ContentValues v = new ContentValues();
      v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
      v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
      v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ShortsMaker");
      v.put(MediaStore.Video.Media.IS_PENDING, 1);
      output = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
      if (output == null) throw new IOException("تعذّر إنشاء الملف");
      try (InputStream in = new FileInputStream(temp);
          OutputStream out = getContentResolver().openOutputStream(output, "w")) {
        byte[] buf = new byte[1 << 20];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
      }
      ContentValues ready = new ContentValues();
      ready.put(MediaStore.Video.Media.IS_PENDING, 0);
      getContentResolver().update(output, ready, null, null);
      Uri done = output;
      main.post(
          () -> {
            temp.delete();
            status.outputs.add(done);
            status.index++;
            next();
          });
    } catch (Exception e) {
      if (output != null)
        try {
          getContentResolver().delete(output, null, null);
        } catch (Exception ignored) {
        }
      main.post(() -> finish(null, "تعذّر حفظ الشورت: " + e.getMessage()));
    }
  }

  private void update(String text) {
    status.message = text;
    postNotification(NOTE, notification(text, status.percent));
  }

  private void finish(String message, String error) {
    if (!status.running) return;
    status.running = false;
    status.error = error;
    status.message = error != null ? error : message;
    main.removeCallbacks(poll);
    if (temp != null) temp.delete();
    stopForeground(STOP_FOREGROUND_REMOVE);
    if (!cancelled) {
      Notification.Builder b =
          new Notification.Builder(this, CHANNEL)
              .setSmallIcon(android.R.drawable.stat_sys_download_done)
              .setContentTitle(error != null ? "ما اكتمل التصدير" : "الشورتات جاهزة")
              .setContentText(status.message)
              .setAutoCancel(true)
              .setContentIntent(openApp());
      postNotification(NOTE_DONE, b.build());
    }
    stopSelf();
  }

  private void cancel() {
    if (!status.running) return;
    cancelled = true;
    if (transformer != null) transformer.cancel();
    finish("تم الإلغاء", null);
  }

  private PendingIntent openApp() {
    Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(
        this, 81, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private Notification notification(String text, int value) {
    Intent cancel = new Intent(this, ExportService.class).setAction("cancel");
    PendingIntent c =
        PendingIntent.getService(
            this, 83, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("صانع الشورت")
        .setContentText(text)
        .setContentIntent(openApp())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(100, Math.max(0, value), value < 0)
        .addAction(new Notification.Action.Builder(null, "إلغاء", c).build())
        .build();
  }

  private void postNotification(int id, Notification n) {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return;
    getSystemService(NotificationManager.class).notify(id, n);
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    cancel();
  }

  @Override
  public void onDestroy() {
    if (active == this) active = null;
    main.removeCallbacksAndMessages(null);
    if (status.running) {
      cancelled = true;
      if (transformer != null) transformer.cancel();
      status.running = false;
    }
    super.onDestroy();
  }
}
