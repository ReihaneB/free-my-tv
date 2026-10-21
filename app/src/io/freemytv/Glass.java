package io.freemytv;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Liquid Glass, done the way the GPU does it.
 *
 * The band behind the chrome is uploaded once, and a fragment shader turns it into glass for up to six rounded
 * shapes at the same time: the top bar, the search pill, the three capsules, the focused tab. Every shape gets the
 * same treatment — a blur, a lens that bends the picture along its border (sampling *outside* it, so the world
 * around the glass slides into the glass), a red/blue split on that border, and a rim light where the edge faces
 * the light.
 *
 * It all comes back as a bitmap, so the rest of the home stays plain Canvas: no GLSurfaceView, no second window,
 * nothing the DPAD has to fight with. A private EGL context on a 1x1 pbuffer owns a framebuffer object the size of
 * the band, which keeps glReadPixels small.
 */
final class Glass {
    static final int MAX_SHAPES = 6;

    private static final String VERT =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aTex;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  vTex = aTex;\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FRAG =
            "precision highp float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec2 uTexel;\n" +
            "uniform vec2 uSize;\n" +
            "uniform vec4 uRect[" + MAX_SHAPES + "];\n" +   // cx, cy, half width, half height, in pixels
            "uniform float uRad[" + MAX_SHAPES + "];\n" +
            "uniform int uCount;\n" +
            "uniform float uEdge;\n" +    // width of the lens band along every border, in pixels
            "uniform float uSat;\n" +
            "\n" +
            "float sdRound(vec2 p, vec2 b, float r) {\n" +
            "  vec2 q = abs(p) - b + r;\n" +
            "  return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n" +
            "}\n" +
            "\n" +
            "vec3 blur(vec2 uv) {\n" +
            "  vec3 s = vec3(0.0);\n" +
            "  for (int i = -1; i <= 1; i++)\n" +
            "    for (int j = -1; j <= 1; j++)\n" +
            "      s += texture2D(uTex, uv + vec2(float(i) * uTexel.x * 1.7, float(j) * uTexel.y * 1.7)).rgb;\n" +
            "  return s * (1.0 / 9.0);\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "  vec2 px = vTex * uSize;\n" +
            "  float best = 1e9;\n" +
            "  vec4 sel = vec4(0.0);\n" +
            "  float selR = 0.0;\n" +
            "  for (int i = 0; i < " + MAX_SHAPES + "; i++) {\n" +
            "    if (i >= uCount) break;\n" +
            "    float d = sdRound(px - uRect[i].xy, uRect[i].zw, uRad[i]);\n" +
            "    if (d < best) { best = d; sel = uRect[i]; selR = uRad[i]; }\n" +
            "  }\n" +
            "  vec3 c = blur(vTex);\n" +
            "  if (best < 0.0) {                       // inside a shape: this pixel is glass\n" +
            "    float e = -best;                      // distance to its border\n" +
            "    vec2 n = vec2(0.0);\n" +
            "    float bend = 0.0;\n" +
            "    if (e < uEdge) {\n" +
            "      float dx = sdRound(px + vec2(1.0, 0.0) - sel.xy, sel.zw, selR)\n" +
            "               - sdRound(px - vec2(1.0, 0.0) - sel.xy, sel.zw, selR);\n" +
            "      float dy = sdRound(px + vec2(0.0, 1.0) - sel.xy, sel.zw, selR)\n" +
            "               - sdRound(px - vec2(0.0, 1.0) - sel.xy, sel.zw, selR);\n" +
            "      n = normalize(vec2(dx, dy) + vec2(1e-5));\n" +
            "      float t = 1.0 - e / uEdge;\n" +
            "      bend = t * t * uEdge * 0.9;        // the border looks outwards: the picture slides in\n" +
            "    }\n" +
            "    if (bend > 0.2) {\n" +
            "      vec2 dir = n * uTexel * bend;\n" +
            "      c.r = blur(vTex + dir * 1.08).r;\n" +
            "      c.g = blur(vTex + dir).g;\n" +
            "      c.b = blur(vTex + dir * 0.92).b;\n" +
            "    }\n" +
            "    if (e < 1.4) {                       // the lit edge, only where it faces up\n" +
            "      c += vec3((1.0 - e / 1.4) * max(0.0, -n.y) * 0.5);\n" +
            "    }\n" +
            "  }\n" +
            "  float l = dot(c, vec3(0.2126, 0.7152, 0.0722));\n" +
            "  c = mix(vec3(l), c, uSat);\n" +
            "  // a hair of noise: it kills the banding a TV shows on long, quiet gradients\n" +
            "  float n = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);\n" +
            "  c += (n - 0.5) * (1.6 / 255.0);\n" +
            "  gl_FragColor = vec4(c, 1.0);\n" +
            "}\n";

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surf = EGL14.EGL_NO_SURFACE;
    private int prog, tex, fbo, outTex;
    private int uTex, uTexel, uSize, uRect, uRad, uCount, uEdge, uSat;
    private int vw, vh;
    private ByteBuffer px;
    private Bitmap out;
    private int[] id = new int[1];
    private boolean ok;

    private static FloatBuffer quad() {
        float[] v = {-1f, -1f, 0f, 0f,  1f, -1f, 1f, 0f,  -1f, 1f, 0f, 1f,  1f, 1f, 1f, 1f};
        FloatBuffer b = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(v).position(0);
        return b;
    }

    boolean init() {
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            if (!EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return false;
            int[] attr = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE};
            EGLConfig[] cfg = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0) || n[0] == 0) return false;
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) return false;
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0],
                    new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (surf == EGL14.EGL_NO_SURFACE) return false;
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return false;

            prog = GLES20.glCreateProgram();
            int vs = shader(GLES20.GL_VERTEX_SHADER, VERT);
            int fs = shader(GLES20.GL_FRAGMENT_SHADER, FRAG);
            if (vs == 0 || fs == 0) return false;
            GLES20.glAttachShader(prog, vs);
            GLES20.glAttachShader(prog, fs);
            GLES20.glLinkProgram(prog);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) return false;
            uTex = GLES20.glGetUniformLocation(prog, "uTex");
            uTexel = GLES20.glGetUniformLocation(prog, "uTexel");
            uSize = GLES20.glGetUniformLocation(prog, "uSize");
            uRect = GLES20.glGetUniformLocation(prog, "uRect");
            uRad = GLES20.glGetUniformLocation(prog, "uRad");
            uCount = GLES20.glGetUniformLocation(prog, "uCount");
            uEdge = GLES20.glGetUniformLocation(prog, "uEdge");
            uSat = GLES20.glGetUniformLocation(prog, "uSat");
            GLES20.glGenTextures(1, id, 0);
            tex = id[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            ok = true;
            return true;
        } catch (Throwable t) {
            ok = false;
            return false;
        }
    }

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] st = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0);
        return st[0] == 0 ? 0 : s;
    }

    boolean ready() {
        return ok;
    }

    /**
     * @param src    the band behind the chrome, already small
     * @param shapes cx, cy, half width, half height, radius — five floats per shape, in the bitmap's own pixels
     * @param count  how many shapes are filled in
     * @param edge   width of the lens along each border, in pixels
     */
    Bitmap render(Bitmap src, float[] shapes, int count, float edge, float sat) {
        if (!ok) return null;
        try {
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) return null;
            int w = src.getWidth(), h = src.getHeight();
            if (out == null || vw != w || vh != h) {
                vw = w;
                vh = h;
                if (out != null) out.recycle();
                out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                px = ByteBuffer.allocateDirect(w * h * 4);
                if (outTex != 0) GLES20.glDeleteTextures(1, new int[]{outTex}, 0);
                GLES20.glGenTextures(1, id, 0);
                outTex = id[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outTex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                if (fbo != 0) GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
                GLES20.glGenFramebuffers(1, id, 0);
                fbo = id[0];
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D, outTex, 0);
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    ok = false;
                    return null;
                }
            }

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, w, h);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(prog);
            int aPos = GLES20.glGetAttribLocation(prog, "aPos");
            int aTex = GLES20.glGetAttribLocation(prog, "aTex");
            FloatBuffer q = quad();
            GLES20.glEnableVertexAttribArray(aPos);
            q.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, q);
            GLES20.glEnableVertexAttribArray(aTex);
            q.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, q);
            GLES20.glUniform1i(uTex, 0);
            GLES20.glUniform2f(uTexel, 1f / w, 1f / h);
            GLES20.glUniform2f(uSize, w, h);
            int n = Math.max(1, Math.min(count, MAX_SHAPES));
            GLES20.glUniform1i(uCount, n);
            GLES20.glUniform1f(uEdge, edge);
            GLES20.glUniform1f(uSat, sat);

            float[] rects = new float[MAX_SHAPES * 4];
            float[] radii = new float[MAX_SHAPES];
            for (int i = 0; i < n; i++) {
                int s = i * 5;
                rects[i * 4] = shapes[s];
                rects[i * 4 + 1] = shapes[s + 1];
                rects[i * 4 + 2] = Math.max(1f, shapes[s + 2]);
                rects[i * 4 + 3] = Math.max(1f, shapes[s + 3]);
                radii[i] = Math.max(0f, Math.min(shapes[s + 4], Math.min(shapes[s + 2], shapes[s + 3])));
            }
            GLES20.glUniform4fv(uRect, MAX_SHAPES, rects, 0);
            GLES20.glUniform1fv(uRad, MAX_SHAPES, radii, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glFinish();

            px.position(0);
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
            px.position(0);
            out.copyPixelsFromBuffer(px);
            return out;
        } catch (Throwable t) {
            ok = false;
            return null;
        }
    }

    void release() {
        try {
            if (dpy != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
                if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
                EGL14.eglTerminate(dpy);
            }
        } catch (Throwable ignored) {
        }
        dpy = EGL14.EGL_NO_DISPLAY;
        ok = false;
    }
}
