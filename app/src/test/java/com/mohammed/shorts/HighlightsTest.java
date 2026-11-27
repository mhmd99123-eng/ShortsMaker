package com.mohammed.shorts;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class HighlightsTest {
  private static Highlights.Signal signal(long durationMs) {
    int n = (int) (durationMs / Highlights.BIN_MS);
    Highlights.Signal s = new Highlights.Signal();
    s.durationMs = durationMs;
    s.loudDb = new float[n];
    s.motion = new float[n];
    s.cut = new float[n];
    s.hasAudio = true;
    Arrays.fill(s.loudDb, -40f);
    return s;
  }

  @Test
  public void findsTheLoudAndBusyPart() {
    Highlights.Signal s = signal(600_000); // 10 minutes
    // Exciting part from 5:00 to 5:30
    for (int i = 600; i < 660; i++) {
      s.loudDb[i] = -12f;
      s.motion[i] = .4f;
    }
    List<Clip> clips = Highlights.pick(s, 30_000, 3);
    assertFalse(clips.isEmpty());
    Clip best = clips.get(0);
    assertTrue("start " + best.startMs, best.startMs >= 296_000 && best.startMs <= 304_000);
    assertTrue(Math.abs(best.length() - 30_000) <= 3_000);
    for (int i = 0; i < clips.size(); i++)
      for (int j = i + 1; j < clips.size(); j++) assertFalse(clips.get(i).overlaps(clips.get(j)));
  }

  @Test
  public void shortVideoBecomesOneClip() {
    List<Clip> clips = Highlights.pick(signal(20_000), 30_000, 5);
    assertEquals(1, clips.size());
    assertEquals(0, clips.get(0).startMs);
    assertEquals(20_000, clips.get(0).endMs);
  }

  @Test
  public void neverLongerThanThreeMinutes() {
    for (Clip c : Highlights.pick(signal(1_800_000), 180_000, 5)) {
      assertTrue(c.length() <= 180_000);
      assertTrue(c.endMs <= 1_800_000);
    }
  }

  @Test
  public void landscapeFillsWidthAndIsCentred() {
    float[] r = ShortStyle.foregroundRect(1080, 1920, 1920, 1080, 1f, 0f, 0f);
    assertEquals(0f, r[0], .01f);
    assertEquals(1080f, r[2], .01f);
    assertEquals(607.5f, r[3], .01f);
    assertEquals((1920 - 607.5f) / 2, r[1], .01f);
  }

  @Test
  public void offsetMovesUpWithinFreeSpace() {
    float[] r = ShortStyle.foregroundRect(1080, 1920, 1920, 1080, 1f, 0f, 1f);
    assertEquals(1920f, r[1] + r[3], .01f); // touches the top
  }
}
