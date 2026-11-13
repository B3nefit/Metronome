package com.stageclick.metronome.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

const val LEVEL_MUTE = 0
const val LEVEL_NORMAL = 1
const val LEVEL_STRONG = 2
const val LEVEL_SUB = 3

/** 재생할 클릭 패턴 (불변 객체 — 스레드 간에 안전하게 전달). */
class ClickPattern(
    val bpm: Double,
    val beatsPerBar: Int,
    val subdivision: Int,
    val accents: IntArray,
    val countInBars: Int,
    val stopAfterCountIn: Boolean,
) {
    fun beatFrames(sampleRate: Int): Double = 60.0 * sampleRate / bpm.coerceIn(MIN_BPM, MAX_BPM)
    fun sub(): Int = subdivision.coerceIn(1, 8)
    fun bpb(): Int = beatsPerBar.coerceIn(1, 32)
    fun accentAt(beat: Int): Int = if (beat < accents.size) accents[beat] else LEVEL_NORMAL

    companion object {
        const val MIN_BPM = 20.0
        const val MAX_BPM = 400.0
    }
}

class SoundSet(val strong: FloatArray, val normal: FloatArray, val sub: FloatArray)

interface TickListener {
    /** frame = 이 클릭의 첫 샘플이 스트림에서 위치하는 절대 프레임 번호 */
    fun onTick(frame: Long, beat: Int, sub: Int, bar: Int, countIn: Boolean, level: Int)
    fun onAutoStop(frame: Long)
}

/**
 * 샘플 단위로 정확한 메트로놈 스케줄러.
 *
 * - 오디오 스레드가 render()를 블록 단위로 호출하고, 클릭은 블록 안의 정확한 샘플 위치에 배치된다.
 * - 박 위치는 기준점(anchor) + n × 간격으로 계산하므로 몇 시간을 재생해도 누적 오차(드리프트)가 없다.
 * - UI 스레드는 명령 큐로만 상태를 바꾸므로 오디오 스레드에서 락을 잡지 않는다.
 */
class ClickScheduler(val sampleRate: Int, initialSounds: SoundSet) {

    private sealed class Cmd {
        class Start(val p: ClickPattern) : Cmd()
        object Stop : Cmd()
        class Update(val p: ClickPattern, val resetBar: Boolean) : Cmd()
        class Sounds(val s: SoundSet) : Cmd()
        class Preview(val level: Int) : Cmd()
    }

    private val queue = ConcurrentLinkedQueue<Cmd>()

    @Volatile var masterGain = 1f
    @Volatile var subGain = 0.6f
    /** 0 = 양쪽, 1 = 왼쪽만, 2 = 오른쪽만 */
    @Volatile var channelMode = 0
    @Volatile var playing = false
        private set

    // ---- 아래는 오디오 스레드 전용 상태 ----
    private var sounds = initialSounds
    private var pattern: ClickPattern? = null
    private var active = false
    private var anchor = 0.0
    private var tickN = 0L
    private var interval = 1.0
    private var lastTick = 0.0
    private var lastBeatTick = 0.0
    private var ticksEmitted = 0L
    private var beat = 0
    private var sub = 0
    private var bar = 0
    private var inCountIn = false
    private var countInBar = 0

    private val vData = arrayOfNulls<FloatArray>(MAX_VOICES)
    private val vPos = IntArray(MAX_VOICES)
    private val vOff = IntArray(MAX_VOICES)
    private val vGain = FloatArray(MAX_VOICES)
    private var mono = FloatArray(0)

    // ---- UI 스레드에서 호출 ----
    fun start(p: ClickPattern) { playing = true; queue.add(Cmd.Start(p)) }
    fun stop() { playing = false; queue.add(Cmd.Stop) }
    fun update(p: ClickPattern, resetBar: Boolean = false) { queue.add(Cmd.Update(p, resetBar)) }
    fun setSounds(s: SoundSet) { queue.add(Cmd.Sounds(s)) }
    fun preview(level: Int) { queue.add(Cmd.Preview(level)) }

    private fun nextTickAt(): Double = anchor + tickN * interval
    private fun rebase(at: Double, iv: Double) { anchor = at; tickN = 0; interval = max(iv, 1.0) }

    private fun applyCommands(blockStart: Long) {
        while (true) {
            val c = queue.poll() ?: break
            when (c) {
                is Cmd.Start -> {
                    val p = c.p
                    pattern = p
                    active = true
                    playing = true
                    beat = 0; sub = 0; bar = 0; ticksEmitted = 0
                    inCountIn = p.countInBars > 0
                    countInBar = 0
                    val bf = p.beatFrames(sampleRate)
                    rebase(blockStart.toDouble(), if (inCountIn) bf else bf / p.sub())
                    lastTick = blockStart.toDouble(); lastBeatTick = lastTick
                }
                is Cmd.Stop -> { active = false; playing = false }
                is Cmd.Update -> applyUpdate(c.p, c.resetBar, blockStart)
                is Cmd.Sounds -> sounds = c.s
                is Cmd.Preview -> trigger(c.level, 0)
            }
        }
    }

    private fun applyUpdate(np: ClickPattern, resetBar: Boolean, blockStart: Long) {
        val old = pattern
        pattern = np
        if (!active || old == null) return
        val bf = np.beatFrames(sampleRate)
        val sd = np.sub()
        val minAt = blockStart.toDouble()
        if (ticksEmitted == 0L) {
            // 아직 첫 클릭 전: 첫 클릭 위치는 그대로, 간격만 갱신
            rebase(anchor, if (inCountIn) bf else bf / sd)
            return
        }
        if (resetBar) {
            // 곡 전환: 다음 박부터 새 곡의 1박으로 시작
            inCountIn = false; beat = 0; sub = 0; bar = 0
            var at = lastBeatTick + bf
            if (at <= lastTick) at = lastTick + bf / sd
            rebase(max(at, minAt), bf / sd)
            return
        }
        if (inCountIn) {
            if (beat >= np.bpb()) beat = 0
            rebase(max(lastTick + bf, minAt), bf)
            return
        }
        if (sd != old.sub()) {
            // 분할이 바뀌면 다음 박 경계에서 새 분할로 시작
            if (sub != 0) { sub = 0; beat++ }
            if (beat >= np.bpb()) { beat = 0; bar++ }
            var at = lastBeatTick + bf
            if (at <= lastTick) at = lastTick + bf / sd
            rebase(max(at, minAt), bf / sd)
        } else {
            // 템포만 바뀜: 다음 클릭부터 새 간격 (끊김 없이 부드럽게)
            if (beat >= np.bpb()) { beat = 0; bar++ }
            rebase(max(lastTick + bf / sd, minAt), bf / sd)
        }
    }

    private fun trigger(level: Int, offset: Int) {
        val s = sounds
        val data = when (level) {
            LEVEL_STRONG -> s.strong
            LEVEL_NORMAL -> s.normal
            LEVEL_SUB -> s.sub
            else -> return
        }
        val gain = if (level == LEVEL_SUB) subGain else 1f
        var slot = -1
        var oldest = -1
        for (i in 0 until MAX_VOICES) {
            if (vData[i] == null) { slot = i; break }
            if (oldest < 0 || vPos[i] > vPos[oldest]) oldest = i
        }
        if (slot < 0) slot = oldest
        vData[slot] = data; vPos[slot] = 0; vOff[slot] = offset; vGain[slot] = gain
    }

    /**
     * frames 개의 스테레오 프레임을 out(인터리브, 길이 ≥ frames*2)에 렌더링한다.
     * blockStart = 이 블록 첫 프레임의 절대 프레임 번호.
     */
    fun render(out: FloatArray, frames: Int, blockStart: Long, listener: TickListener?) {
        applyCommands(blockStart)
        val end = blockStart + frames
        var guard = 0
        while (active && guard++ < 256) {
            val tf = nextTickAt()
            val frame = floor(tf).toLong()
            if (frame >= end) break
            val off = (frame - blockStart).coerceIn(0L, (frames - 1).toLong()).toInt()
            emitTick(tf, off, blockStart, listener)
        }

        if (mono.size < frames) mono = FloatArray(frames)
        java.util.Arrays.fill(mono, 0, frames, 0f)
        for (v in 0 until MAX_VOICES) {
            val d = vData[v] ?: continue
            val off = vOff[v]
            var pos = vPos[v]
            val g = vGain[v]
            val n = min(frames - off, d.size - pos)
            for (i in 0 until n) mono[off + i] += d[pos + i] * g
            pos += n
            if (pos >= d.size) vData[v] = null else vPos[v] = pos
            vOff[v] = 0
        }

        val mg = masterGain
        val ch = channelMode
        var j = 0
        for (i in 0 until frames) {
            val s = limit(mono[i] * mg)
            out[j++] = if (ch == 2) 0f else s
            out[j++] = if (ch == 1) 0f else s
        }
    }

    private fun emitTick(tf: Double, off: Int, blockStart: Long, listener: TickListener?) {
        val p = pattern ?: run { active = false; return }
        val frame = blockStart + off
        val level: Int
        val reportBar: Int
        if (inCountIn) {
            level = if (beat == 0) LEVEL_STRONG else LEVEL_NORMAL
            reportBar = countInBar
        } else {
            level = if (sub == 0) p.accentAt(beat) else LEVEL_SUB
            reportBar = bar
        }
        if (level != LEVEL_MUTE) trigger(level, off)
        listener?.onTick(frame, beat, sub, reportBar, inCountIn, level)
        lastTick = tf
        if (sub == 0) lastBeatTick = tf
        ticksEmitted++

        val bf = p.beatFrames(sampleRate)
        val sd = p.sub()
        if (inCountIn) {
            beat++
            if (beat >= p.bpb()) {
                beat = 0
                countInBar++
                if (countInBar >= p.countInBars) {
                    inCountIn = false
                    if (p.stopAfterCountIn) {
                        active = false
                        playing = false
                        listener?.onAutoStop(frame)
                        return
                    }
                    rebase(tf + bf, bf / sd)
                    return
                }
            }
            tickN++
        } else {
            sub++
            if (sub >= sd) {
                sub = 0
                beat++
                if (beat >= p.bpb()) { beat = 0; bar++ }
            }
            tickN++
        }
    }

    companion object {
        const val MAX_VOICES = 8

        /** 0.9까지는 그대로, 그 위는 부드럽게 눌러서 최대 1.0 — 부스트 시 찌그러짐 없이 크게 */
        fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.9f) return x
            val y = 0.9f + 0.1f * tanh((a - 0.9f) / 0.1f)
            return if (x < 0) -y else y
        }
    }
}
