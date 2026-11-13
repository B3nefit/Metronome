package com.stageclick.metronome

import com.stageclick.metronome.audio.ClickPattern
import com.stageclick.metronome.audio.ClickScheduler
import com.stageclick.metronome.audio.ClickSounds
import com.stageclick.metronome.audio.LEVEL_MUTE
import com.stageclick.metronome.audio.LEVEL_NORMAL
import com.stageclick.metronome.audio.LEVEL_STRONG
import com.stageclick.metronome.audio.LEVEL_SUB
import com.stageclick.metronome.audio.TickListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor

class ClickSchedulerTest {

    private val sr = 48000

    private class Tick(val frame: Long, val beat: Int, val sub: Int, val bar: Int, val countIn: Boolean, val level: Int)

    private class Collector : TickListener {
        val ticks = ArrayList<Tick>()
        var autoStops = 0
        override fun onTick(frame: Long, beat: Int, sub: Int, bar: Int, countIn: Boolean, level: Int) {
            ticks.add(Tick(frame, beat, sub, bar, countIn, level))
        }
        override fun onAutoStop(frame: Long) { autoStops++ }
    }

    private fun pattern(bpm: Double, bpb: Int = 4, sub: Int = 1, countIn: Int = 0, stopAfter: Boolean = false, accents: IntArray? = null) =
        ClickPattern(bpm, bpb, sub, accents ?: IntArray(bpb) { if (it == 0) LEVEL_STRONG else LEVEL_NORMAL }, countIn, stopAfter)

    private fun scheduler() = ClickScheduler(sr, ClickSounds.build(0, 1, sr))

    /** 스케줄러를 block 크기로 돌리고, 블록마다 hook(블록 시작 프레임)을 부른다 */
    private fun run(s: ClickScheduler, frames: Long, block: Int, c: Collector, start: Long = 0, audio: FloatArray? = null, hook: ((Long) -> Unit)? = null): Long {
        val buf = FloatArray(block * 2)
        var pos = start
        while (pos < start + frames) {
            hook?.invoke(pos)
            s.render(buf, block, pos, c)
            if (audio != null) {
                for (i in 0 until block) {
                    val idx = (pos - start + i).toInt()
                    if (idx < audio.size) audio[idx] = buf[i * 2]
                }
            }
            pos += block
        }
        return pos
    }

    @Test
    fun steadyTempo_noDriftOverOneHour() {
        for (bpm in doubleArrayOf(120.0, 133.3, 97.5, 61.0, 240.0)) {
            val s = scheduler(); val c = Collector()
            s.start(pattern(bpm))
            run(s, sr * 3600L, 240, c)
            val interval = 60.0 * sr / bpm
            var maxErr = 0.0
            c.ticks.forEachIndexed { k, t -> maxErr = maxOf(maxErr, abs(t.frame - floor(k * interval))) }
            assertTrue("bpm=$bpm 최대 오차 $maxErr 프레임", maxErr <= 1.0)
            val expected = (3600.0 * sr / interval).toInt()
            assertTrue("bpm=$bpm 클릭 수 ${c.ticks.size} vs $expected", abs(c.ticks.size - expected) <= 1)
        }
    }

    @Test
    fun blockSizeDoesNotChangeTiming() {
        val results = intArrayOf(64, 96, 192, 240, 1000, 2048).map { b ->
            val s = scheduler(); val c = Collector()
            s.start(pattern(131.0, sub = 3))
            run(s, sr * 30L, b, c)
            c.ticks.map { it.frame }.filter { it < sr * 29L }
        }
        for (r in results) assertEquals(results[0], r)
    }

    @Test
    fun countInThenSubdivision() {
        val s = scheduler(); val c = Collector()
        s.start(pattern(120.0, bpb = 4, sub = 2, countIn = 1))
        run(s, sr * 4L, 192, c)
        val beat = 24000L
        // 카운트인 4박 (분할 없음)
        for (i in 0 until 4) {
            assertTrue(c.ticks[i].countIn)
            assertEquals(i * beat, c.ticks[i].frame)
            assertEquals(if (i == 0) LEVEL_STRONG else LEVEL_NORMAL, c.ticks[i].level)
        }
        // 본 곡: 8분음표 간격, 1박 강박
        assertEquals(false, c.ticks[4].countIn)
        assertEquals(4 * beat, c.ticks[4].frame)
        assertEquals(LEVEL_STRONG, c.ticks[4].level)
        assertEquals(4 * beat + beat / 2, c.ticks[5].frame)
        assertEquals(LEVEL_SUB, c.ticks[5].level)
        assertEquals(1, c.ticks[5].sub)
    }

    @Test
    fun stopAfterCountIn() {
        val s = scheduler(); val c = Collector()
        s.start(pattern(100.0, bpb = 3, countIn = 2, stopAfter = true))
        run(s, sr * 10L, 256, c)
        assertEquals(6, c.ticks.size)
        assertEquals(1, c.autoStops)
        assertEquals(false, s.playing)
    }

    @Test
    fun tempoChangeAppliesFromNextClick() {
        val s = scheduler(); val c = Collector()
        s.start(pattern(120.0))
        var changed = false
        run(s, sr * 6L, 192, c) { pos ->
            if (!changed && pos >= 50000) { s.update(pattern(150.0)); changed = true }
        }
        // 50000 이후 첫 클릭 전까지는 120 BPM, 그 다음부터 150 BPM 간격
        val newIv = 60.0 * sr / 150.0
        val idx = c.ticks.indexOfFirst { it.frame > 50000 }
        for (k in idx + 1 until c.ticks.size) {
            val d = c.ticks[k].frame - c.ticks[k - 1].frame
            assertTrue("간격 $d", abs(d - newIv) <= 1.0)
        }
        // 박 번호는 끊기지 않고 이어진다
        for (k in 1 until c.ticks.size) assertEquals((c.ticks[k - 1].beat + 1) % 4, c.ticks[k].beat)
    }

    @Test
    fun songSwitchWhilePlayingRestartsBar() {
        val s = scheduler(); val c = Collector()
        s.start(pattern(120.0, bpb = 4))
        var switched = false
        run(s, sr * 6L, 192, c) { pos ->
            if (!switched && pos >= 30000) { s.update(pattern(90.0, bpb = 3), resetBar = true); switched = true }
        }
        val idx = c.ticks.indexOfFirst { it.frame > 30000 }
        assertEquals(0, c.ticks[idx].beat)
        assertEquals(0, c.ticks[idx].bar)
        assertEquals(56000L, c.ticks[idx].frame) // 직전 박(24000) + 새 템포 한 박(32000)
        val iv = 60.0 * sr / 90.0
        for (k in idx + 1 until c.ticks.size) assertTrue(abs(c.ticks[k].frame - c.ticks[k - 1].frame - iv) <= 1.0)
        assertEquals(1, c.ticks[idx + 1].beat)
        assertEquals(0, c.ticks[idx + 3].beat) // 3/4로 바뀜
    }

    @Test
    fun clickOnsetIsSampleAccurate() {
        val s = scheduler(); val c = Collector()
        val audio = FloatArray(sr * 3)
        s.start(pattern(137.0))
        run(s, audio.size.toLong(), 240, c, audio = audio)
        for (t in c.ticks) {
            val f = t.frame.toInt()
            if (f + 10 >= audio.size || f == 0) continue
            assertEquals("클릭 직전 샘플은 무음이어야 함", 0f, audio[f - 1], 0f)
            var energy = 0f
            for (i in f until f + 10) energy += abs(audio[i])
            assertTrue("클릭 시작 지점에 소리가 있어야 함", energy > 0.01f)
        }
    }

    @Test
    fun mutedBeatIsSilentButReported() {
        val s = scheduler(); val c = Collector()
        val audio = FloatArray(sr * 2)
        s.start(pattern(120.0, accents = intArrayOf(LEVEL_STRONG, LEVEL_MUTE, LEVEL_NORMAL, LEVEL_MUTE)))
        run(s, audio.size.toLong(), 192, c, audio = audio)
        val muted = c.ticks.filter { it.level == LEVEL_MUTE }
        assertTrue(muted.isNotEmpty())
        for (t in muted) {
            val f = t.frame.toInt()
            // 이전 클릭 꼬리가 끝난 뒤 구간 (클릭 길이 < 0.1 s)
            var e = 0f
            for (i in f until minOf(f + 2000, audio.size)) e += abs(audio[i])
            assertEquals(0f, e, 0f)
        }
    }

    @Test
    fun limiterNeverClips() {
        for (x in floatArrayOf(0f, 0.5f, 0.9f, 1.0f, 2f, 8f, -8f)) {
            val y = ClickScheduler.limit(x)
            assertTrue(abs(y) <= 1.0f)
            if (abs(x) <= 0.9f) assertEquals(x, y, 0f)
        }
        val s = scheduler(); val c = Collector()
        s.masterGain = 4f // +12 dB 부스트
        val audio = FloatArray(sr)
        s.start(pattern(200.0, sub = 4))
        run(s, audio.size.toLong(), 192, c, audio = audio)
        assertTrue(audio.all { abs(it) <= 1f })
    }

    @Test
    fun allSoundsAreValid() {
        for (type in ClickSounds.NAMES.indices) for (len in 0..2) {
            val set = ClickSounds.build(type, len, sr)
            for (a in listOf(set.strong, set.normal, set.sub)) {
                assertTrue(a.isNotEmpty() && a.size < sr)
                assertTrue(a.all { !it.isNaN() && abs(it) <= 1.0001f })
            }
            assertTrue(set.strong.maxOf { abs(it) } > set.normal.maxOf { abs(it) })
        }
    }

    @Test
    fun stopThenStartIsImmediate() {
        val s = scheduler(); val c = Collector()
        s.start(pattern(120.0))
        var pos = run(s, 30000, 192, c)
        s.stop()
        pos = run(s, 10000, 192, c, start = pos)
        val before = c.ticks.size
        s.start(pattern(120.0))
        val startAt = pos
        run(s, 1000, 192, c, start = pos)
        assertEquals(before + 1, c.ticks.size)
        assertEquals(startAt, c.ticks.last().frame) // 시작 버튼 → 다음 블록의 첫 샘플에서 즉시 클릭
    }
}
