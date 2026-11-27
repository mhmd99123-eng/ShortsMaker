package com.mohammed.shorts;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Built-in system fonts plus any TTF/OTF the user imports (kept in the app's files). */
final class Fonts {
  static final String DEFAULT = "sys:sans-serif-black";
  private static final String[][] BUILT_IN = {
    {"sys:sans-serif-black", "عريض"},
    {"sys:sans-serif-medium", "متوسط"},
    {"sys:sans-serif", "عادي"},
    {"sys:sans-serif-light", "نحيف"},
    {"sys:sans-serif-condensed", "مكثّف"},
    {"sys:serif", "كلاسيكي"},
    {"sys:monospace", "كود"},
  };
  private static final ConcurrentHashMap<String, Typeface> cache = new ConcurrentHashMap<>();

  static final class Entry {
    final String key, name;

    Entry(String key, String name) {
      this.key = key;
      this.name = name;
    }
  }

  static File dir(Context c) {
    File d = new File(c.getFilesDir(), "fonts");
    d.mkdirs();
    return d;
  }

  static List<Entry> all(Context c) {
    List<Entry> out = new ArrayList<>();
    File[] files = dir(c).listFiles();
    if (files != null) {
      Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
      for (File f : files) out.add(new Entry("file:" + f.getAbsolutePath(), f.getName().replaceAll("\\.(ttf|otf|TTF|OTF)$", "")));
    }
    for (String[] b : BUILT_IN) out.add(new Entry(b[0], b[1]));
    return out;
  }

  static Typeface get(String key) {
    if (key == null) key = DEFAULT;
    Typeface t = cache.get(key);
    if (t != null) return t;
    try {
      if (key.startsWith("file:")) {
        File f = new File(key.substring(5));
        t = f.exists() ? new Typeface.Builder(f).build() : null;
      } else {
        String fam = key.substring(4);
        boolean bold = fam.equals("sans-serif-condensed") || fam.equals("serif");
        t = Typeface.create(fam, bold ? Typeface.BOLD : Typeface.NORMAL);
      }
    } catch (Exception ignored) {
    }
    if (t == null) t = Typeface.create("sans-serif-black", Typeface.NORMAL);
    cache.put(key, t);
    return t;
  }

  /** Copies a font file into the app and returns its key, or throws with an Arabic message. */
  static String importFont(Context c, Uri uri) throws Exception {
    String name = "font.ttf";
    try (Cursor cur = c.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (cur != null && cur.moveToFirst() && cur.getString(0) != null) name = cur.getString(0);
    }
    name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
    if (!name.toLowerCase().matches(".*\\.(ttf|otf)$")) name = name + ".ttf";
    File out = new File(dir(c), name);
    try (InputStream in = c.getContentResolver().openInputStream(uri);
        OutputStream o = new FileOutputStream(out)) {
      byte[] buf = new byte[1 << 16];
      int n;
      while ((n = in.read(buf)) != -1) o.write(buf, 0, n);
    }
    Typeface t = null;
    try {
      t = new Typeface.Builder(out).build();
    } catch (Exception ignored) {
    }
    if (t == null) {
      out.delete();
      throw new Exception("هذا الملف مو خط صالح (TTF أو OTF)");
    }
    String key = "file:" + out.getAbsolutePath();
    cache.put(key, t);
    return key;
  }

  static void delete(String key) {
    if (key != null && key.startsWith("file:")) {
      new File(key.substring(5)).delete();
      cache.remove(key);
    }
  }
}
