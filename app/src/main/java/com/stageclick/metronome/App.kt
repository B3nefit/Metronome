package com.stageclick.metronome

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.stageclick.metronome.audio.ClickEngine
import com.stageclick.metronome.data.Repository

interface EngineEvents {
    /** 카운트인만 주는 곡이 끝나 엔진이 스스로 멈춤 */
    fun onAutoStop()
    /** 이어폰 분리·알림창 정지 버튼 등 외부 요인으로 멈춤 */
    fun onExternalStop(reason: String)
}

enum class Route(val label: String) { SPEAKER("스피커"), WIRED("유선"), USB("USB"), BLUETOOTH("블루투스"), OTHER("외부 출력") }

class App : Application() {
    lateinit var repo: Repository
        private set
    lateinit var engine: ClickEngine
        private set
    var events: EngineEvents? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = Repository(this)
        engine = ClickEngine(this)
        engine.applySettings(repo.data.settings)
        engine.onAutoStop = {
            stopPlaybackService()
            events?.onAutoStop()
        }
        registerNoisyReceiver()
    }

    fun applySettings() = engine.applySettings(repo.data.settings)

    fun startPlaybackService(title: String) {
        try {
            startForegroundService(Intent(this, PlaybackService::class.java).putExtra(PlaybackService.EXTRA_TITLE, title))
        } catch (_: Exception) {
            // 백그라운드 시작 제한 등 — 재생 자체에는 영향 없음
        }
    }

    fun stopPlaybackService() {
        try { stopService(Intent(this, PlaybackService::class.java)) } catch (_: Exception) {}
    }

    /** 이어폰/송신기 케이블이 빠지거나 블루투스가 끊기면 안드로이드가 보내는 신호 → 스피커로 클릭이 새는 것 방지 */
    private fun registerNoisyReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                if (repo.data.settings.stopOnUnplug && engine.isPlaying) {
                    engine.stop()
                    stopPlaybackService()
                    events?.onExternalStop("출력 장치가 분리되어 자동 정지했습니다")
                }
            }
        }
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    fun currentRoute(): Route {
        val d = engine.routedDevice()
        if (d != null) return classify(d.type)
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { classify(it.type) }
        return when {
            Route.WIRED in outs -> Route.WIRED
            Route.USB in outs -> Route.USB
            Route.BLUETOOTH in outs -> Route.BLUETOOTH
            else -> Route.SPEAKER
        }
    }

    companion object {
        lateinit var instance: App
            private set

        fun classify(type: Int): Route = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            26 /* BLE_HEADSET */, 27 /* BLE_SPEAKER */, 30 /* BLE_BROADCAST */ -> Route.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> Route.WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Route.USB
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, 24 /* BUILTIN_SPEAKER_SAFE */ -> Route.SPEAKER
            else -> Route.OTHER
        }
    }
}
