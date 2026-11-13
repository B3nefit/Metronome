package com.stageclick.metronome.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 클릭음 합성기. 녹음 파일 없이 앱 안에서 생성하므로 오프라인·저용량.
 *
 * 시끄러운 무대에서 잘 들리도록 설계:
 *  - 밴드 사운드(킥·베이스·기타·보컬 기음)가 몰린 저·중역을 피하고
 *    귀가 가장 민감한 2~4 kHz 대역에 에너지를 집중
 *  - 아주 짧은 어택(트랜지언트)으로 '박의 시작점'이 또렷하게
 *  - 강박/약박/분할음은 음높이와 크기를 함께 바꿔 구분이 쉽게
 */
object ClickSounds {
    val NAMES = arrayOf("하이 틱 (추천 · 가장 잘 들림)", "디지털 비프", "우드블록", "카우벨", "스틱 (림)")
    val LENGTHS = arrayOf("짧게", "보통", "길게 (무선 송신기 권장)")

    fun build(type: Int, length: Int, sr: Int): SoundSet {
        val ls = when (length) { 0 -> 0.6; 2 -> 1.8; else -> 1.0 }
        return SoundSet(
            make(type, LEVEL_STRONG, ls, sr, 1.0f),
            make(type, LEVEL_NORMAL, ls, sr, 0.78f),
            make(type, LEVEL_SUB, ls, sr, 0.6f),
        )
    }

    private fun make(type: Int, level: Int, ls: Double, sr: Int, peak: Float): FloatArray {
        val r = when (level) { LEVEL_STRONG -> 1.0; LEVEL_NORMAL -> 0.8; else -> 0.66 }
        val out = when (type) {
            1 -> beep(sr, 1760.0 * r, ls)
            2 -> wood(sr, 1100.0 * r, ls)
            3 -> cowbell(sr, r, ls)
            4 -> stick(sr, r, ls)
            else -> hiTick(sr, 3000.0 * r, ls)
        }
        normalize(out, peak)
        fadeTail(out, sr)
        return out
    }

    private fun len(sr: Int, sec: Double) = max(16, (sr * sec).toInt())

    private fun hiTick(sr: Int, f: Double, ls: Double): FloatArray {
        val tau = 0.012 * ls
        val out = FloatArray(len(sr, 0.075 * ls))
        val rnd = Random(7)
        var prevN = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = (1 - exp(-t / 0.0003)) * exp(-t / tau)
            val n = rnd.nextGaussian()
            val hp = n - prevN; prevN = n
            val s = sin(2 * PI * f * t) +
                0.35 * sin(2 * PI * 2 * f * t) * exp(-t / (tau * 0.5)) +
                0.5 * hp * exp(-t / 0.0015)
            out[i] = (s * env).toFloat()
        }
        return out
    }

    private fun beep(sr: Int, f: Double, ls: Double): FloatArray {
        val total = 0.08 * ls
        val hold = 0.045 * ls
        val out = FloatArray(len(sr, total))
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val a = min(1.0, t / 0.001)
            val rel = if (t < hold) 1.0 else max(0.0, 1 - (t - hold) / (total - hold))
            val s = sin(2 * PI * f * t) + 0.25 * sin(2 * PI * 3 * f * t) + 0.12 * sin(2 * PI * 5 * f * t)
            out[i] = (s * a * rel).toFloat()
        }
        return out
    }

    private fun wood(sr: Int, f0: Double, ls: Double): FloatArray {
        val out = FloatArray(len(sr, 0.12 * ls))
        val rnd = Random(11)
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val a = min(1.0, t / 0.0004)
            val s = sin(2 * PI * f0 * t) * exp(-t / (0.025 * ls)) +
                0.5 * sin(2 * PI * 2.76 * f0 * t) * exp(-t / (0.008 * ls)) +
                0.5 * rnd.nextGaussian() * exp(-t / 0.0008)
            out[i] = (s * a).toFloat()
        }
        return out
    }

    private fun cowbell(sr: Int, r: Double, ls: Double): FloatArray {
        val f1 = 587.0 * r * 1.25
        val f2 = 845.0 * r * 1.25
        val out = FloatArray(len(sr, 0.3 * ls))
        val nyq = sr / 2.5
        for (i in out.indices) {
            val t = i.toDouble() / sr
            var s = 0.0
            var h = 1
            while (h <= 9) {
                if (f1 * h < nyq) s += sin(2 * PI * f1 * h * t) / h
                if (f2 * h < nyq) s += sin(2 * PI * f2 * h * t) / h
                h += 2
            }
            val a = min(1.0, t / 0.0005)
            val env = 0.7 * exp(-t / (0.06 * ls)) + 0.3 * exp(-t / 0.008)
            out[i] = (s * a * env).toFloat()
        }
        return out
    }

    private fun stick(sr: Int, r: Double, ls: Double): FloatArray {
        val out = FloatArray(len(sr, 0.06 * ls))
        val rnd = Random(3)
        // RBJ 밴드패스 (중심 3 kHz)
        val w0 = 2 * PI * min(3000.0 * r, sr * 0.4) / sr
        val q = 1.2
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b0 = alpha / a0; val b2 = -alpha / a0
        val a1 = -2 * cos(w0) / a0; val a2 = (1 - alpha) / a0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        val tone = 1700.0 * r
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val x = rnd.nextGaussian()
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            val s = 1.5 * y * exp(-t / (0.006 * ls)) + 0.6 * sin(2 * PI * tone * t) * exp(-t / (0.01 * ls))
            out[i] = s.toFloat()
        }
        return out
    }

    private fun normalize(a: FloatArray, peak: Float) {
        var m = 0f
        for (v in a) m = max(m, abs(v))
        if (m <= 0f) return
        val g = peak / m
        for (i in a.indices) a[i] *= g
    }

    private fun fadeTail(a: FloatArray, sr: Int) {
        val n = min(a.size, max(8, sr / 500)) // 2 ms
        for (i in 0 until n) a[a.size - 1 - i] *= i.toFloat() / n
    }
}
