package com.stageclick.metronome.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.stageclick.metronome.audio.LEVEL_MUTE
import com.stageclick.metronome.audio.LEVEL_STRONG
import kotlin.math.ceil
import kotlin.math.min

/** 어두운 무대에서 눈부시지 않으면서 대비가 높은 색 */
object C {
    const val BG = 0xFF000000.toInt()
    const val SURFACE = 0xFF141619.toInt()
    const val SURFACE2 = 0xFF24282E.toInt()
    const val PRESSED = 0xFF3A404A.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFFFFB300.toInt()
    const val PLAY = 0xFF00C853.toInt()
    const val STOP = 0xFFE53935.toInt()
    const val COUNT = 0xFF29B6F6.toInt()
    const val WARN = 0xFFFF5252.toInt()
    const val MUTED = 0xFF4A4F57.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

fun pressable(normal: Int, pressed: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, radius, strokeColor, strokeWidth))
    addState(intArrayOf(-android.R.attr.state_enabled), rounded(0xFF0E0F11.toInt(), radius))
    addState(intArrayOf(), rounded(normal, radius, strokeColor, strokeWidth))
}

/**
 * 손가락이 닿는 순간(ACTION_DOWN) 바로 동작하는 버튼.
 * 일반 버튼은 손을 뗄 때 동작해 체감 지연이 생기므로 공연용으로는 부적합.
 * 시스템 터치음도 꺼서 인이어에 '딸깍' 소리가 섞이지 않게 한다.
 */
@SuppressLint("ClickableViewAccessibility")
class PadButton(ctx: Context) : TextView(ctx) {
    var onPress: (() -> Unit)? = null
    var onHold: (() -> Unit)? = null
    var holdMs = 700L
    var repeat = false
    /** 마지막 터치 순간 (System.nanoTime 기준 — 보정용) */
    var lastDownNanos = 0L
        private set
    private var held = false

    private val repeatTask = object : Runnable {
        override fun run() {
            onPress?.invoke()
            postDelayed(this, 70)
        }
    }
    private val holdTask = Runnable {
        held = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold?.invoke()
    }

    init {
        gravity = Gravity.CENTER
        isSoundEffectsEnabled = false
        isClickable = true
        setTextColor(C.TEXT)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        maxLines = 1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPressed = true
                held = false
                // eventTime은 터치가 감지된 시각(uptimeMillis = CLOCK_MONOTONIC) → nanoTime과 같은 시계
                lastDownNanos = e.eventTime * 1_000_000L
                onPress?.invoke()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                if (repeat) postDelayed(repeatTask, 450)
                if (onHold != null) postDelayed(holdTask, holdMs)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPressed = false
                removeCallbacks(repeatTask)
                removeCallbacks(holdTask)
                if (e.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** 마디의 박을 큰 원으로 표시. 원을 누르면 강박 → 보통 → 무음 순으로 바뀐다. */
@SuppressLint("ClickableViewAccessibility")
class BeatView(ctx: Context) : View(ctx) {
    private var beats = 4
    private var accents = intArrayOf(2, 1, 1, 1)
    private var subdivision = 1
    private var cur = -1
    private var curSub = 0
    private var curCountIn = false
    var onBeatTap: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arc = RectF()
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var radius = 0f

    init { isSoundEffectsEnabled = false }

    fun configure(beats: Int, accents: IntArray, subdivision: Int) {
        this.beats = beats.coerceAtLeast(1)
        this.accents = accents.copyOf()
        this.subdivision = subdivision
        if (cur >= this.beats) cur = -1
        invalidate()
    }

    fun show(beat: Int, sub: Int, countIn: Boolean) {
        cur = beat; curSub = sub; curCountIn = countIn
        invalidate()
    }

    fun reset() {
        if (cur != -1) { cur = -1; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        val n = beats
        val rows = if (n > 8) 2 else 1
        val perRow = ceil(n / rows.toFloat()).toInt()
        val cellW = width / perRow.toFloat()
        val cellH = height / rows.toFloat()
        val r = min(cellW, cellH) * 0.40f
        radius = r
        if (cx.size != n) { cx = FloatArray(n); cy = FloatArray(n) }
        text.textSize = r * 0.85f
        for (i in 0 until n) {
            val row = i / perRow
            val col = i % perRow
            val inRow = if (row == rows - 1) n - perRow * (rows - 1) else perRow
            val x = (perRow - inRow) * cellW / 2f + cellW * (col + 0.5f)
            val y = cellH * (row + 0.5f)
            cx[i] = x; cy[i] = y
            val acc = if (i < accents.size) accents[i] else 1
            val active = i == cur
            fill.color = when {
                active && curCountIn -> C.COUNT
                active && acc == LEVEL_STRONG -> C.ACCENT
                active && acc == LEVEL_MUTE -> C.MUTED
                active -> C.TEXT
                else -> C.SURFACE2
            }
            c.drawCircle(x, y, r, fill)
            stroke.strokeWidth = if (acc == LEVEL_STRONG) r * 0.18f else r * 0.08f
            stroke.color = when (acc) {
                LEVEL_STRONG -> C.ACCENT
                LEVEL_MUTE -> C.MUTED
                else -> 0xFF8A8F96.toInt()
            }
            c.drawCircle(x, y, r - stroke.strokeWidth / 2f, stroke)
            if (acc == LEVEL_MUTE && !active) {
                stroke.strokeWidth = r * 0.08f
                val d = r * 0.45f
                c.drawLine(x - d, y - d, x + d, y + d, stroke)
            }
            text.color = if (active) C.BG else if (acc == LEVEL_MUTE) C.MUTED else C.TEXT
            c.drawText("${i + 1}", x, y - (text.descent() + text.ascent()) / 2f, text)

            // 분할 진행 표시 (원 바깥쪽 호)
            if (active && !curCountIn && subdivision > 1) {
                stroke.color = C.TEXT
                stroke.strokeWidth = r * 0.10f
                val rr = r * 1.16f
                arc.set(x - rr, y - rr, x + rr, y + rr)
                c.drawArc(arc, -90f, 360f * (curSub + 1) / subdivision, false, stroke)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_DOWN) return true
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in cx.indices) {
            val dx = e.x - cx[i]; val dy = e.y - cy[i]
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        if (best >= 0 && bestD <= (radius * 1.4f) * (radius * 1.4f)) onBeatTap?.invoke(best)
        return true
    }
}
