/*
 * Copyright (C) 2026 Ishan
 * Android Port component of mGBA, GPL-3.0.
 */
package org.mgba_emu.mgba.renderer.gl

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import org.mgba_emu.mgba.input.ui.ClassicLayoutGeometry
import org.mgba_emu.mgba.settings.model.Settings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Original texture upload and aspect-preserving viewport, plus optional
 * Libretro-derived GLES2 fragment filters. All GL calls run on the GL thread.
 */
class OpenGLRenderer(
    private val frameBuffer: FrameBuffer,
    private val appContext: Context
) : GLSurfaceView.Renderer {

    private data class Program(
        val handle: Int,
        val position: Int,
        val texCoord: Int,
        val sampler: Int,
        val inputSize: Int
    )

    @Volatile var classicMode = false
    @Volatile var requestedShaderId: Int = Settings.glslShader.value
    @Volatile var requestedUpscaleId: Int = Settings.upscalingFilter.value

    private val programs = mutableMapOf<GbaShaderFilter, Program>()
    private val failedPrograms = mutableSetOf<GbaShaderFilter>()
    private var textureId = 0
    private var textureWidth = 0
    private var textureHeight = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var configuredFilter = -1

    private var intermediateTex = 0
    private var intermediateFbo = 0
    private var intermediateWidth = 0
    private var intermediateHeight = 0

    private val vertexBuffer = floatBuffer(floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    ))
    // Core's top-left pixel is uploaded as OpenGL's bottom-left texel.
    private val coreTexCoords = floatBuffer(floatArrayOf(
        0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
    ))
    // Render-to-texture has ordinary OpenGL origin; the second pass flips Y.
    private val fboTexCoords = floatBuffer(floatArrayOf(
        0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f
    ))

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(values); position(0) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        // A new EGL context invalidates all old programs, FBOs and textures.
        programs.clear()
        failedPrograms.clear()
        intermediateFbo = 0
        intermediateTex = 0
        intermediateWidth = 0
        intermediateHeight = 0
        configuredFilter = -1
        programs[GbaShaderFilter.NONE] = compileProgram(DEFAULT_VERTEX, DEFAULT_FRAGMENT)
            ?: error("Unable to compile the default OpenGL ES 2.0 shader")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        textureWidth = 0
        textureHeight = 0
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val frame = frameBuffer.acquireLatest() ?: return
        if (frame.width <= 0 || frame.height <= 0) return
        uploadTexture(frame.pixels, frame.width, frame.height)

        val requested = GbaShaderFilter.from(requestedShaderId)
        val active = if (requested == GbaShaderFilter.HQ4X_TWO_PASS)
            GbaShaderFilter.HQ2X else requested
        val chosen = programFor(active) ?: programs.getValue(GbaShaderFilter.NONE)
        updateTextureSampler(if (chosen == programs[GbaShaderFilter.NONE])
            GbaShaderFilter.NONE else active)

        if (requested == GbaShaderFilter.HQ4X_TWO_PASS &&
            chosen != programs[GbaShaderFilter.NONE] &&
            drawTwoPassHq(frame.width, frame.height, chosen)) {
            return
        }

        // Single-pass fallback if the intermediary or shader cannot be used.
        drawPass(chosen, textureId, frame.width, frame.height, coreTexCoords, true)
    }

    private fun uploadTexture(pixels: IntArray, width: Int, height: Int) {
        val buffer: IntBuffer = IntBuffer.wrap(pixels)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        if (width != textureWidth || height != textureHeight) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            textureWidth = width
            textureHeight = height
        } else {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        }
    }

    private fun updateTextureSampler(mode: GbaShaderFilter) {
        val filter = if (mode == GbaShaderFilter.NONE && requestedUpscaleId == 1)
            GLES20.GL_LINEAR else GLES20.GL_NEAREST
        if (configuredFilter == filter) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        configuredFilter = filter
    }

    private fun drawTwoPassHq(w: Int, h: Int, hqProgram: Program): Boolean {
        val iw = w * 2
        val ih = h * 2
        if (!ensureIntermediate(iw, ih)) return false

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glViewport(0, 0, iw, ih)
        drawPass(hqProgram, textureId, w, h, coreTexCoords, false)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        drawPass(hqProgram, intermediateTex, iw, ih, fboTexCoords, true)
        return true
    }

    private fun ensureIntermediate(w: Int, h: Int): Boolean {
        if (w == intermediateWidth && h == intermediateHeight && intermediateFbo != 0) return true
        releaseIntermediate()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        intermediateTex = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, intermediateTex)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glGenFramebuffers(1, ids, 0)
        intermediateFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, intermediateFbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, intermediateTex, 0)
        val complete = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (!complete) {
            Log.w(TAG, "HQ4x intermediate framebuffer incomplete, using HQ2x")
            releaseIntermediate()
            return false
        }
        intermediateWidth = w
        intermediateHeight = h
        return true
    }

    private fun releaseIntermediate() {
        if (intermediateTex != 0) GLES20.glDeleteTextures(1, intArrayOf(intermediateTex), 0)
        if (intermediateFbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(intermediateFbo), 0)
        intermediateTex = 0
        intermediateFbo = 0
        intermediateWidth = 0
        intermediateHeight = 0
    }

    private fun drawPass(p: Program, inputTex: Int, w: Int, h: Int,
                         coords: FloatBuffer, fitted: Boolean) {
        GLES20.glUseProgram(p.handle)
        GLES20.glEnableVertexAttribArray(p.position)
        GLES20.glVertexAttribPointer(p.position, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(p.texCoord)
        GLES20.glVertexAttribPointer(p.texCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
        GLES20.glUniform1i(p.sampler, 0)
        if (p.inputSize >= 0) GLES20.glUniform2f(p.inputSize, w.toFloat(), h.toFloat())
        if (fitted) maintainAspectViewport(w, h)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(p.position)
        GLES20.glDisableVertexAttribArray(p.texCoord)
    }

    private fun programFor(mode: GbaShaderFilter): Program? {
        programs[mode]?.let { return it }
        if (mode in failedPrograms) return null
        val filename = when (mode) {
            GbaShaderFilter.NONE -> return programs[GbaShaderFilter.NONE]
            GbaShaderFilter.HQ2X, GbaShaderFilter.HQ4X_TWO_PASS -> "hq2x.glsl"
            GbaShaderFilter.XBR2X, GbaShaderFilter.XBR4X -> "xbr.glsl"
            GbaShaderFilter.LCD3X -> "lcd3x.glsl"
        }
        return try {
            val raw = appContext.assets.open("shaders/$filename").bufferedReader().use { it.readText() }
            val fragment = when (mode) {
                GbaShaderFilter.XBR2X -> raw.replace("XBR_SCALE_VALUE", "2.0")
                GbaShaderFilter.XBR4X -> raw.replace("XBR_SCALE_VALUE", "4.0")
                else -> raw
            }
            val program = compileProgram(DEFAULT_VERTEX, fragment)
            if (program != null) programs[mode] = program
            else failedPrograms.add(mode)
            program
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to load GLSL shader $mode; reverting to basic filter", ex)
            failedPrograms.add(mode)
            null
        }
    }

    private fun compileProgram(vertex: String, fragment: String): Program? {
        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vertex)
        if (vert == 0) return null
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        if (frag == 0) {
            GLES20.glDeleteShader(vert)
            return null
        }
        val id = GLES20.glCreateProgram()
        GLES20.glAttachShader(id, vert)
        GLES20.glAttachShader(id, frag)
        GLES20.glLinkProgram(id)
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)
        val status = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "GLSL program link failed: " + GLES20.glGetProgramInfoLog(id))
            GLES20.glDeleteProgram(id)
            return null
        }
        return Program(id,
            GLES20.glGetAttribLocation(id, "aPosition"),
            GLES20.glGetAttribLocation(id, "aTexCoord"),
            GLES20.glGetUniformLocation(id, "uTexture"),
            GLES20.glGetUniformLocation(id, "uInputSize"))
    }

    private fun compileShader(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "GLSL compile failed: " + GLES20.glGetShaderInfoLog(id))
            GLES20.glDeleteShader(id)
            return 0
        }
        return id
    }

    private fun maintainAspectViewport(coreWidth: Int, coreHeight: Int) {
        if (coreWidth <= 0 || coreHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val reserved = if (classicMode && surfaceWidth > surfaceHeight)
            ClassicLayoutGeometry.panelWidth(surfaceWidth, surfaceHeight) else 0
        val availableWidth = (surfaceWidth - reserved * 2).coerceAtLeast(1)
        val targetAspect = coreWidth.toFloat() / coreHeight.toFloat()
        val surfaceAspect = availableWidth.toFloat() / surfaceHeight.toFloat()
        val vpW: Int
        val vpH: Int
        if (surfaceAspect > targetAspect) {
            vpW = (surfaceHeight * targetAspect).toInt().coerceAtMost(availableWidth)
            vpH = surfaceHeight
        } else {
            vpW = availableWidth
            vpH = (availableWidth / targetAspect).toInt().coerceAtMost(surfaceHeight)
        }
        GLES20.glViewport((surfaceWidth - vpW) / 2, (surfaceHeight - vpH) / 2, vpW, vpH)
    }

    private companion object {
        const val TAG = "mgba-glsl"
        const val DEFAULT_VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPosition, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
        """
        const val DEFAULT_FRAGMENT = """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """
    }
}
