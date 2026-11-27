package com.mohammed.shorts;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayerResizeTest {
  private static final float TOLERANCE = .001f;
  private final LayerResize.Box box = new LayerResize.Box(100, 100, 80, 40);

  @Test
  public void rotatedRightHandleKeepsLeftEdgeFixed() {
    LayerResize.Box result = LayerResize.drag(box, 90, 1, 0, -12, 30, false, 24, 24, 200, 200);
    assertBox(result, 100, 115, 110, 40);
    assertEquals(box.y - box.width / 2, result.y - result.width / 2, TOLERANCE);
  }

  @Test
  public void topHandleCannotShrinkPastMinimumHeight() {
    LayerResize.Box result = LayerResize.drag(box, 0, 0, -1, 30, 50, false, 24, 24, 200, 200);
    assertBox(result, 100, 108, 80, 24);
    assertEquals(box.y + box.height / 2, result.y + result.height / 2, TOLERANCE);
  }

  @Test
  public void rotatedCornerScalesUniformlyAroundOppositeCorner() {
    // At 90 degrees, dragging the bottom-right corner (-20, 40) is half the original diagonal.
    LayerResize.Box result = LayerResize.drag(box, 90, 1, 1, -20, 40, false, 24, 24, 200, 200);
    assertBox(result, 90, 120, 120, 60);
    assertEquals(box.x + box.height / 2, result.x + result.height / 2, TOLERANCE);
    assertEquals(box.y - box.width / 2, result.y - result.width / 2, TOLERANCE);
  }

  @Test
  public void lockedSideKeepsAspectAndOppositeEdge() {
    LayerResize.Box result = LayerResize.drag(box, 0, -1, 0, -40, 0, true, 24, 24, 200, 200);
    assertBox(result, 80, 100, 120, 60);
    assertEquals(box.x + box.width / 2, result.x + result.width / 2, TOLERANCE);
  }

  @Test
  public void frameBoundaryPreservesTheAnchor() {
    LayerResize.Box start = new LayerResize.Box(.9f, .9f, .4f, .2f);
    LayerResize.Box drag = LayerResize.drag(start, 0, 1, 0, .6f, 0, true, .1f, .1f, 2, 2);
    LayerResize.Box result = LayerResize.limitCentre(start, drag, 16f / 9f);
    assertBox(result, 1f, .9f, .6f, .3f);
    assertEquals(start.x - start.width / 2, result.x - result.width / 2, TOLERANCE);
  }

  @Test
  public void rotationSnapsOnlyWithinFourDegrees() {
    assertEquals(45, LayerResize.snapRotation(41, 45), TOLERANCE);
    assertEquals(40, LayerResize.snapRotation(40, 45), TOLERANCE);
    assertEquals(-135, LayerResize.snapRotation(228, 45), TOLERANCE);
  }

  private static void assertBox(LayerResize.Box result, float x, float y, float width, float height) {
    assertEquals(x, result.x, TOLERANCE);
    assertEquals(y, result.y, TOLERANCE);
    assertEquals(width, result.width, TOLERANCE);
    assertEquals(height, result.height, TOLERANCE);
  }
}
