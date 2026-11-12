package com.codescv.delta.emulation

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import com.codescv.delta.settings.VideoFilter
import com.codescv.delta.skins.Box
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One game screen: destination rect in view px and source sub-rect in core px (null = all). */
data class ScreenQuad(val dest: Box, val source: Box?)

/**
 * GLES2 renderer for the core's RGBA frame: one quad per skin screen (NDS: two sub-rects of
 * the single 256x384 texture), with a GLSL port of Delta's Metal shader
 * (0 Sharp Pixels / 1 LCD Grid / 2 CRT Scanlines / 3 Smooth Bilinear).
 */
class GameGlRenderer : GLSurfaceView.Renderer {
    @Volatile var session: EmulatorSession? = null
    @Volatile var quads: List<ScreenQuad> = emptyList()
    @Volatile var filter: VideoFilter = VideoFilter.SHARP_PIXELS
    @Volatile var integerScaling: Boolean = false

    private var program = 0
    private var texture = 0
    private var texW = 0
    private var texH = 0
    private var viewW = 1
    private var viewH = 1
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uTexSize = 0
    private var uOutSize = 0
    private var uMode = 0
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexSize = GLES20.glGetUniformLocation(program, "uTexSize")
        uOutSize = GLES20.glGetUniformLocation(program, "uOutSize")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texW = 0
        texH = 0
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = maxOf(width, 1)
        viewH = maxOf(height, 1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return
        var fw = 0
        var fh = 0
        s.withFrame { bmp: Bitmap? ->
            if (bmp != null && !bmp.isRecycled) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                if (bmp.width != texW || bmp.height != texH) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    texW = bmp.width
                    texH = bmp.height
                } else {
                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bmp)
                }
                fw = bmp.width
                fh = bmp.height
            }
        }
        if (fw == 0 || fh == 0 || program == 0) return
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1i(uMode, shaderMode(filter))
        for (q in quads) drawQuad(q, fw, fh)
    }

    private fun drawQuad(q: ScreenQuad, fw: Int, fh: Int) {
        val src = q.source ?: Box(0f, 0f, fw.toFloat(), fh.toFloat())
        var d = q.dest
        if (integerScaling) d = integerScaled(d, src)
        if (d.width <= 0 || d.height <= 0) return
        val u0 = src.x / fw
        val v0 = src.y / fh
        val u1 = src.maxX / fw
        val v1 = src.maxY / fh
        val x0 = d.x / viewW * 2f - 1f
        val x1 = d.maxX / viewW * 2f - 1f
        val y0 = 1f - d.y / viewH * 2f
        val y1 = 1f - d.maxY / viewH * 2f
        vertices.clear()
        vertices.put(floatArrayOf(x0, y1, u0, v1, x1, y1, u1, v1, x0, y0, u0, v0, x1, y0, u1, v0))
        vertices.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aPos)
        vertices.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(aUv)
        // Texel space of this screen's source rect (shader works per source pixel).
        GLES20.glUniform2f(uTexSize, fw.toFloat(), fh.toFloat())
        GLES20.glUniform2f(uOutSize, d.width * fw / src.width, d.height * fh / src.height)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        private const val TAG = "DeltaGl"

        fun shaderMode(f: VideoFilter) = when (f) {
            VideoFilter.SHARP_PIXELS -> 0
            VideoFilter.LCD_GRID -> 1
            VideoFilter.CRT_SCANLINES -> 2
            VideoFilter.SMOOTH_BILINEAR -> 3
        }

        /** Largest whole-number scale of [src] that fits in [dest], centered. */
        fun integerScaled(dest: Box, src: Box): Box {
            val scale = minOf(dest.width / src.width, dest.height / src.height).toInt().coerceAtLeast(1)
            val w = src.width * scale
            val h = src.height * scale
            if (w > dest.width + 0.5f || h > dest.height + 0.5f) return dest
            return Box(dest.midX - w / 2f, dest.midY - h / 2f, w, h)
        }

        private const val VERTEX = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
              vUv = aUv;
              gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        // Port of MetalRenderer.swift lr_fragment.
        private const val FRAGMENT = """
            precision highp float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec2 uTexSize;
            uniform vec2 uOutSize;
            uniform int uMode;
            void main() {
              vec2 uv = vUv;
              if (uMode == 3) {
                gl_FragColor = vec4(texture2D(uTex, uv).rgb, 1.0);
                return;
              }
              vec2 texel = uv * uTexSize;
              vec2 scale = max(floor(uOutSize / uTexSize), vec2(1.0));
              vec2 base = floor(texel);
              vec2 centerDist = fract(texel) - 0.5;
              vec2 region = 0.5 - 0.5 / scale;
              vec2 f = (centerDist - clamp(centerDist, -region, region)) * scale + 0.5;
              vec3 color = texture2D(uTex, (base + f) / uTexSize).rgb;
              vec2 cell = fract(texel);
              if (uMode == 1) {
                float gx = smoothstep(0.0, 0.14, cell.x) * smoothstep(1.0, 0.86, cell.x);
                float gy = smoothstep(0.0, 0.14, cell.y) * smoothstep(1.0, 0.86, cell.y);
                color *= mix(0.62, 1.0, gx * gy);
                float sub = cell.x * 3.0;
                vec3 mask = sub < 1.0 ? vec3(1.0, 0.78, 0.78) : (sub < 2.0 ? vec3(0.78, 1.0, 0.78) : vec3(0.78, 0.78, 1.0));
                color *= mix(vec3(1.0), mask, 0.45) * 1.12;
              } else if (uMode == 2) {
                float d = cell.y - 0.5;
                float scan = exp(-d * d * 10.0);
                color *= mix(0.45, 1.15, scan);
                float column = mod(floor(gl_FragCoord.x), 3.0);
                vec3 mask = column < 0.5 ? vec3(1.0, 0.82, 0.82) : (column < 1.5 ? vec3(0.82, 1.0, 0.82) : vec3(0.82, 0.82, 1.0));
                color *= mask * 1.08;
              }
              gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
            }
        """

        private fun compile(type: Int, src: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "shader compile failed: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun buildProgram(vs: String, fs: String): Int {
            val v = compile(GLES20.GL_VERTEX_SHADER, vs)
            val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
            if (v == 0 || f == 0) return 0
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, v)
            GLES20.glAttachShader(p, f)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) {
                Log.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p))
                return 0
            }
            return p
        }
    }
}

/** GLSurfaceView hosting [GameGlRenderer], redrawn on demand when the core publishes a frame. */
class GameSurfaceView(context: Context) : GLSurfaceView(context) {
    val renderer = GameGlRenderer()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 0, 0, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }
}
