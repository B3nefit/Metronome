package com.stageclick.metronome.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.stageclick.metronome.data.OutputProfile
import com.stageclick.metronome.data.Settings
import kotlin.math.max
import kotlin.math.pow

/**
 * 저지연 오디오 출력.
 *
 * 핵심 아이디어
 * 1) 앱이 화면에 떠 있는 동안 오디오 스트림을 항상 열어 두고 무음을 흘린다(웜 스트림).
 *    → START를 누르면 스트림을 새로 여는 지연 없이, 다음 버퍼(수 ms) 안에 첫 클릭이 나간다.
 *    → 블루투스는 무음 구간에 연결이 잠들어 첫 박이 잘리는 문제가 있는데, 이것도 막아 준다.
 * 2) AudioTrack PERFORMANCE_MODE_LOW_LATENCY + 기기 고유 샘플레이트/버스트 크기 → 안드로이드 FAST 경로.
 * 3) 버퍼 크기는 출력 방식에 맞춰 조절: 유선/무선 IEM은 최소(버스트 2개), 블루투스는 끊김 방지용으로 넉넉하게.
 *    재생 중 언더런(끊김)이 감지되면 버퍼를 한 단계씩 자동으로 늘린다.
 * 4) AudioTrack.getTimestamp()로 "이 샘플이 실제로 스피커/이어폰에서 나오는 시각"을 알아내
 *    화면 깜빡임을 소리에 맞춘다.
 */
class ClickEngine(context: Context) : TickListener {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val sampleRate: Int = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        ?.takeIf { it in 8000..192000 } ?: 48000
    val burst: Int = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
        ?.takeIf { it in 32..4096 } ?: 240

    val scheduler = ClickScheduler(sampleRate, ClickSounds.build(0, 1, sampleRate))
    private val ring = TickRing(256)
    private val main = Handler(Looper.getMainLooper())

    /** 카운트인 후 자동 정지 시 (메인 스레드에서 호출) */
    var onAutoStop: (() -> Unit)? = null

    @Volatile private var track: AudioTrack? = null
    private var audioThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var trackStartFrame = 0L
    /** 전체 타임라인 기준 지금까지 쓴 프레임 수 (스트림을 다시 열어도 계속 증가) */
    @Volatile var framesWritten = 0L
        private set
    @Volatile private var targetFrames = burst * 2
    @Volatile private var tuneRequest = false
    var capacityFrames = burst * 16
        private set
    @Volatile var actualBufferFrames = 0
        private set
    @Volatile var underruns = 0
        private set
    @Volatile var lowLatencyPath = false
        private set
    private var soundKey = -1

    private val ts = AudioTimestamp()
    private var tsFrame = 0L
    private var tsNanos = 0L
    private var tsValid = false
    private var tsQueriedAt = 0L

    val isPlaying: Boolean get() = scheduler.playing
    val isRunning: Boolean get() = running

    // ---------------- 스트림 수명 ----------------

    @Synchronized
    fun ensureRunning() {
        if (running) return
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        capacityFrames = max(if (minBytes > 0) minBytes / 4 else 0, burst * 16)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(capacityFrames * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack 생성 실패", e)
            null
        } ?: return
        if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); return }

        lowLatencyPath = t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        track = t
        trackStartFrame = framesWritten
        tsValid = false
        applyBufferSize(t)
        t.play()
        running = true
        audioThread = Thread({ audioLoop(t) }, "StageClick-Audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun shutdown() {
        running = false
        try { audioThread?.join(400) } catch (_: InterruptedException) {}
        audioThread = null
        track?.let { t ->
            try { t.pause(); t.flush() } catch (_: Exception) {}
            t.release()
        }
        track = null
        tsValid = false
    }

    private fun restartAfterError() {
        main.post {
            Log.w(TAG, "오디오 출력 재시작")
            shutdown()
            ensureRunning()
        }
    }

    private fun audioLoop(t: AudioTrack) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (_: Exception) {}
        val n = burst
        val fbuf = FloatArray(n * 2)
        val sbuf = ShortArray(n * 2)
        var lastUnderruns = t.underrunCount
        var lastCheck = framesWritten
        while (running) {
            if (tuneRequest) { tuneRequest = false; applyBufferSize(t) }
            scheduler.render(fbuf, n, framesWritten, this)
            for (i in 0 until n * 2) {
                val v = (fbuf[i] * 32767f).toInt()
                sbuf[i] = (if (v > 32767) 32767 else if (v < -32768) -32768 else v).toShort()
            }
            var off = 0
            while (off < n * 2 && running) {
                val w = t.write(sbuf, off, n * 2 - off, AudioTrack.WRITE_BLOCKING)
                if (w < 0) {
                    Log.e(TAG, "write 오류 $w")
                    if (running) restartAfterError()
                    return
                }
                if (w == 0) SystemClock.sleep(1)
                off += w
            }
            framesWritten += n

            // 끊김 감지 → 버퍼 한 단계 늘리기
            if (framesWritten - lastCheck > sampleRate / 4) {
                lastCheck = framesWritten
                val u = t.underrunCount
                if (u > lastUnderruns) {
                    lastUnderruns = u
                    underruns++
                    if (actualBufferFrames + burst <= capacityFrames) {
                        targetFrames = actualBufferFrames + burst
                        applyBufferSize(t)
                    }
                }
            }
        }
    }

    private fun applyBufferSize(t: AudioTrack) {
        val want = targetFrames.coerceIn(burst, capacityFrames)
        val r = t.setBufferSizeInFrames(want)
        actualBufferFrames = if (r > 0) r else t.bufferSizeInFrames
    }

    // ---------------- 재생 제어 ----------------

    fun start(p: ClickPattern) {
        ensureRunning()
        ring.clear()
        scheduler.start(p)
    }

    fun stop() = scheduler.stop()

    fun update(p: ClickPattern, resetBar: Boolean = false) = scheduler.update(p, resetBar)

    fun preview(level: Int) {
        ensureRunning()
        scheduler.preview(level)
    }

    fun applySettings(s: Settings) {
        val key = s.sound * 10 + s.clickLength
        if (key != soundKey) {
            soundKey = key
            scheduler.setSounds(ClickSounds.build(s.sound, s.clickLength, sampleRate))
        }
        scheduler.masterGain = (s.volume * 10.0.pow(s.boostDb / 20.0)).toFloat()
        scheduler.subGain = s.subVolume
        scheduler.channelMode = s.channel
        setProfile(s.profile)
    }

    private var currentProfile: OutputProfile? = null

    private fun setProfile(p: OutputProfile) {
        if (p == currentProfile) return
        currentProfile = p
        targetFrames = when (p) {
            OutputProfile.BLUETOOTH -> max(burst * 8, sampleRate / 25)   // ≥ 40 ms: 끊김 방지 우선
            else -> burst * 2                                            // 최소 지연
        }
        tuneRequest = true
    }

    // ---------------- 시간 정보 ----------------

    /** nanos(System.nanoTime 기준) 시각에 출력 장치에서 실제로 나오고 있는 프레임 번호 */
    fun presentedFrameAt(nanos: Long): Long? {
        val t = track ?: return null
        if (!running) return null
        val now = System.nanoTime()
        if (!tsValid || now - tsQueriedAt > 200_000_000L) {
            tsQueriedAt = now
            if (t.getTimestamp(ts) && ts.nanoTime > 0 && ts.framePosition > 0) {
                tsFrame = ts.framePosition; tsNanos = ts.nanoTime; tsValid = true
            } else {
                tsValid = false
            }
        }
        return if (tsValid) {
            trackStartFrame + tsFrame + (nanos - tsNanos) * sampleRate / 1_000_000_000L
        } else {
            // 타임스탬프를 아직 못 받았을 때의 근사값
            trackStartFrame + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
        }
    }

    /** 앱이 쓴 시점부터 실제로 귀에 들리기까지의 측정 지연(ms). 측정 불가 시 null */
    fun measuredLatencyMs(): Double? {
        val pf = presentedFrameAt(System.nanoTime()) ?: return null
        if (!tsValid) return null
        return (framesWritten - pf) * 1000.0 / sampleRate
    }

    fun routedDevice(): AudioDeviceInfo? = try { track?.routedDevice } catch (_: Exception) { null }

    fun drainTicks(sink: TickSink) = ring.drain(sink)

    // ---------------- 오디오 스레드 콜백 ----------------

    override fun onTick(frame: Long, beat: Int, sub: Int, bar: Int, countIn: Boolean, level: Int) {
        ring.push(frame, beat, sub, bar, countIn, level)
    }

    override fun onAutoStop(frame: Long) {
        main.post { onAutoStop?.invoke() }
    }

    companion object {
        private const val TAG = "StageClick"
    }
}
