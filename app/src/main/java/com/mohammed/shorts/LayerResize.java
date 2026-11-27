package com.mohammed.shorts;

/** Geometry for resizing a box in the layer's rotated axes. All distances use the same units. */
final class LayerResize {
  private LayerResize() {}

  static final class Box {
    final float x, y, width, height;

    Box(float x, float y, float width, float height) {
      this.x = x;
      this.y = y;
      this.width = width;
      this.height = height;
    }
  }

  /** Handle directions are -1, 0 or +1 on each local axis. The drag is in frame coordinates. */
  static Box drag(Box box, float rotation, int sx, int sy, float dx, float dy, boolean lockAspect,
      float minWidth, float minHeight, float maxWidth, float maxHeight) {
    double a = Math.toRadians(rotation);
    float localX = (float) (dx * Math.cos(a) + dy * Math.sin(a));
    float localY = (float) (-dx * Math.sin(a) + dy * Math.cos(a));
    float width = box.width, height = box.height;
    if (lockAspect || (sx != 0 && sy != 0)) {
      // Project onto the original line from the fixed anchor to the dragged handle.
      float vx = sx * width, vy = sy * height;
      float scale = 1f + (localX * vx + localY * vy) / (vx * vx + vy * vy);
      float minScale = Math.max(minWidth / width, minHeight / height);
      float maxScale = Math.min(maxWidth / width, maxHeight / height);
      scale = clamp(scale, minScale, maxScale);
      width *= scale;
      height *= scale;
    } else if (sx != 0) {
      width = clamp(width + sx * localX, minWidth, maxWidth);
    } else {
      height = clamp(height + sy * localY, minHeight, maxHeight);
    }
    return anchor(box, rotation, sx, sy, width, height);
  }

  /** Re-anchor using the actual rendered size, which may differ when text wraps. */
  static Box anchor(Box box, float rotation, int sx, int sy, float width, float height) {
    float localX = sx * (width - box.width) / 2f;
    float localY = sy * (height - box.height) / 2f;
    double a = Math.toRadians(rotation);
    float x = box.x + (float) (localX * Math.cos(a) - localY * Math.sin(a));
    float y = box.y + (float) (localX * Math.sin(a) + localY * Math.cos(a));
    return new Box(x, y, width, height);
  }

  /** Stop the resize at the frame boundary without shifting its fixed anchor. */
  static Box limitCentre(Box start, Box end, float frameHeight) {
    float t = Math.min(insideFraction(start.x, end.x, 1f), insideFraction(start.y, end.y, frameHeight));
    if (t == 1f) return end;
    return new Box(start.x + t * (end.x - start.x), start.y + t * (end.y - start.y),
        start.width + t * (end.width - start.width), start.height + t * (end.height - start.height));
  }

  private static float insideFraction(float start, float end, float max) {
    if (end < 0f) return clamp(-start / (end - start), 0f, 1f);
    if (end > max) return clamp((max - start) / (end - start), 0f, 1f);
    return 1f;
  }

  static float snapRotation(float rotation, int step) {
    rotation %= 360f;
    if (rotation < -180f) rotation += 360f;
    if (rotation > 180f) rotation -= 360f;
    float snap = Math.round(rotation / step) * step;
    return Math.abs(rotation - snap) <= 4f ? snap : rotation;
  }

  static float clamp(float value, float min, float max) {
    return Math.min(max, Math.max(min, value));
  }
}
