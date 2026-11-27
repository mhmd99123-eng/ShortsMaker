package com.mohammed.shorts;

import android.content.Context;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Something drawn on top of the Short: text, an image or sticker, a small looping video, or an
 * audio wave. Positions are relative to the frame so the same layer looks identical in the
 * 720p preview and the 1080p export.
 */
final class Layer {
  static final int TEXT = 0, IMAGE = 1, VIDEO = 2, WAVE = 3;

  String id = UUID.randomUUID().toString();
  int type;
  /** Centre, 0…1 from the top-left of the frame. */
  float x = .5f, y = .5f;
  /** TEXT: text height ÷ frame width. Others: box width ÷ frame width. */
  float scale = .3f;
  float rotation = 0f;
  float opacity = 1f;

  // text
  String text = "";
  String font = Fonts.DEFAULT;
  int color = 0xffffffff;
  int align = 0; // 0 centre, 1 right, 2 left
  float stroke = 0f; // outline width ÷ text height
  int strokeColor = 0xff000000;
  int bg = 0; // 0 none, 1 solid box, 2 see-through box
  int bgColor = 0xff000000;
  boolean shadow = true;

  // image / video
  String path = "";
  float aspect = 1f; // height ÷ width
  float radius = 0f; // ÷ box width
  int frames = 0;
  float fps = 24f;

  // wave
  int waveStyle = 0;
  int bars = 32;
  boolean gradient = true;
  float sensitivity = 1f;

  static Layer text(String s) {
    Layer l = new Layer();
    l.type = TEXT;
    l.text = s;
    l.scale = .07f;
    l.stroke = .08f;
    return l;
  }

  static Layer sticker(String emoji) {
    Layer l = text(emoji);
    l.scale = .16f;
    l.stroke = 0f;
    l.shadow = true;
    return l;
  }

  static Layer wave() {
    Layer l = new Layer();
    l.type = WAVE;
    l.scale = .78f;
    l.aspect = .28f;
    l.y = .8f;
    return l;
  }

  boolean animated() {
    return type == WAVE || (type == VIDEO && frames > 1);
  }

  String label() {
    switch (type) {
      case TEXT:
        String t = text.replace('\n', ' ').trim();
        return t.isEmpty() ? "نص" : (t.length() > 22 ? t.substring(0, 22) + "…" : t);
      case IMAGE:
        return "صورة";
      case VIDEO:
        return "فيديو";
      default:
        return "موجة صوتية";
    }
  }

  Layer copy() {
    try {
      return fromJson(toJson());
    } catch (Exception e) {
      return this;
    }
  }

  JSONObject toJson() throws Exception {
    JSONObject o = new JSONObject();
    o.put("id", id).put("type", type).put("x", x).put("y", y).put("scale", scale).put("rotation", rotation).put("opacity", opacity);
    o.put("text", text).put("font", font).put("color", color).put("align", align).put("stroke", stroke).put("strokeColor", strokeColor);
    o.put("bg", bg).put("bgColor", bgColor).put("shadow", shadow);
    o.put("path", path).put("aspect", aspect).put("radius", radius).put("frames", frames).put("fps", fps);
    o.put("waveStyle", waveStyle).put("bars", bars).put("gradient", gradient).put("sensitivity", sensitivity);
    return o;
  }

  static Layer fromJson(JSONObject o) {
    Layer l = new Layer();
    l.id = o.optString("id", l.id);
    l.type = o.optInt("type", TEXT);
    l.x = (float) o.optDouble("x", l.x);
    l.y = (float) o.optDouble("y", l.y);
    l.scale = (float) o.optDouble("scale", l.scale);
    l.rotation = (float) o.optDouble("rotation", 0);
    l.opacity = (float) o.optDouble("opacity", 1);
    l.text = o.optString("text", "");
    l.font = o.optString("font", Fonts.DEFAULT);
    l.color = o.optInt("color", l.color);
    l.align = o.optInt("align", 0);
    l.stroke = (float) o.optDouble("stroke", 0);
    l.strokeColor = o.optInt("strokeColor", l.strokeColor);
    l.bg = o.optInt("bg", 0);
    l.bgColor = o.optInt("bgColor", l.bgColor);
    l.shadow = o.optBoolean("shadow", true);
    l.path = o.optString("path", "");
    l.aspect = (float) o.optDouble("aspect", 1);
    l.radius = (float) o.optDouble("radius", 0);
    l.frames = o.optInt("frames", 0);
    l.fps = (float) o.optDouble("fps", 24);
    l.waveStyle = o.optInt("waveStyle", 0);
    l.bars = o.optInt("bars", 32);
    l.gradient = o.optBoolean("gradient", true);
    l.sensitivity = (float) o.optDouble("sensitivity", 1);
    return l;
  }

  // ---------------------------------------------------------------- storage

  static File dir(Context c) {
    File d = new File(c.getFilesDir(), "layers");
    d.mkdirs();
    return d;
  }

  static List<Layer> load(Context c) {
    List<Layer> out = new ArrayList<>();
    File f = new File(c.getFilesDir(), "layers.json");
    if (!f.exists()) return out;
    try {
      JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
      for (int i = 0; i < a.length(); i++) {
        Layer l = fromJson(a.getJSONObject(i));
        if ((l.type == IMAGE || l.type == VIDEO) && !new File(l.path).exists()) continue;
        out.add(l);
      }
    } catch (Exception ignored) {
    }
    return out;
  }

  static void save(Context c, List<Layer> layers) {
    try {
      JSONArray a = new JSONArray();
      for (Layer l : layers) a.put(l.toJson());
      File tmp = new File(c.getFilesDir(), "layers.json.tmp");
      Files.write(tmp.toPath(), a.toString().getBytes(StandardCharsets.UTF_8));
      tmp.renameTo(new File(c.getFilesDir(), "layers.json"));
    } catch (Exception ignored) {
    }
  }

  /** Removes image/video files that no layer uses any more. */
  static void cleanFiles(Context c, List<Layer> layers) {
    File[] files = dir(c).listFiles();
    if (files == null) return;
    for (File f : files) {
      boolean used = false;
      for (Layer l : layers) used |= f.getAbsolutePath().equals(l.path);
      if (!used) deleteTree(f);
    }
  }

  private static void deleteTree(File f) {
    File[] kids = f.listFiles();
    if (kids != null) for (File k : kids) deleteTree(k);
    f.delete();
  }
}
