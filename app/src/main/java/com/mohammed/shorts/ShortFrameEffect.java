package com.mohammed.shorts;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

/**
 * Turns any frame into a 9:16 frame: a zoomed, blurred and dimmed copy of the video fills the
 * screen and the sharp video sits on top (optionally rounded, with a soft shadow).
 *
 * <p>The blur is done on a tiny texture (downscale → separable Gaussian → bilinear upscale), so a
 * very strong blur costs almost nothing even at 60 fps.
 */
@UnstableApi
final class ShortFrameEffect implements GlEffect {
  private final ShortStyle style;

  ShortFrameEffect(ShortStyle style) {
    this.style = style;
  }

  @Override
  public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
      throws VideoFrameProcessingException {
    return new Program(style, useHdr);
  }

  private static final String VERTEX =
      "attribute vec4 aFramePosition;\n"
          + "varying vec2 vUv;\n"
          + "void main(){ gl_Position = aFramePosition; vUv = aFramePosition.xy * 0.5 + 0.5; }\n";

  /** Crop-fill the source into the small texture, averaging 16 taps to avoid shimmering. */
  private static final String DOWN =
      "precision highp float;\n"
          + "uniform sampler2D uTex;\n"
          + "uniform vec4 uMap;\n"
          + "uniform vec2 uStep;\n"
          + "varying vec2 vUv;\n"
          + "void main(){\n"
          + "  vec2 src = uMap.zw + (vUv - 0.5) * uMap.xy;\n"
          + "  vec4 c = vec4(0.0);\n"
          + "  for (int i = 0; i < 4; i++) {\n"
          + "    for (int j = 0; j < 4; j++) {\n"
          + "      c += texture2D(uTex, src + (vec2(float(i), float(j)) - 1.5) * uStep);\n"
          + "    }\n"
          + "  }\n"
          + "  gl_FragColor = vec4(c.rgb / 16.0, 1.0);\n"
          + "}\n";

  /** 13-tap Gaussian (sigma 3) in one direction. */
  private static final String BLUR =
      "precision highp float;\n"
          + "uniform sampler2D uTex;\n"
          + "uniform vec2 uDir;\n"
          + "varying vec2 vUv;\n"
          + "void main(){\n"
          + "  vec3 c = texture2D(uTex, vUv).rgb * 0.1330;\n"
          + "  c += (texture2D(uTex, vUv + uDir).rgb + texture2D(uTex, vUv - uDir).rgb) * 0.1258;\n"
          + "  c += (texture2D(uTex, vUv + 2.0*uDir).rgb + texture2D(uTex, vUv - 2.0*uDir).rgb) * 0.1063;\n"
          + "  c += (texture2D(uTex, vUv + 3.0*uDir).rgb + texture2D(uTex, vUv - 3.0*uDir).rgb) * 0.0805;\n"
          + "  c += (texture2D(uTex, vUv + 4.0*uDir).rgb + texture2D(uTex, vUv - 4.0*uDir).rgb) * 0.0545;\n"
          + "  c += (texture2D(uTex, vUv + 5.0*uDir).rgb + texture2D(uTex, vUv - 5.0*uDir).rgb) * 0.0331;\n"
          + "  c += (texture2D(uTex, vUv + 6.0*uDir).rgb + texture2D(uTex, vUv - 6.0*uDir).rgb) * 0.0180;\n"
          + "  gl_FragColor = vec4(c / 0.9694, 1.0);\n"
          + "}\n";

  /** Background + shadow + rounded sharp video. */
  private static final String FINAL =
      "precision highp float;\n"
          + "uniform sampler2D uTex;\n"
          + "uniform sampler2D uBg;\n"
          + "uniform vec4 uRect;\n" // x, y, w, h in output pixels
          + "uniform vec4 uOut;\n" // outW, outH, radius px, shadow 0..1
          + "uniform vec4 uLook;\n" // dim, saturation, solid colour mix, unused
          + "uniform vec4 uColor;\n"
          + "varying vec2 vUv;\n"
          + "float box(vec2 p, vec2 b, float r){\n"
          + "  vec2 q = abs(p) - b + r;\n"
          + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n"
          + "}\n"
          + "void main(){\n"
          + "  vec2 px = vUv * uOut.xy;\n"
          + "  vec3 bg = mix(texture2D(uBg, vUv).rgb, uColor.rgb, uLook.z);\n"
          + "  float l = dot(bg, vec3(0.299, 0.587, 0.114));\n"
          + "  bg = clamp(mix(vec3(l), bg, uLook.y), 0.0, 1.0) * (1.0 - uLook.x);\n"
          + "  vec2 halfSize = uRect.zw * 0.5;\n"
          + "  vec2 centre = uRect.xy + halfSize;\n"
          + "  float r = min(uOut.z, min(halfSize.x, halfSize.y));\n"
          + "  float d = box(px - centre, halfSize, r);\n"
          + "  float soft = uOut.x * 0.035;\n"
          + "  bg *= 1.0 - uOut.w * 0.65 * exp(-max(d, 0.0) / soft) * step(0.0, d);\n"
          + "  vec2 local = (px - uRect.xy) / uRect.zw;\n"
          + "  vec2 o = 0.25 / uRect.zw;\n"
          + "  vec3 fg = (texture2D(uTex, local + vec2(o.x, o.y)).rgb\n"
          + "      + texture2D(uTex, local + vec2(-o.x, o.y)).rgb\n"
          + "      + texture2D(uTex, local + vec2(o.x, -o.y)).rgb\n"
          + "      + texture2D(uTex, local + vec2(-o.x, -o.y)).rgb) * 0.25;\n"
          + "  float a = 1.0 - smoothstep(-0.75, 0.75, d);\n"
          + "  gl_FragColor = vec4(mix(bg, fg, a), 1.0);\n"
          + "}\n";

  private static final class Program extends BaseGlShaderProgram {
    private final ShortStyle style;
    private final GlProgram down, blur, compose;
    private final float[] quad = GlUtil.getNormalizedCoordinateBounds();
    private int inW, inH, outW, outH;
    private int smallW, smallH;
    private int texA = -1, texB = -1, fboA = -1, fboB = -1;

    Program(ShortStyle style, boolean useHdr) throws VideoFrameProcessingException {
      super(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1);
      this.style = style;
      try {
        down = new GlProgram(VERTEX, DOWN);
        blur = new GlProgram(VERTEX, BLUR);
        compose = new GlProgram(VERTEX, FINAL);
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
    }

    @Override
    public Size configure(int inputWidth, int inputHeight) {
      inW = inputWidth;
      inH = inputHeight;
      outW = style.outW;
      outH = style.outH;
      return new Size(outW, outH);
    }

    private void ensureSmall(int w, int h) throws GlUtil.GlException {
      if (w == smallW && h == smallH && texA != -1) return;
      releaseSmall();
      smallW = w;
      smallH = h;
      texA = GlUtil.createTexture(w, h, false);
      texB = GlUtil.createTexture(w, h, false);
      fboA = GlUtil.createFboForTexture(texA);
      fboB = GlUtil.createFboForTexture(texB);
    }

    private void releaseSmall() throws GlUtil.GlException {
      if (fboA != -1) GlUtil.deleteFbo(fboA);
      if (fboB != -1) GlUtil.deleteFbo(fboB);
      if (texA != -1) GlUtil.deleteTexture(texA);
      if (texB != -1) GlUtil.deleteTexture(texB);
      texA = texB = fboA = fboB = -1;
    }

    private void draw(GlProgram p) throws GlUtil.GlException {
      p.setBufferAttribute("aFramePosition", quad, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE);
      p.bindAttributesAndUniforms();
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
      GlUtil.checkGlError();
    }

    @Override
    public void drawFrame(int inputTexId, long presentationTimeUs)
        throws VideoFrameProcessingException {
      try {
        int[] target = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, target, 0);

        // Stronger blur = smaller texture (the upscale does the rest).
        float strength = Math.max(0f, Math.min(1f, style.blur));
        int sw = Math.max(16, Math.round(220 - strength * 186));
        int sh = Math.max(16, Math.round(sw * outH / (float) outW));
        ensureSmall(sw, sh);

        // Crop-fill mapping with a little extra zoom so blurred edges never show.
        float bgZoom = 1.12f;
        float inAspect = inW / (float) inH, outAspect = outW / (float) outH;
        float sx, sy;
        if (inAspect > outAspect) {
          sx = outAspect / inAspect / bgZoom;
          sy = 1f / bgZoom;
        } else {
          sx = 1f / bgZoom;
          sy = inAspect / outAspect / bgZoom;
        }

        GlUtil.focusFramebufferUsingCurrentContext(fboA, sw, sh);
        down.use();
        down.setSamplerTexIdUniform("uTex", inputTexId, 0);
        down.setFloatsUniform("uMap", new float[] {sx, sy, .5f, .5f});
        down.setFloatsUniform("uStep", new float[] {sx / sw / 4f, sy / sh / 4f});
        draw(down);

        for (int pass = 0; pass < 2; pass++) {
          GlUtil.focusFramebufferUsingCurrentContext(fboB, sw, sh);
          blur.use();
          blur.setSamplerTexIdUniform("uTex", texA, 0);
          blur.setFloatsUniform("uDir", new float[] {1f / sw, 0f});
          draw(blur);
          GlUtil.focusFramebufferUsingCurrentContext(fboA, sw, sh);
          blur.use();
          blur.setSamplerTexIdUniform("uTex", texB, 0);
          blur.setFloatsUniform("uDir", new float[] {0f, 1f / sh});
          draw(blur);
        }

        GlUtil.focusFramebufferUsingCurrentContext(target[0], outW, outH);
        float[] rect =
            ShortStyle.foregroundRect(
                outW, outH, inW, inH, style.zoom, style.offsetX, style.offsetY);
        int color = style.bgColor;
        compose.use();
        compose.setSamplerTexIdUniform("uTex", inputTexId, 0);
        compose.setSamplerTexIdUniform("uBg", texA, 1);
        compose.setFloatsUniform("uRect", rect);
        compose.setFloatsUniform(
            "uOut", new float[] {outW, outH, style.radius * outW, style.shadow});
        compose.setFloatsUniform(
            "uLook",
            new float[] {
              style.dim, style.saturation, style.bgMode == ShortStyle.BG_COLOR ? 1f : 0f, 0f
            });
        compose.setFloatsUniform(
            "uColor",
            new float[] {
              ((color >> 16) & 255) / 255f, ((color >> 8) & 255) / 255f, (color & 255) / 255f, 1f
            });
        draw(compose);
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e, presentationTimeUs);
      }
    }

    @Override
    public void release() throws VideoFrameProcessingException {
      super.release();
      try {
        releaseSmall();
        down.delete();
        blur.delete();
        compose.delete();
      } catch (GlUtil.GlException e) {
        throw new VideoFrameProcessingException(e);
      }
    }
  }
}
