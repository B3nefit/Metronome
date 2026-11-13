package com.stageclick.metronome.audio

fun interface TickSink {
    fun accept(frame: Long, beat: Int, sub: Int, bar: Int, countIn: Boolean, level: Int)
}

/** 오디오 스레드 → UI 스레드로 클릭 이벤트를 넘기는 고정 크기 링 버퍼 (할당 없음). */
class TickRing(private val cap: Int) {
    private val frames = LongArray(cap)
    private val packed = IntArray(cap)
    private val bars = IntArray(cap)
    private var head = 0
    private var size = 0

    @Synchronized
    fun push(frame: Long, beat: Int, sub: Int, bar: Int, countIn: Boolean, level: Int) {
        val idx = (head + size) % cap
        frames[idx] = frame
        packed[idx] = (beat and 0xFF) or ((sub and 0xF) shl 8) or ((level and 0xF) shl 12) or (if (countIn) 1 shl 16 else 0)
        bars[idx] = bar
        if (size < cap) size++ else head = (head + 1) % cap
    }

    @Synchronized
    fun drain(sink: TickSink) {
        while (size > 0) {
            val p = packed[head]
            sink.accept(frames[head], p and 0xFF, (p shr 8) and 0xF, bars[head], (p shr 16) and 1 == 1, (p shr 12) and 0xF)
            head = (head + 1) % cap
            size--
        }
    }

    @Synchronized
    fun clear() { head = 0; size = 0 }
}
