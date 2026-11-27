package com.mohammed.shorts;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.util.ArrayList;
import java.util.List;

/** A part of the source video that becomes one Short. Times are in milliseconds. */
final class Clip {
  final long startMs, endMs;
  /** 0…1 when it came from the smart search, -1 when picked by hand. */
  final float score;

  Clip(long startMs, long endMs, float score) {
    this.startMs = startMs;
    this.endMs = endMs;
    this.score = score;
  }

  long length() {
    return endMs - startMs;
  }

  boolean overlaps(Clip o) {
    return startMs < o.endMs && o.startMs < endMs;
  }

  static long[] encode(List<Clip> clips) {
    long[] out = new long[clips.size() * 2];
    for (int i = 0; i < clips.size(); i++) {
      out[i * 2] = clips.get(i).startMs;
      out[i * 2 + 1] = clips.get(i).endMs;
    }
    return out;
  }

  static List<Clip> decode(long[] data) {
    List<Clip> out = new ArrayList<>();
    if (data == null) return out;
    for (int i = 0; i + 1 < data.length; i += 2) out.add(new Clip(data[i], data[i + 1], -1));
    return out;
  }

  /** Basic facts about a video file (already corrected for rotation). */
  static final class Info {
    long durationMs;
    int width, height;
    float fps = 30;

    static Info read(Context c, Uri uri) throws Exception {
      MediaMetadataRetriever r = new MediaMetadataRetriever();
      try {
        r.setDataSource(c, uri);
        Info i = new Info();
        i.durationMs = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        int w = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int h = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        int rot = (int) parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        boolean swap = rot == 90 || rot == 270;
        i.width = swap ? h : w;
        i.height = swap ? w : h;
        String frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        long count = parse(frames);
        if (count > 0 && i.durationMs > 0) i.fps = Math.max(10, Math.min(120, count * 1000f / i.durationMs));
        if (i.durationMs <= 0 || i.width <= 0 || i.height <= 0)
          throw new Exception("هذا الملف ما فيه فيديو صالح");
        return i;
      } finally {
        try {
          r.release();
        } catch (Exception ignored) {
        }
      }
    }

    private static long parse(String s) {
      try {
        return s == null ? 0 : Long.parseLong(s.trim());
      } catch (NumberFormatException e) {
        return 0;
      }
    }
  }
}
