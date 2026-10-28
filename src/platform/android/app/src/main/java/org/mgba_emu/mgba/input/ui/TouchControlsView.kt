/*
 * Copyright (C) 2026 Ishan
 * Android Port component of mGBA.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3.
 */

package org.mgba_emu.mgba.input.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.mgba_emu.mgba.R
import org.mgba_emu.mgba.input.GbaKey
import org.mgba_emu.mgba.input.InputState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Native multi-touch GBA overlay. Geometry is shared by drawing, hit-testing and
 * the drag editor; positions are saved separately for portrait and landscape.
 */
class TouchControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    lateinit var inputState: InputState

    private val positions = context.getSharedPreferences("touch_layout_positions", Context.MODE_PRIVATE)
    private val pointerKeys = mutableMapOf<Int, Int>()
    private val controls = mutableListOf<Control>()
    private val controlById get() = controls.associateBy { it.id }

    private enum class Shape { CIRCLE, PILL }
    private data class Control(
        val id: String, val key: Int, val label: String, val shape: Shape,
        val rect: RectF
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(230, 245, 245, 245)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(220, 239, 239, 239)
    }

    private var opacityPercent = 38
    private var sizePercent = 100
    private val sizeFactor get() = sizePercent / 100f

    private var dpadCenterX = 0f
    private var dpadCenterY = 0f
    private var dpadArmLength = 0f
    private var dpadArmWidth = 0f

    private var editing = false
    val isEditing: Boolean get() = editing
    private var editingPointer = -1
    private var editingId: String? = null
    private var lastEditX = 0f
    private var lastEditY = 0f
    private val doneRect = RectF()

    init {
        isClickable = true
        updatePaints()
    }

    fun setOpacity(percent: Int) {
        val clamped = percent.coerceIn(10, 90)
        if (opacityPercent == clamped) return
        opacityPercent = clamped
        updatePaints()
        invalidate()
    }

    fun setSize(percent: Int) {
        val clamped = percent.coerceIn(65, 145)
        if (sizePercent == clamped) return
        clearHeldKeys()
        sizePercent = clamped
        layoutControls(width, height)
        invalidate()
    }

    private fun updatePaints() {
        val alpha = opacityPercent * 255 / 100
        fillPaint.color = Color.argb(alpha, 154, 154, 154)
        pressedPaint.color = Color.argb(min(255, alpha + 70), 205, 205, 205)
        borderPaint.color = Color.argb(min(235, alpha + 75), 224, 224, 224)
        borderPaint.strokeWidth = max(1f, resources.displayMetrics.density * 1.1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearHeldKeys()
        layoutControls(w, h)
    }

    private fun orientationKey(): String = if (width >= height) "landscape" else "portrait"

    private fun savedCenter(id: String, defaultX: Float, defaultY: Float, halfW: Float, halfH: Float): Pair<Float, Float> {
        val key = orientationKey() + "_" + id
        val x = if (positions.contains(key + "_x")) positions.getFloat(key + "_x", 0f) * width else defaultX
        val y = if (positions.contains(key + "_y")) positions.getFloat(key + "_y", 0f) * height else defaultY
        return Pair(x.coerceIn(halfW, max(halfW, width - halfW)), y.coerceIn(halfH, max(halfH, height - halfH)))
    }

    private fun addControl(id: String, key: Int, label: String, shape: Shape,
                           x: Float, y: Float, halfW: Float, halfH: Float) {
        val (cx, cy) = savedCenter(id, x, y, halfW, halfH)
        controls += Control(id, key, label, shape, RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH))
    }

    private fun layoutControls(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        controls.clear()
        val s = sizeFactor
        val margin = w * 0.04f

        dpadArmLength = w * 0.11f * s
        dpadArmWidth = w * 0.09f * s
        val (px, py) = savedCenter("dpad", margin + dpadArmLength, h - margin - dpadArmLength,
            dpadArmLength, dpadArmLength)
        dpadCenterX = px
        dpadCenterY = py

        val radius = w * 0.055f * s
        val faceX = w - margin - radius * 2.2f
        val faceY = h - margin - radius * 2.2f
        addControl("a", GbaKey.A, "A", Shape.CIRCLE,
            faceX + radius * 1.3f, faceY, radius, radius)
        addControl("b", GbaKey.B, "B", Shape.CIRCLE,
            faceX, faceY + radius * 1.3f, radius, radius)

        val smallW = w * 0.09f * s
        val smallH = max(h * 0.045f, 28f * resources.displayMetrics.density) * s
        val centerX = w / 2f
        addControl("select", GbaKey.SELECT, "SELECT", Shape.PILL,
            centerX - smallW * 0.6f, h - margin - smallH / 2, smallW / 2, smallH / 2)
        addControl("start", GbaKey.START, "START", Shape.PILL,
            centerX + smallW * 0.6f, h - margin - smallH / 2, smallW / 2, smallH / 2)

        val shoulderW = w * 0.13f * s
        val shoulderH = max(h * 0.06f, 30f * resources.displayMetrics.density) * s
        addControl("l", GbaKey.L, "L", Shape.PILL,
            margin + shoulderW / 2, margin + shoulderH / 2, shoulderW / 2, shoulderH / 2)
        addControl("r", GbaKey.R, "R", Shape.PILL,
            w - margin - shoulderW / 2, margin + shoulderH / 2, shoulderW / 2, shoulderH / 2)
        doneRect.set(w / 2f - w * .075f, 10f * resources.displayMetrics.density,
                     w / 2f + w * .075f, 50f * resources.displayMetrics.density)
        textPaint.textSize = max(17f * resources.displayMetrics.density, min(w, h) * .045f * s)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawDpad(canvas)
        controls.forEach { control ->
            val paint = if (isKeyHeld(control.key)) pressedPaint else fillPaint
            val rect = control.rect
            if (control.shape == Shape.CIRCLE) {
                val r = rect.width() / 2
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, paint)
                canvas.drawCircle(rect.centerX(), rect.centerY(), r, borderPaint)
            } else {
                val corner = rect.height() / 2
                canvas.drawRoundRect(rect, corner, corner, paint)
                canvas.drawRoundRect(rect, corner, corner, borderPaint)
            }
            val oldSize = textPaint.textSize
            if (control.id == "select" || control.id == "start") textPaint.textSize = oldSize * .5f
            canvas.drawText(control.label, rect.centerX(),
                rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            textPaint.textSize = oldSize
        }
        if (editing) {
            val p = Paint(fillPaint).apply { alpha = 205 }
            canvas.drawRoundRect(doneRect, doneRect.height() / 2, doneRect.height() / 2, p)
            canvas.drawRoundRect(doneRect, doneRect.height() / 2, doneRect.height() / 2, borderPaint)
            val previousSize = textPaint.textSize
            textPaint.textSize = max(14f * resources.displayMetrics.density, previousSize * .55f)
            canvas.drawText(context.getString(R.string.touch_layout_done), doneRect.centerX(),
                doneRect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            textPaint.textSize = previousSize
        }
    }

    private fun isKeyHeld(key: Int): Boolean = pointerKeys.values.any { it and key != 0 }

    private fun drawDpad(canvas: Canvas) {
        val arm = dpadArmLength
        val half = dpadArmWidth / 2
        val vertical = RectF(dpadCenterX - half, dpadCenterY - arm,
            dpadCenterX + half, dpadCenterY + arm)
        val horizontal = RectF(dpadCenterX - arm, dpadCenterY - half,
            dpadCenterX + arm, dpadCenterY + half)
        val rad = half * .24f
        val shape = Path().apply { addRoundRect(vertical, rad, rad, Path.Direction.CW) }
        val other = Path().apply { addRoundRect(horizontal, rad, rad, Path.Direction.CW) }
        shape.op(other, Path.Op.UNION)
        val pressed = isKeyHeld(GbaKey.UP) || isKeyHeld(GbaKey.DOWN) ||
            isKeyHeld(GbaKey.LEFT) || isKeyHeld(GbaKey.RIGHT)
        canvas.drawPath(shape, if (pressed) pressedPaint else fillPaint)
        canvas.drawPath(shape, borderPaint)

        val centerRadius = half * .32f
        canvas.drawCircle(dpadCenterX, dpadCenterY, centerRadius, markerPaint)
        val offset = arm * .62f
        drawTriangle(canvas, dpadCenterX, dpadCenterY - offset, "up", half * .26f)
        drawTriangle(canvas, dpadCenterX, dpadCenterY + offset, "down", half * .26f)
        drawTriangle(canvas, dpadCenterX - offset, dpadCenterY, "left", half * .26f)
        drawTriangle(canvas, dpadCenterX + offset, dpadCenterY, "right", half * .26f)
    }

    private fun drawTriangle(canvas: Canvas, x: Float, y: Float, direction: String, size: Float) {
        val p = Path()
        when (direction) {
            "up" -> { p.moveTo(x, y - size); p.lineTo(x - size, y + size); p.lineTo(x + size, y + size) }
            "down" -> { p.moveTo(x, y + size); p.lineTo(x - size, y - size); p.lineTo(x + size, y - size) }
            "left" -> { p.moveTo(x - size, y); p.lineTo(x + size, y - size); p.lineTo(x + size, y + size) }
            else -> { p.moveTo(x + size, y); p.lineTo(x - size, y - size); p.lineTo(x - size, y + size) }
        }
        p.close()
        canvas.drawPath(p, markerPaint)
    }

    private fun clearHeldKeys() {
        if (!::inputState.isInitialized) { pointerKeys.clear(); return }
        val held = pointerKeys.values.fold(0) { mask, value -> mask or value }
        var bit = 1
        while (bit <= GbaKey.R) {
            if ((held and bit) != 0) inputState.release(bit)
            bit = bit shl 1
        }
        pointerKeys.clear()
    }

    fun beginEditing() {
        clearHeldKeys()
        editingPointer = -1
        editingId = null
        editing = true
        invalidate()
    }

    fun finishEditing() {
        editing = false
        editingPointer = -1
        editingId = null
        invalidate()
    }

    fun resetPositions() {
        positions.edit().clear().apply()
        clearHeldKeys()
        layoutControls(width, height)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editing) {
            handleEditTouch(event)
            invalidate()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                applyPointerKeys(event.getPointerId(idx), resolveKeysAt(event.getX(idx), event.getY(idx)))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    applyPointerKeys(event.getPointerId(i), resolveKeysAt(event.getX(i), event.getY(i)))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val previous = pointerKeys.remove(id) ?: 0
                if (previous != 0) releaseIfNotHeldElsewhere(previous, id)
            }
            MotionEvent.ACTION_CANCEL -> clearHeldKeys()
        }
        invalidate()
        return true
    }

    private fun handleEditTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                editingPointer = event.getPointerId(0)
                lastEditX = event.x
                lastEditY = event.y
                editingId = if (doneRect.contains(event.x, event.y)) "done" else pickControl(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(editingPointer)
                val id = editingId
                if (idx < 0 || id == null || id == "done") return
                val x = event.getX(idx)
                val y = event.getY(idx)
                val dx = x - lastEditX
                val dy = y - lastEditY
                shiftControl(id, dx, dy)
                lastEditX = x
                lastEditY = y
            }
            MotionEvent.ACTION_UP -> {
                val id = editingId
                if (id == "done" && doneRect.contains(event.x, event.y)) {
                    finishEditing()
                } else if (id != null && id != "done") {
                    saveControl(id)
                }
                editingPointer = -1
                editingId = null
            }
            MotionEvent.ACTION_CANCEL -> {
                layoutControls(width, height)
                editingPointer = -1
                editingId = null
            }
        }
    }

    private fun pickControl(x: Float, y: Float): String? {
        // Reverse order favors face buttons if edited layouts overlap.
        controls.asReversed().firstOrNull { it.rect.contains(x, y) }?.let { return it.id }
        if (abs(x - dpadCenterX) <= dpadArmLength &&
            abs(y - dpadCenterY) <= dpadArmLength) return "dpad"
        return null
    }

    private fun shiftControl(id: String, dx: Float, dy: Float) {
        if (id == "dpad") {
            dpadCenterX = (dpadCenterX + dx).coerceIn(dpadArmLength, max(dpadArmLength, width - dpadArmLength))
            dpadCenterY = (dpadCenterY + dy).coerceIn(dpadArmLength, max(dpadArmLength, height - dpadArmLength))
            return
        }
        val rect = controlById[id]?.rect ?: return
        val nextX = (rect.centerX() + dx).coerceIn(rect.width() / 2, max(rect.width() / 2, width - rect.width() / 2))
        val nextY = (rect.centerY() + dy).coerceIn(rect.height() / 2, max(rect.height() / 2, height - rect.height() / 2))
        rect.offsetTo(nextX - rect.width() / 2, nextY - rect.height() / 2)
    }

    private fun saveControl(id: String) {
        if (width <= 0 || height <= 0) return
        val (cx, cy) = if (id == "dpad") Pair(dpadCenterX, dpadCenterY)
            else controlById[id]?.rect?.let { Pair(it.centerX(), it.centerY()) } ?: return
        val key = orientationKey() + "_" + id
        positions.edit().putFloat(key + "_x", cx / width).putFloat(key + "_y", cy / height).apply()
    }

    private fun applyPointerKeys(pointerId: Int, newKeys: Int) {
        val previous = pointerKeys[pointerId] ?: 0
        if (previous == newKeys) return
        pointerKeys[pointerId] = newKeys
        val released = previous and newKeys.inv()
        val pressed = newKeys and previous.inv()
        if (released != 0) releaseIfNotHeldElsewhere(released, pointerId)
        var bit = 1
        while (bit <= GbaKey.R) {
            if (pressed and bit != 0) inputState.press(bit)
            bit = bit shl 1
        }
    }

    private fun releaseIfNotHeldElsewhere(keys: Int, excludePointer: Int) {
        var bit = 1
        while (bit <= GbaKey.R) {
            if (keys and bit != 0 &&
                pointerKeys.entries.none { (id, mask) -> id != excludePointer && mask and bit != 0 }) {
                inputState.release(bit)
            }
            bit = bit shl 1
        }
    }

    private fun resolveKeysAt(x: Float, y: Float): Int {
        var keys = 0
        val dx = x - dpadCenterX
        val dy = y - dpadCenterY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist <= dpadArmLength + dpadArmWidth / 2) {
            val vertical = abs(dx) <= dpadArmWidth
            val horizontal = abs(dy) <= dpadArmWidth
            if (vertical && dy < -dpadArmWidth / 2) keys = keys or GbaKey.UP
            if (vertical && dy > dpadArmWidth / 2) keys = keys or GbaKey.DOWN
            if (horizontal && dx < -dpadArmWidth / 2) keys = keys or GbaKey.LEFT
            if (horizontal && dx > dpadArmWidth / 2) keys = keys or GbaKey.RIGHT
            if (!vertical && !horizontal) {
                keys = keys or (if (dy < 0) GbaKey.UP else GbaKey.DOWN)
                keys = keys or (if (dx < 0) GbaKey.LEFT else GbaKey.RIGHT)
            }
        }

        controls.forEach { control ->
            val r = control.rect
            val hit = if (control.shape == Shape.CIRCLE) {
                hypot((x - r.centerX()).toDouble(), (y - r.centerY()).toDouble()) <= r.width() / 2
            } else r.contains(x, y)
            if (hit) keys = keys or control.key
        }
        return keys
    }
}
