package com.stageclick.metronome

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.stageclick.metronome.audio.LEVEL_MUTE
import com.stageclick.metronome.audio.LEVEL_STRONG
import com.stageclick.metronome.data.OutputProfile
import com.stageclick.metronome.data.Setlist
import com.stageclick.metronome.data.Song
import com.stageclick.metronome.ui.BeatView
import com.stageclick.metronome.ui.C
import com.stageclick.metronome.ui.PadButton
import com.stageclick.metronome.ui.dp
import com.stageclick.metronome.ui.pressable
import com.stageclick.metronome.ui.rounded
import kotlin.math.roundToInt

class MainActivity : Activity(), EngineEvents {

    internal val app: App get() = App.instance
    internal val repo get() = app.repo
    internal val engine get() = app.engine
    internal val data get() = app.repo.data
    internal val settings get() = app.repo.data.settings

    private class UiTick(val frame: Long, val beat: Int, val sub: Int, val bar: Int, val countIn: Boolean, val level: Int)

    // ---- 뷰 ----
    private lateinit var flashView: View
    private lateinit var setlistBtn: PadButton
    private lateinit var routeChip: PadButton
    private lateinit var settingsBtn: PadButton
    private lateinit var lockBtn: PadButton
    private lateinit var posText: TextView
    private lateinit var statusText: TextView
    private lateinit var titleText: TextView
    private lateinit var bpmText: TextView
    private lateinit var metaText: TextView
    private lateinit var noteText: TextView
    private lateinit var beatView: BeatView
    private lateinit var playBtn: PadButton
    private lateinit var prevBtn: PadButton
    private lateinit var nextBtn: PadButton
    private lateinit var listTitle: TextView
    private lateinit var listView: ListView
    private val lockables = ArrayList<View>()
    private val adapter = SongAdapter()
    private var built = false
    private var sc = 1f

    // ---- 상태 ----
    internal var locked = false
        private set
    private var lockedAtPress = false
    private var resumed = false
    private val uiTicks = ArrayDeque<UiTick>()
    internal val recentTickFrames = LongArray(32)
    internal var recentTickCount = 0
    private var shownPlaying = false
    private var flashUntil = 0L
    private var lastTickShownNs = 0L
    private val taps = ArrayList<Long>()
    internal var route: Route? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private var toast: Toast? = null

    // =====================================================================
    // 수명 주기
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        applyOrientation()
        buildUi()
    }

    override fun onStart() {
        super.onStart()
        app.events = this
        engine.ensureRunning()
        (getSystemService(AUDIO_SERVICE) as AudioManager).registerAudioDeviceCallback(deviceCallback, handler)
        updateRoute()
        shownPlaying = engine.isPlaying
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        enterImmersive()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        resumed = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        repo.saveNow()
        super.onPause()
    }

    override fun onStop() {
        (getSystemService(AUDIO_SERVICE) as AudioManager).unregisterAudioDeviceCallback(deviceCallback)
        // 재생 중이 아니면 오디오 장치를 놓아 배터리 절약 (재생 중이면 포그라운드 서비스가 유지)
        if (!engine.isPlaying) engine.shutdown()
        super.onStop()
    }

    override fun onDestroy() {
        if (app.events === this) app.events = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 회전해도 액티비티를 다시 만들지 않으므로 재생이 절대 끊기지 않는다. 레이아웃만 다시 구성.
        buildUi()
    }

    internal fun applyOrientation() {
        requestedOrientation = when (settings.orientation) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    // =====================================================================
    // 레이아웃 (세로/가로 각각 최적화)
    // =====================================================================

    private val M = ViewGroup.LayoutParams.MATCH_PARENT
    private val W = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).also { it.topMargin = top; it.leftMargin = left; it.rightMargin = right }

    private fun label(sizeSp: Float, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        includeFontPadding = false
        isSoundEffectsEnabled = false
    }

    private fun pad(text: String, sizeSp: Float, bg: Int = C.SURFACE2, stroke: Int = 0): PadButton = PadButton(this).apply {
        this.text = text
        textSize = sizeSp
        val r = dp(14f * sc).toFloat()
        background = pressable(bg, C.PRESSED, r, stroke, if (stroke != 0) dp(2) else 0)
    }

    private fun buildUi() {
        val cfg = resources.configuration
        val land = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        sc = if (land) (cfg.screenHeightDp / 390f).coerceIn(0.8f, 1.5f) else (cfg.screenWidthDp / 390f).coerceIn(0.8f, 1.5f)
        lockables.clear()
        val g = dp(8f * sc)

        // ---- 상단 바 ----
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        setlistBtn = pad("", 16f * sc).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(14), 0, dp(10), 0)
            ellipsize = TextUtils.TruncateAt.END
            onPress = { if (guard()) showSetlistChooser() }
        }
        routeChip = pad("", 14f * sc).apply {
            setPadding(dp(12), 0, dp(12), 0)
            onPress = { if (guard()) showSettings() }
        }
        settingsBtn = pad("⚙", 22f * sc).apply { onPress = { if (guard()) showSettings() } }
        lockBtn = pad("🔓", 20f * sc).apply {
            onPress = {
                lockedAtPress = locked
                if (!locked) setLocked(true) else showToast("🔒 잠금 해제: 자물쇠를 길게 누르세요")
            }
            onHold = { if (lockedAtPress) setLocked(false) }
        }
        val topH = dp((if (land) 44f else 48f) * sc)
        top.addView(setlistBtn, lp(0, M, 1f))
        top.addView(routeChip, lp(W, M, left = g))
        top.addView(settingsBtn, lp(topH, M, left = g))
        top.addView(lockBtn, lp(topH, M, left = g))
        lockables += listOf(setlistBtn, routeChip, settingsBtn)

        // ---- 곡 정보 ----
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        posText = label(16f * sc, C.DIM, true)
        statusText = label(18f * sc, C.DIM, true)
        row1.addView(posText, lp(0, W, 1f))
        row1.addView(statusText, lp(W, W))
        titleText = label((if (land) 24f else 30f) * sc, C.TEXT, true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { if (guard()) repo.currentSong()?.let { editSong(data.currentIndex) } ?: editSong(null) }
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        bpmText = label((if (land) 64f else 92f) * sc, C.TEXT, true).apply {
            setOnClickListener { if (guard()) promptBpm() }
        }
        val bpmSide = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, dp(10f * sc)) }
        bpmSide.addView(label(16f * sc, C.ACCENT, true).apply { text = "BPM" })
        metaText = label(16f * sc, C.DIM, false).apply { maxLines = 2 }
        bpmSide.addView(metaText, lp(W, W, top = dp(4)))
        row2.addView(bpmText, lp(W, W))
        row2.addView(bpmSide, lp(0, W, 1f))
        noteText = label(14f * sc, C.DIM).apply { maxLines = if (land) 1 else 2; ellipsize = TextUtils.TruncateAt.END }
        info.addView(row1, lp(M, W))
        info.addView(titleText, lp(M, W, top = dp(6f * sc)))
        info.addView(row2, lp(M, W, top = dp(2f * sc)))
        info.addView(noteText, lp(M, W, top = dp(2f * sc)))
        lockables += listOf(titleText, bpmText)

        // ---- 박 표시 ----
        beatView = BeatView(this).apply {
            onBeatTap = { i -> if (guard()) cycleAccent(i) }
        }
        lockables += beatView

        // ---- 템포 ----
        val tempo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tb(t: String, d: Double) = pad(t, 22f * sc).apply { repeat = true; onPress = { if (guard()) changeBpm(d) } }
        val tap = pad("TAP", 20f * sc, C.SURFACE2, C.ACCENT).apply { setTextColor(C.ACCENT); onPress = { if (guard()) tapTempo() } }
        listOf(tb("−5", -5.0), tb("−1", -1.0), tap, tb("+1", 1.0), tb("+5", 5.0)).forEachIndexed { i, b ->
            tempo.addView(b, lp(0, M, 1f, left = if (i == 0) 0 else g))
            lockables += b
        }

        // ---- 재생 ----
        val transport = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        prevBtn = pad("◀◀", 24f * sc).apply { onPress = { if (guard()) selectSong(data.currentIndex - 1) } }
        nextBtn = pad("▶▶", 24f * sc).apply { onPress = { if (guard()) selectSong(data.currentIndex + 1) } }
        playBtn = pad("", 30f * sc).apply { onPress = { togglePlay() } }
        transport.addView(prevBtn, lp(0, M, 1f))
        transport.addView(playBtn, lp(0, M, 2.3f, left = g))
        transport.addView(nextBtn, lp(0, M, 1f, left = g))
        lockables += listOf(prevBtn, nextBtn)

        // ---- 세트리스트 ----
        val listPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val listHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listTitle = label(16f * sc, C.DIM, true)
        val addBtn = pad("＋ 곡 추가", 15f * sc).apply {
            setPadding(dp(14), 0, dp(14), 0)
            onPress = { if (guard()) editSong(null) }
        }
        listHeader.addView(listTitle, lp(0, W, 1f))
        listHeader.addView(addBtn, lp(W, M))
        lockables += addBtn
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(6f * sc)
            selector = ColorDrawable(Color.TRANSPARENT)
            isSoundEffectsEnabled = false
            isVerticalScrollBarEnabled = true
            setOnItemClickListener { _, _, pos, _ ->
                if (!guard()) return@setOnItemClickListener
                if (engine.isPlaying && pos != data.currentIndex) {
                    showToast("재생 중에는 곡을 길게 눌러 '지금 전환'을 선택하세요")
                } else selectSong(pos)
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                if (guard()) songMenu(pos)
                true
            }
        }
        lockables += listView
        listPane.addView(listHeader, lp(M, dp(40f * sc)))
        listPane.addView(listView, lp(M, 0, 1f, top = dp(6)))

        // ---- 조립 ----
        val content = LinearLayout(this).apply {
            orientation = if (land) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val mainCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mainCol.addView(top, lp(M, topH))
        if (land) {
            mainCol.addView(info, lp(M, 0, 1f, top = g))
            mainCol.addView(beatView, lp(M, dp(54f * sc), top = g))
            mainCol.addView(tempo, lp(M, dp(50f * sc), top = g))
            mainCol.addView(transport, lp(M, dp(76f * sc), top = g))
            content.addView(mainCol, lp(0, M, 1.25f))
            content.addView(listPane, lp(0, M, 1f, left = dp(12)))
        } else {
            mainCol.addView(info, lp(M, W, top = g))
            mainCol.addView(beatView, lp(M, dp(76f * sc), top = g))
            mainCol.addView(tempo, lp(M, dp(58f * sc), top = g))
            mainCol.addView(transport, lp(M, dp(96f * sc), top = g))
            content.addView(mainCol, lp(M, W))
            content.addView(listPane, lp(M, 0, 1f, top = dp(12f * sc)))
        }
        flashView = View(this)
        val root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        root.addView(content, FrameLayout.LayoutParams(M, M))
        root.addView(flashView, FrameLayout.LayoutParams(M, M))
        setContentView(root)
        built = true
        setLocked(locked)
        refreshAll()
    }

    // =====================================================================
    // 화면 갱신
    // =====================================================================

    internal fun refreshAll() {
        if (!built) return
        refreshTop()
        refreshSong()
        refreshTransport()
        refreshList()
    }

    private fun refreshTop() {
        setlistBtn.text = "📋  ${repo.currentSetlist().name}  ▾"
        refreshRouteChip()
    }

    internal fun refreshRouteChip() {
        if (!built) return
        val r = route ?: Route.SPEAKER
        val p = settings.profile
        val mismatch = (r == Route.BLUETOOTH) != (p == OutputProfile.BLUETOOTH) && r != Route.SPEAKER
        val (txt, bg, fg) = when {
            r == Route.SPEAKER -> Triple("⚠ 스피커", C.WARN, C.TEXT)
            mismatch -> Triple("${icon(r)} ${p.short} !", C.SURFACE2, C.ACCENT)
            else -> Triple("${icon(r)} ${p.short}", C.SURFACE2, C.TEXT)
        }
        routeChip.text = txt
        routeChip.setTextColor(fg)
        routeChip.background = pressable(bg, C.PRESSED, dp(14f * sc).toFloat())
    }

    private fun icon(r: Route) = when (r) {
        Route.BLUETOOTH -> "📶"
        Route.SPEAKER -> "🔊"
        else -> "🎧"
    }

    internal fun refreshSong() {
        if (!built) return
        val sl = repo.currentSetlist()
        val s = repo.currentSong()
        if (s == null) {
            posText.text = "곡 없음"
            titleText.text = "＋ 곡 추가를 눌러 시작하세요"
            bpmText.text = "--"
            metaText.text = ""
            noteText.visibility = View.GONE
            beatView.configure(4, Song.defaultAccents(4), 1)
            return
        }
        posText.text = "${data.currentIndex + 1} / ${sl.songs.size}"
        titleText.text = s.title
        bpmText.text = fmtBpm(s.bpm)
        metaText.text = buildString {
            append("${s.beatsPerBar}/${s.beatUnit}")
            if (s.subdivision > 1) append(" · ").append(subName(s.subdivision))
            if (s.countInBars > 0) append(" · 카운트 ${s.countInBars}마디")
            if (s.stopAfterCountIn) append(" (카운트만)")
        }
        noteText.text = s.note
        noteText.visibility = if (s.note.isBlank()) View.GONE else View.VISIBLE
        beatView.configure(s.beatsPerBar, s.accents, s.subdivision)
        if (!engine.isPlaying) setStatus(if (locked) "🔒 READY" else "READY", C.DIM)
    }

    internal fun refreshList() {
        if (!built) return
        listTitle.text = "곡 목록 (${repo.currentSetlist().songs.size})"
        adapter.notifyDataSetChanged()
        val i = data.currentIndex
        listView.post {
            if (i < listView.firstVisiblePosition || i > listView.lastVisiblePosition - 1) listView.smoothScrollToPosition(i)
        }
    }

    private fun refreshTransport() {
        if (!built) return
        val playing = engine.isPlaying
        playBtn.text = if (playing) "■  STOP" else "▶  START"
        playBtn.setTextColor(C.BG)
        playBtn.background = pressable(if (playing) C.STOP else C.PLAY, C.PRESSED, dp(16f * sc).toFloat())
    }

    private fun setStatus(t: String, color: Int) {
        statusText.text = t
        statusText.setTextColor(color)
    }

    private fun setLocked(v: Boolean) {
        locked = v
        if (!built) return
        lockBtn.text = if (v) "🔒" else "🔓"
        lockBtn.background = pressable(if (v) C.ACCENT else C.SURFACE2, C.PRESSED, dp(14f * sc).toFloat())
        lockables.forEach { it.alpha = if (v) 0.35f else 1f }
        if (!engine.isPlaying) setStatus(if (v) "🔒 READY" else "READY", C.DIM)
    }

    /** 잠금 중이면 false (터치 무시) */
    private fun guard(): Boolean {
        if (locked) {
            showToast("🔒 잠금 중 — 자물쇠를 길게 누르면 해제")
            return false
        }
        return true
    }

    internal fun showToast(msg: String) {
        toast?.cancel()
        toast = Toast.makeText(this, msg, Toast.LENGTH_SHORT).also { it.show() }
    }

    internal fun fmtBpm(b: Double): String =
        if (b % 1.0 == 0.0) b.toInt().toString() else String.format("%.1f", b)

    internal fun subName(sd: Int) = when (sd) { 2 -> "2분할"; 3 -> "셋잇단"; 4 -> "4분할"; else -> "" }

    // =====================================================================
    // 동작
    // =====================================================================

    private fun togglePlay() {
        if (engine.isPlaying) stopPlayback(userStop = true) else startPlayback()
    }

    private fun startPlayback() {
        val s = repo.currentSong() ?: run { showToast("곡을 먼저 추가하세요"); return }
        uiTicks.clear()
        engine.start(s.pattern())   // ← 여기서 바로 다음 오디오 버퍼에 첫 클릭이 들어간다
        shownPlaying = true
        refreshTransport()
        setStatus(if (s.countInBars > 0) "COUNT-IN" else "▶ PLAY", if (s.countInBars > 0) C.COUNT else C.PLAY)
        app.startPlaybackService(s.title)
    }

    private fun stopPlayback(userStop: Boolean) {
        engine.stop()
        app.stopPlaybackService()
        uiTicks.clear()
        beatView.reset()
        flashView.setBackgroundColor(0)
        shownPlaying = false
        refreshTransport()
        setStatus(if (locked) "🔒 READY" else "READY", C.DIM)
        if (userStop && settings.autoNext) advanceQuietly()
    }

    private fun advanceQuietly() {
        val n = repo.currentSetlist().songs.size
        if (data.currentIndex < n - 1) selectSong(data.currentIndex + 1)
    }

    internal fun selectSong(index: Int) {
        val songs = repo.currentSetlist().songs
        if (songs.isEmpty()) return
        val i = index.coerceIn(0, songs.size - 1)
        data.currentIndex = i
        if (engine.isPlaying) engine.update(songs[i].pattern(), resetBar = true)
        repo.save()
        refreshSong()
        refreshList()
    }

    private fun setBpm(v: Double) {
        val s = repo.currentSong() ?: return
        s.bpm = v
        s.normalize()
        if (engine.isPlaying) engine.update(s.pattern())
        bpmText.text = fmtBpm(s.bpm)
        adapter.notifyDataSetChanged()
        repo.save()
    }

    private fun changeBpm(d: Double) {
        val s = repo.currentSong() ?: return
        setBpm(s.bpm + d)
    }

    private fun tapTempo() {
        val now = SystemClock.uptimeMillis()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.add(now)
        if (taps.size > 6) taps.removeAt(0)
        if (taps.size >= 2) {
            val iv = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            setBpm((60000.0 / iv).roundToInt().toDouble())
        }
    }

    private fun cycleAccent(i: Int) {
        val s = repo.currentSong() ?: return
        if (i >= s.accents.size) return
        s.accents[i] = when (s.accents[i]) { LEVEL_STRONG -> 1; 1 -> LEVEL_MUTE; else -> LEVEL_STRONG }
        if (engine.isPlaying) engine.update(s.pattern())
        beatView.configure(s.beatsPerBar, s.accents, s.subdivision)
        repo.save()
    }

    internal fun onDataChanged() {
        val n = repo.currentSetlist().songs.size
        data.currentIndex = if (n == 0) 0 else data.currentIndex.coerceIn(0, n - 1)
        refreshAll()
    }

    internal fun currentSetlistOrNull(): Setlist = repo.currentSetlist()

    // =====================================================================
    // 출력 장치 감지
    // =====================================================================

    private val routeTask = Runnable { updateRoute() }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = scheduleRouteUpdate()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = scheduleRouteUpdate()
    }

    private fun scheduleRouteUpdate() {
        handler.removeCallbacks(routeTask)
        handler.postDelayed(routeTask, 400)
        handler.postDelayed({ updateRoute() }, 1500) // 라우팅 전환이 늦는 기기 대비
    }

    private fun updateRoute() {
        val r = app.currentRoute()
        val changed = r != route
        route = r
        val s = settings
        if (changed && s.autoRoute) {
            if (r == Route.BLUETOOTH && s.profile != OutputProfile.BLUETOOTH) {
                s.lastWiredProfile = s.profile
                s.profile = OutputProfile.BLUETOOTH
                app.applySettings(); repo.save()
                showToast("블루투스 감지 → 블루투스 모드로 전환")
            } else if ((r == Route.WIRED || r == Route.USB) && s.profile == OutputProfile.BLUETOOTH) {
                s.profile = if (s.lastWiredProfile != OutputProfile.BLUETOOTH) s.lastWiredProfile else OutputProfile.WIRED
                app.applySettings(); repo.save()
                showToast("유선 출력 감지 → ${s.profile.short} 모드로 전환")
            }
        }
        refreshRouteChip()
    }

    // =====================================================================
    // 화면 동기화 (오디오 타임스탬프 기준)
    // =====================================================================

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onFrame()
            if (resumed) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun offsetFrames(): Long = settings.offsetFor(settings.profile).toLong() * engine.sampleRate / 1000

    private fun onFrame() {
        if (!built) return
        engine.drainTicks { f, b, sb, bar, cin, lvl ->
            uiTicks.addLast(UiTick(f, b, sb, bar, cin, lvl))
            if (lvl != LEVEL_MUTE) {
                recentTickFrames[recentTickCount % recentTickFrames.size] = f
                recentTickCount++
            }
            while (uiTicks.size > 64) uiTicks.removeFirst()
        }
        val now = System.nanoTime()
        val pf = engine.presentedFrameAt(now)
        if (pf != null) {
            val display = pf - offsetFrames()
            var latest: UiTick? = null
            while (uiTicks.isNotEmpty() && uiTicks.first().frame <= display) latest = uiTicks.removeFirst()
            latest?.let { showTick(it, now) }
        }
        val playing = engine.isPlaying
        if (playing != shownPlaying) {
            shownPlaying = playing
            refreshTransport()
            if (!playing) setStatus(if (locked) "🔒 READY" else "READY", C.DIM)
        }
        if (!playing && uiTicks.isEmpty() && lastTickShownNs != 0L && now - lastTickShownNs > 600_000_000L) {
            beatView.reset(); lastTickShownNs = 0L
        }
        if (flashUntil != 0L && now >= flashUntil) {
            flashView.setBackgroundColor(0)
            flashUntil = 0L
        }
    }

    private fun showTick(t: UiTick, now: Long) {
        beatView.show(t.beat, t.sub, t.countIn)
        lastTickShownNs = now
        if (t.countIn) setStatus("COUNT-IN  ${t.beat + 1}", C.COUNT)
        else if (engine.isPlaying) setStatus("▶ 마디 ${t.bar + 1}", C.PLAY)
        if (settings.flash && t.sub == 0 && t.level != LEVEL_MUTE) {
            val color = when {
                t.countIn -> 0x5529B6F6
                t.level == LEVEL_STRONG -> 0x55FFB300
                else -> 0x26FFFFFF
            }
            flashView.setBackgroundColor(color)
            flashUntil = now + 90_000_000L
        }
    }

    // =====================================================================
    // 엔진 이벤트
    // =====================================================================

    override fun onAutoStop() {
        shownPlaying = false
        refreshTransport()
        setStatus("카운트인 완료", C.COUNT)
        if (settings.autoNext) advanceQuietly()
    }

    override fun onExternalStop(reason: String) {
        runOnUiThread {
            uiTicks.clear()
            beatView.reset()
            shownPlaying = false
            refreshTransport()
            setStatus("정지됨", C.WARN)
            showToast(reason)
        }
    }

    // =====================================================================
    // 블루투스 페달 / 키보드
    // =====================================================================

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (event.repeatCount == 0) {
                    when {
                        keyCode == KeyEvent.KEYCODE_MEDIA_PLAY && engine.isPlaying -> {}
                        keyCode == KeyEvent.KEYCODE_MEDIA_STOP && !engine.isPlaying -> {}
                        else -> togglePlay()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (event.repeatCount == 0) selectSong(data.currentIndex + 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (event.repeatCount == 0) selectSong(data.currentIndex - 1)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // =====================================================================
    // 파일 내보내기 / 가져오기 (Storage Access Framework — 권한·인터넷 불필요)
    // =====================================================================

    internal var pendingExport: String? = null

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { pendingExport = null; return }
        try {
            when (requestCode) {
                REQ_EXPORT -> {
                    val text = pendingExport ?: return
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    showToast("내보내기 완료")
                }
                REQ_IMPORT -> {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return
                    val n = repo.importJson(text)
                    if (n > 0) {
                        showToast("세트리스트 ${n}개를 가져왔습니다")
                        refreshAll()
                    } else showToast("세트리스트를 찾지 못했습니다")
                }
            }
        } catch (e: Exception) {
            showToast("파일 처리 실패: ${e.message}")
        } finally {
            pendingExport = null
        }
    }

    // =====================================================================
    // 목록 어댑터
    // =====================================================================

    private class RowHolder(val num: TextView, val title: TextView, val bpm: TextView, val meta: TextView)

    private inner class SongAdapter : BaseAdapter() {
        override fun getCount() = repo.currentSetlist().songs.size
        override fun getItem(position: Int): Any = repo.currentSetlist().songs[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val h = row.tag as RowHolder
            val s = repo.currentSetlist().songs[position]
            val cur = position == data.currentIndex
            h.num.text = "${position + 1}"
            h.title.text = s.title
            h.bpm.text = fmtBpm(s.bpm)
            h.meta.text = "${s.beatsPerBar}/${s.beatUnit}" + if (s.countInBars > 0) " · C${s.countInBars}" else ""
            h.title.setTextColor(if (cur) C.ACCENT else C.TEXT)
            h.num.setTextColor(if (cur) C.ACCENT else C.DIM)
            row.background = if (cur) rounded(C.SURFACE2, dp(12).toFloat(), C.ACCENT, dp(2)) else rounded(C.SURFACE, dp(12).toFloat())
            return row
        }

        private fun makeRow(): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(58f * sc)
                setPadding(dp(12), dp(6), dp(14), dp(6))
                isSoundEffectsEnabled = false
            }
            val num = label(18f * sc, C.DIM, true).apply { minWidth = dp(34f * sc) }
            val title = label(20f * sc, C.TEXT, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            val right = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            val bpm = label(22f * sc, C.TEXT, true).apply { gravity = Gravity.END }
            val meta = label(12f * sc, C.DIM).apply { gravity = Gravity.END }
            right.addView(bpm, LinearLayout.LayoutParams(W, W))
            right.addView(meta, LinearLayout.LayoutParams(W, W))
            row.addView(num, LinearLayout.LayoutParams(W, W))
            row.addView(title, LinearLayout.LayoutParams(0, W, 1f))
            row.addView(right, LinearLayout.LayoutParams(W, W).also { it.leftMargin = dp(8) })
            row.tag = RowHolder(num, title, bpm, meta)
            return row
        }
    }

    companion object {
        internal const val REQ_EXPORT = 41
        internal const val REQ_IMPORT = 42
    }
}
