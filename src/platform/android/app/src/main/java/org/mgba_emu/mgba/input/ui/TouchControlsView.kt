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
        textAlign = Paint.Align.CENTER
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
    private val decreaseRect = RectF()
    private val increaseRect = RectF()
    private var selectedId: String? = null
    private var editAction: String? = null
    private var pinchActive = false
    private var pinchInitialDistance = 0f
    private var pinchInitialScale = 1f

    // Extra scaling for a single control, persisted per orientation.
    private fun controlScale(id: String): Float {
        val key = orientationKey() + "_" + id + "_scale"
        return positions.getFloat(key, 1f).coerceIn(0.55f, 1.65f)
    }

    private fun resizeSelected(delta: Float) {
        val id = selectedId ?: return
        setControlScale(id, controlScale(id) + delta)
    }

    private fun setControlScale(id: String, requested: Float) {
        val scale = requested.coerceIn(0.55f, 1.65f)
        if (abs(scale - controlScale(id)) < 0.002f) return

        // Preserve a previously dragged center before rebuilding the geometry.
        saveControl(id)
        positions.edit()
            .putFloat(orientationKey() + "_" + id + "_scale", scale)
            .apply()
        layoutControls(width, height)
        invalidate()
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        return hypot((event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble()).toFloat()
    }

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
        // The existing default (38%) is the reference appearance. Keep the slider
        // proportional for fills, outlines and symbols, including saved settings.
        val opacity = opacityPercent / 38f
        fun alpha(base: Float) = (255 * base * opacity).toInt().coerceIn(0, 255)
        fillPaint.color = Color.argb(alpha(.26f), 35, 35, 35)
        pressedPaint.color = Color.argb(alpha(.34f), 35, 35, 35)
        // Soft, slightly white outlines as on Strato's graphite overlay.
        // Applies to the D-pad discs, A/B, L/R, and Start/Select equally.
        borderPaint.color = Color.argb(alpha(.30f), 235, 235, 235)
        textPaint.color = Color.argb(alpha(.55f), 255, 255, 255)
        markerPaint.color = textPaint.color
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

        val dpadScale = s * controlScale("dpad")
        // Strato-style direction buttons are four distinct translucent discs.
        // armLength is the distance to a disc center; armWidth is its diameter.
        dpadArmLength = w * 0.050f * dpadScale
        dpadArmWidth = w * 0.058f * dpadScale
        val dpadReach = dpadArmLength + dpadArmWidth / 2f
        val (px, py) = savedCenter("dpad", margin + dpadReach, h - margin - dpadReach,
            dpadReach, dpadReach)
        dpadCenterX = px
        dpadCenterY = py

        val radius = w * 0.055f * s
        val faceX = w - margin - radius * 2.2f
        val faceY = h - margin - radius * 2.2f
        val radiusA = radius * 1.10f * controlScale("a")
        val radiusB = radius * 0.90f * controlScale("b")
        addControl("a", GbaKey.A, "A", Shape.CIRCLE,
            faceX + radius * 1.3f, faceY, radiusA, radiusA)
        addControl("b", GbaKey.B, "B", Shape.CIRCLE,
            faceX, faceY + radius * 1.3f, radiusB, radiusB)

        val smallW = w * 0.09f * s
        val smallH = max(h * 0.045f, 28f * resources.displayMetrics.density) * s
        val centerX = w / 2f
        addControl("select", GbaKey.SELECT, "SELECT", Shape.PILL,
            centerX - smallW * 0.6f, h - margin - smallH / 2,
            smallW / 2 * controlScale("select"), smallH / 2 * controlScale("select"))
        addControl("start", GbaKey.START, "START", Shape.PILL,
            centerX + smallW * 0.6f, h - margin - smallH / 2,
            smallW / 2 * controlScale("start"), smallH / 2 * controlScale("start"))

        val shoulderW = w * 0.13f * s
        val shoulderH = max(h * 0.06f, 30f * resources.displayMetrics.density) * s
        addControl("l", GbaKey.L, "L", Shape.PILL,
            margin + shoulderW / 2, margin + shoulderH / 2,
            shoulderW / 2 * controlScale("l"), shoulderH / 2 * controlScale("l"))
        addControl("r", GbaKey.R, "R", Shape.PILL,
            w - margin - shoulderW / 2, margin + shoulderH / 2,
            shoulderW / 2 * controlScale("r"), shoulderH / 2 * controlScale("r"))
        val density = resources.displayMetrics.density
        val toolbarTop = 10f * density
        val toolbarBottom = 50f * density
        val doneWidth = max(84f * density, w * .13f)
        val resizeWidth = 52f * density
        val spacing = 10f * density
        doneRect.set(w / 2f - doneWidth / 2, toolbarTop,
                     w / 2f + doneWidth / 2, toolbarBottom)
        decreaseRect.set(doneRect.left - spacing - resizeWidth, toolbarTop,
                         doneRect.left - spacing, toolbarBottom)
        increaseRect.set(doneRect.right + spacing, toolbarTop,
                         doneRect.right + spacing + resizeWidth, toolbarBottom)
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
            if (editing && selectedId == control.id) {
                val highlight = Paint(borderPaint).apply {
                    color = Color.argb(240, 255, 255, 255)
                    strokeWidth = 2.5f * resources.displayMetrics.density
                }
                if (control.shape == Shape.CIRCLE) {
                    canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() / 2, highlight)
                } else {
                    canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, highlight)
                }
            }
            val oldSize = textPaint.textSize
            textPaint.textSize = rect.height() * when {
                control.shape == Shape.CIRCLE -> .42f
                control.label.length == 1 -> .50f
                else -> .38f
            }
            // Labels follow individual resizing and fit narrow portrait capsules.
            val labelWidth = textPaint.measureText(control.label)
            if (labelWidth > rect.width() * .8f) {
                textPaint.textSize *= rect.width() * .8f / labelWidth
            }
            canvas.drawText(control.label, rect.centerX(),
                rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            textPaint.textSize = oldSize
        }
        if (editing) {
            val p = Paint(fillPaint).apply { alpha = 205 }
            val previousSize = textPaint.textSize
            val previousAlpha = textPaint.alpha
            textPaint.alpha = 230 // Keep editor tools readable at low game opacity.
            val labelSize = max(14f * resources.displayMetrics.density, previousSize * .55f)
            textPaint.textSize = labelSize
            fun drawTool(rect: RectF, label: String, active: Boolean) {
                val background = if (active) p else Paint(p).apply { alpha = 105 }
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, background)
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, borderPaint)
                canvas.drawText(label, rect.centerX(),
                    rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            }
            drawTool(doneRect, context.getString(R.string.touch_layout_done), true)
            drawTool(decreaseRect, "−", selectedId != null)
            drawTool(increaseRect, "+", selectedId != null)
            selectedId?.let { id ->
                val label = id.uppercase(java.util.Locale.ROOT) + "  " +
                    (controlScale(id) * 100).toInt() + "%"
                val y = doneRect.bottom + 18f * resources.displayMetrics.density
                val textWidth = textPaint.measureText(label)
                val backgroundRect = RectF(width / 2f - textWidth / 2 - 12f, y - labelSize,
                    width / 2f + textWidth / 2 + 12f, y + 5f)
                canvas.drawRoundRect(backgroundRect, 9f, 9f, p)
                canvas.drawText(label, width / 2f, y, textPaint)
            }
            textPaint.textSize = previousSize
            textPaint.alpha = previousAlpha
        }
    }

    private fun isKeyHeld(key: Int): Boolean = pointerKeys.values.any { it and key != 0 }

    private fun drawDpad(canvas: Canvas) {
        val radius = dpadArmWidth / 2f
        val distance = dpadArmLength

        // Strato's D-pad consists of four independent outlined circular keys,
        // not the single solid cross used in the previous mGBA overlay.
        drawDirection(canvas, dpadCenterX, dpadCenterY - distance, radius,
            GbaKey.UP, "up")
        drawDirection(canvas, dpadCenterX - distance, dpadCenterY, radius,
            GbaKey.LEFT, "left")
        drawDirection(canvas, dpadCenterX + distance, dpadCenterY, radius,
            GbaKey.RIGHT, "right")
        drawDirection(canvas, dpadCenterX, dpadCenterY + distance, radius,
            GbaKey.DOWN, "down")
    }

    private fun drawDirection(
        canvas: Canvas, cx: Float, cy: Float, radius: Float,
        key: Int, direction: String
    ) {
        canvas.drawCircle(cx, cy, radius, if (isKeyHeld(key)) pressedPaint else fillPaint)
        canvas.drawCircle(cx, cy, radius, borderPaint)
        if (editing && selectedId == "dpad") {
            val selectedStroke = Paint(borderPaint).apply {
                color = Color.argb(210, 236, 236, 236)
                strokeWidth = 2f * resources.displayMetrics.density
            }
            canvas.drawCircle(cx, cy, radius, selectedStroke)
        }
        drawTriangle(canvas, cx, cy, direction, radius * .24f)
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
        while (bit <= max(GbaKey.L, GbaKey.R)) {
            if ((held and bit) != 0) inputState.release(bit)
            bit = bit shl 1
        }
        pointerKeys.clear()
    }

    fun beginEditing() {
        clearHeldKeys()
        editingPointer = -1
        editingId = null
        selectedId = null
        pinchActive = false
        editAction = null
        editing = true
        invalidate()
    }

    fun finishEditing() {
        editing = false
        editingPointer = -1
        editingId = null
        selectedId = null
        pinchActive = false
        editAction = null
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
                pinchActive = false
                editAction = when {
                    doneRect.contains(event.x, event.y) -> "done"
                    decreaseRect.contains(event.x, event.y) && selectedId != null -> "decrease"
                    increaseRect.contains(event.x, event.y) && selectedId != null -> "increase"
                    else -> null
                }
                editingId = if (editAction == null) pickControl(event.x, event.y) else null
                if (editingId != null) selectedId = editingId
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && editingId != null && editAction == null) {
                    pinchInitialDistance = pinchDistance(event)
                    if (pinchInitialDistance > 10f) {
                        pinchInitialScale = controlScale(editingId!!)
                        pinchActive = true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val id = editingId
                if (pinchActive && id != null && event.pointerCount >= 2) {
                    val distance = pinchDistance(event)
                    if (distance > 0f) {
                        setControlScale(id, pinchInitialScale * distance / pinchInitialDistance)
                    }
                    return
                }
                if (event.pointerCount != 1 || id == null) return
                val index = event.findPointerIndex(editingPointer)
                if (index < 0) return
                val x = event.getX(index)
                val y = event.getY(index)
                shiftControl(id, x - lastEditX, y - lastEditY)
                lastEditX = x
                lastEditY = y
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (pinchActive) {
                    editingId?.let { saveControl(it) }
                    editingId = null // Do not jump when only one finger remains.
                    editingPointer = -1
                    pinchActive = false
                }
            }
            MotionEvent.ACTION_UP -> {
                when (editAction) {
                    "done" -> if (doneRect.contains(event.x, event.y)) finishEditing()
                    "decrease" -> if (decreaseRect.contains(event.x, event.y)) resizeSelected(-0.10f)
                    "increase" -> if (increaseRect.contains(event.x, event.y)) resizeSelected(0.10f)
                    else -> editingId?.let { saveControl(it) }
                }
                editingPointer = -1
                editingId = null
                editAction = null
                pinchActive = false
            }
            MotionEvent.ACTION_CANCEL -> {
                layoutControls(width, height)
                editingPointer = -1
                editingId = null
                editAction = null
                pinchActive = false
            }
        }
    }

    private fun pickControl(x: Float, y: Float): String? {
        // Reverse order favors face buttons if edited layouts overlap.
        controls.asReversed().firstOrNull { it.rect.contains(x, y) }?.let { return it.id }
        val dpadReach = dpadArmLength + dpadArmWidth / 2f
        if (abs(x - dpadCenterX) <= dpadReach &&
            abs(y - dpadCenterY) <= dpadReach) return "dpad"
        return null
    }

    private fun shiftControl(id: String, dx: Float, dy: Float) {
        if (id == "dpad") {
            val reach = dpadArmLength + dpadArmWidth / 2f
            dpadCenterX = (dpadCenterX + dx).coerceIn(reach, max(reach, width - reach))
            dpadCenterY = (dpadCenterY + dy).coerceIn(reach, max(reach, height - reach))
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
        while (bit <= max(GbaKey.L, GbaKey.R)) {
            if (pressed and bit != 0) inputState.press(bit)
            bit = bit shl 1
        }
    }

    private fun releaseIfNotHeldElsewhere(keys: Int, excludePointer: Int) {
        var bit = 1
        while (bit <= max(GbaKey.L, GbaKey.R)) {
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
        val radius = dpadArmWidth / 2f
        val distance = dpadArmLength
        fun inDirection(centerX: Float, centerY: Float): Boolean =
            hypot((x - centerX).toDouble(), (y - centerY).toDouble()) <= radius

        if (inDirection(dpadCenterX, dpadCenterY - distance)) keys = keys or GbaKey.UP
        if (inDirection(dpadCenterX, dpadCenterY + distance)) keys = keys or GbaKey.DOWN
        if (inDirection(dpadCenterX - distance, dpadCenterY)) keys = keys or GbaKey.LEFT
        if (inDirection(dpadCenterX + distance, dpadCenterY)) keys = keys or GbaKey.RIGHT

        // Retain diagonals when sliding between adjacent directional discs.
        // Avoid a virtual button at the empty center of the D-pad.
        if (keys == 0) {
            val radial = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (radial > radius * .55f && radial <= distance + radius) {
                val threshold = radius * .36f
                if (abs(dx) > threshold && abs(dy) > threshold) {
                    keys = keys or (if (dx < 0) GbaKey.LEFT else GbaKey.RIGHT)
                    keys = keys or (if (dy < 0) GbaKey.UP else GbaKey.DOWN)
                }
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
