package com.stageclick.metronome

import android.app.AlertDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.stageclick.metronome.audio.ClickPattern
import com.stageclick.metronome.audio.ClickSounds
import com.stageclick.metronome.audio.LEVEL_NORMAL
import com.stageclick.metronome.audio.LEVEL_STRONG
import com.stageclick.metronome.data.OutputProfile
import com.stageclick.metronome.data.Settings
import com.stageclick.metronome.data.Setlist
import com.stageclick.metronome.data.Song
import com.stageclick.metronome.ui.C
import com.stageclick.metronome.ui.PadButton
import com.stageclick.metronome.ui.dp
import com.stageclick.metronome.ui.pressable
import java.util.Collections
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DLG = android.R.style.Theme_Material_Dialog_Alert
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

// ------------------------------------------------------------------ 공통 도우미

private fun MainActivity.builder() = AlertDialog.Builder(this, DLG)

private fun MainActivity.form(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(22), dp(8), dp(22), dp(8))
}

private fun MainActivity.scroll(v: View) = ScrollView(this).apply { addView(v) }

private fun MainActivity.caption(t: String, color: Int = C.DIM, size: Float = 13f) = TextView(this).apply {
    text = t; textSize = size; setTextColor(color)
}

private fun MainActivity.section(parent: LinearLayout, t: String) {
    parent.addView(TextView(this).apply {
        text = t; textSize = 15f; setTextColor(C.ACCENT)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(18); it.bottomMargin = dp(4) })
}

private fun MainActivity.add(parent: LinearLayout, v: View, top: Int = 6) {
    parent.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(top) })
}

private fun MainActivity.spinner(items: Array<String>, selected: Int, onSelect: ((Int) -> Unit)? = null) = Spinner(this).apply {
    adapter = ArrayAdapter(this@spinner, android.R.layout.simple_spinner_item, items).also {
        it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
    setSelection(selected.coerceIn(0, items.size - 1))
    if (onSelect != null) {
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onSelect(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }
}

private fun MainActivity.labeled(parent: LinearLayout, title: String, v: View) {
    add(parent, caption(title), 12)
    add(parent, v, 2)
}

private fun MainActivity.seek(parent: LinearLayout, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): Pair<TextView, SeekBar> {
    val t = caption(fmt(value), C.TEXT, 15f)
    val sb = SeekBar(this).apply {
        this.max = max - min
        progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                t.text = fmt(p + min)
                if (fromUser) onChange(p + min)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
    add(parent, t, 12)
    add(parent, sb, 2)
    return t to sb
}

private fun MainActivity.check(parent: LinearLayout, title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    add(parent, CheckBox(this).apply {
        text = title; textSize = 15f; isChecked = value
        setOnCheckedChangeListener { _, c -> onChange(c) }
    }, 4)
}

internal fun MainActivity.prompt(title: String, initial: String, inputType: Int = InputType.TYPE_CLASS_TEXT, onOk: (String) -> Unit) {
    val f = form()
    val e = EditText(this).apply {
        setText(initial); this.inputType = inputType; setSingleLine(); selectAll()
    }
    add(f, e, 8)
    val d = builder().setTitle(title).setView(f)
        .setPositiveButton("확인") { _, _ -> onOk(e.text.toString()) }
        .setNegativeButton("취소", null).create()
    d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    e.requestFocus()
}

internal fun MainActivity.confirm(title: String, msg: String, yes: String, onYes: () -> Unit) {
    builder().setTitle(title).setMessage(msg)
        .setPositiveButton(yes) { _, _ -> onYes() }
        .setNegativeButton("취소", null).show()
}

// ------------------------------------------------------------------ BPM 직접 입력

internal fun MainActivity.promptBpm() {
    val s = repo.currentSong() ?: return
    prompt("BPM 입력 (20 ~ 400)", fmtBpm(s.bpm), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) { txt ->
        val v = txt.replace(',', '.').toDoubleOrNull() ?: return@prompt
        s.bpm = v; s.normalize()
        if (engine.isPlaying) engine.update(s.pattern())
        repo.save(); refreshSong(); refreshList()
    }
}

// ------------------------------------------------------------------ 곡 편집

internal fun MainActivity.editSong(index: Int?) {
    val sl = repo.currentSetlist()
    val isNew = index == null || index !in sl.songs.indices
    val s = if (isNew) Song(title = "새 곡 ${sl.songs.size + 1}") else sl.songs[index!!].copy()
    val f = form()
    val title = EditText(this).apply { setText(s.title); setSingleLine() }
    val bpm = EditText(this).apply {
        setText(fmtBpm(s.bpm)); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setSingleLine()
    }
    val units = intArrayOf(2, 4, 8, 16)
    val beats = spinner(Array(16) { "${it + 1}" }, s.beatsPerBar - 1)
    val unit = spinner(units.map { "$it" }.toTypedArray(), units.indexOf(s.beatUnit).coerceAtLeast(1))
    val sub = spinner(arrayOf("없음 (박마다 1번)", "2분할 (8분음표)", "3분할 (셋잇단)", "4분할 (16분음표)"), s.subdivision - 1)
    val countIn = spinner(arrayOf("없음", "1마디", "2마디", "3마디", "4마디"), s.countInBars)
    val stopAfter = CheckBox(this).apply { text = "카운트인만 주고 클릭 정지"; isChecked = s.stopAfterCountIn; textSize = 15f }
    val note = EditText(this).apply { setText(s.note); hint = "예: 드럼 인트로 4마디 후 시작"; minLines = 2 }

    labeled(f, "제목", title)
    labeled(f, "BPM (클릭 단위 기준, 소수점 1자리까지)", bpm)
    val sig = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    sig.addView(beats, LinearLayout.LayoutParams(0, WRAP, 1f))
    sig.addView(caption(" / ", C.TEXT, 20f))
    sig.addView(unit, LinearLayout.LayoutParams(0, WRAP, 1f))
    labeled(f, "박자 (한 마디의 클릭 수 / 음표 단위)", sig)
    labeled(f, "분할 클릭", sub)
    labeled(f, "카운트인", countIn)
    add(f, stopAfter, 4)
    labeled(f, "메모 (무대 화면에 표시)", note)
    add(f, caption("※ 강박·약박·무음은 메인 화면의 박 원을 눌러 바꿉니다."), 12)

    val b = builder().setTitle(if (isNew) "곡 추가" else "곡 편집").setView(scroll(f))
        .setPositiveButton("저장") { _, _ ->
            s.title = title.text.toString().trim().ifEmpty { "제목 없음" }
            bpm.text.toString().replace(',', '.').toDoubleOrNull()?.let { s.bpm = it }
            s.beatsPerBar = beats.selectedItemPosition + 1
            s.beatUnit = units[unit.selectedItemPosition]
            s.subdivision = sub.selectedItemPosition + 1
            s.countInBars = countIn.selectedItemPosition
            s.stopAfterCountIn = stopAfter.isChecked
            s.note = note.text.toString().trim()
            s.normalize()
            if (isNew) {
                val at = if (sl.songs.isEmpty()) 0 else data.currentIndex + 1
                sl.songs.add(at, s)
                if (!engine.isPlaying) data.currentIndex = at
            } else {
                sl.songs[index!!] = s
                if (engine.isPlaying && index == data.currentIndex) engine.update(s.pattern())
            }
            repo.save()
            onDataChanged()
        }
        .setNegativeButton("취소", null)
    if (!isNew) b.setNeutralButton("삭제") { _, _ -> deleteSong(index!!) }
    b.show()
}

private fun MainActivity.deleteSong(pos: Int) {
    val sl = repo.currentSetlist()
    if (engine.isPlaying && pos == data.currentIndex) { showToast("재생 중인 곡은 정지 후 삭제하세요"); return }
    confirm("곡 삭제", "'${sl.songs[pos].title}'을(를) 삭제할까요?", "삭제") {
        sl.songs.removeAt(pos)
        if (pos < data.currentIndex) data.currentIndex--
        repo.save(); onDataChanged()
    }
}

internal fun MainActivity.songMenu(pos: Int) {
    val sl = repo.currentSetlist()
    if (pos !in sl.songs.indices) return
    val playingSwitch = engine.isPlaying && pos != data.currentIndex
    val items = ArrayList<String>()
    if (playingSwitch) items += "▶ 지금 이 곡으로 전환"
    items += listOf("편집", "위로 이동", "아래로 이동", "복제", "삭제")
    builder().setTitle(sl.songs[pos].title).setItems(items.toTypedArray()) { _, which ->
        when (items[which]) {
            "▶ 지금 이 곡으로 전환" -> selectSong(pos)
            "편집" -> editSong(pos)
            "위로 이동" -> moveSong(sl, pos, pos - 1)
            "아래로 이동" -> moveSong(sl, pos, pos + 1)
            "복제" -> {
                sl.songs.add(pos + 1, sl.songs[pos].copy(newIdentity = true).also { it.title += " (복사)" })
                if (data.currentIndex > pos) data.currentIndex++
                repo.save(); onDataChanged()
            }
            "삭제" -> deleteSong(pos)
        }
    }.show()
}

private fun MainActivity.moveSong(sl: Setlist, from: Int, to: Int) {
    if (to !in sl.songs.indices) return
    Collections.swap(sl.songs, from, to)
    if (data.currentIndex == from) data.currentIndex = to else if (data.currentIndex == to) data.currentIndex = from
    repo.save(); onDataChanged()
}

// ------------------------------------------------------------------ 세트리스트

internal fun MainActivity.showSetlistChooser() {
    val lists = data.setlists
    val names = lists.map { "${it.name}   (${it.songs.size}곡)" }.toTypedArray()
    val cur = lists.indexOfFirst { it.id == data.currentSetlistId }.coerceAtLeast(0)
    builder().setTitle("세트리스트 선택")
        .setSingleChoiceItems(names, cur) { d, which ->
            if (engine.isPlaying && which != cur) {
                showToast("재생 중에는 세트리스트를 바꿀 수 없습니다")
            } else {
                data.currentSetlistId = lists[which].id
                data.currentIndex = 0
                repo.save(); refreshAll()
            }
            d.dismiss()
        }
        .setPositiveButton("＋ 새로 만들기") { _, _ -> createSetlist() }
        .setNeutralButton("관리…") { _, _ -> manageSetlists() }
        .setNegativeButton("닫기", null)
        .show()
}

private fun MainActivity.createSetlist() {
    if (engine.isPlaying) { showToast("정지 후 만들어 주세요"); return }
    prompt("새 세트리스트 이름", "공연 ${data.setlists.size + 1}") { name ->
        val sl = Setlist(name = name.trim().ifEmpty { "새 세트리스트" })
        data.setlists.add(sl)
        data.currentSetlistId = sl.id
        data.currentIndex = 0
        repo.save(); refreshAll()
    }
}

private fun MainActivity.manageSetlists() {
    val sl = repo.currentSetlist()
    val items = arrayOf("이름 변경", "복제", "삭제", "이 세트리스트 내보내기 (JSON)", "전체 백업 내보내기 (JSON)", "가져오기 (JSON)")
    builder().setTitle("‘${sl.name}’ 관리").setItems(items) { _, which ->
        when (which) {
            0 -> prompt("세트리스트 이름", sl.name) { n -> sl.name = n.trim().ifEmpty { sl.name }; repo.save(); refreshAll() }
            1 -> {
                val copy = Setlist(name = sl.name + " (복사)")
                sl.songs.forEach { copy.songs.add(it.copy(newIdentity = true)) }
                data.setlists.add(data.setlists.indexOf(sl) + 1, copy)
                repo.save(); showToast("복제했습니다")
            }
            2 -> {
                if (data.setlists.size <= 1) { showToast("마지막 세트리스트는 삭제할 수 없습니다"); return@setItems }
                if (engine.isPlaying) { showToast("정지 후 삭제하세요"); return@setItems }
                confirm("세트리스트 삭제", "'${sl.name}'과(와) 그 안의 ${sl.songs.size}곡을 삭제할까요?", "삭제") {
                    data.setlists.remove(sl)
                    data.currentSetlistId = data.setlists.first().id
                    data.currentIndex = 0
                    repo.save(); refreshAll()
                }
            }
            3 -> exportFile(repo.exportJson(listOf(sl)), safeName(sl.name) + ".json")
            4 -> exportFile(repo.exportJson(data.setlists), "StageClick_백업.json")
            5 -> importFile()
        }
    }.setNegativeButton("닫기", null).show()
}

private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { "setlist" }

@Suppress("DEPRECATION")
private fun MainActivity.exportFile(json: String, fileName: String) {
    pendingExport = json
    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/json").putExtra(Intent.EXTRA_TITLE, fileName)
    try { startActivityForResult(i, MainActivity.REQ_EXPORT) } catch (e: Exception) { showToast("파일 저장 화면을 열 수 없습니다") }
}

@Suppress("DEPRECATION")
private fun MainActivity.importFile() {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
    try { startActivityForResult(i, MainActivity.REQ_IMPORT) } catch (e: Exception) { showToast("파일 선택 화면을 열 수 없습니다") }
}

// ------------------------------------------------------------------ 설정

private fun profileHelp(p: OutputProfile) = when (p) {
    OutputProfile.WIRED ->
        "휴대폰 → 케이블 → 이어폰 / 개인 모니터 앰프.\n" +
            "버퍼를 최소(오디오 버스트 2개)로 잡아 지연이 가장 짧습니다. 무대에서 가장 확실한 방식입니다."
    OutputProfile.WIRELESS ->
        "휴대폰 → 케이블 → 무선 IEM 송신기 → 벨트팩 수신기 → 인이어.\n" +
            "휴대폰 쪽은 유선과 똑같이 최소 지연으로 내보냅니다. 송신기 자체 지연(아날로그 FM ≈ 0 ms, 디지털 보통 2~6 ms)은 " +
            "아래 '화면 보정'에 넣어 박 깜빡임을 귀에 들리는 소리에 맞춥니다.\n" +
            "아날로그 송신기는 컴팬더 때문에 아주 짧은 클릭이 작게 들릴 수 있으니 '클릭 길이: 길게'를 권장합니다."
    OutputProfile.BLUETOOTH ->
        "블루투스는 구조적으로 약 100~300 ms의 지연과 순간 끊김 위험이 있어 공연용으로는 권장하지 않습니다.\n" +
            "이 모드는 ① 버퍼를 넉넉하게 잡아 끊김을 막고 ② 스트림을 계속 열어 두어 첫 박이 잘리지 않게 하며 " +
            "③ 탭 보정으로 화면을 실제 소리에 맞춥니다. 템포 자체는 정확하지만, 시작 버튼과 소리 사이에 지연이 있습니다."
}

private fun fmtMs(v: Int) = if (v > 0) "+$v ms" else "$v ms"

internal fun MainActivity.showSettings() {
    val s = settings
    val f = form()
    val ui = Handler(Looper.getMainLooper())

    // ---- 1. 출력 방식 ----
    section(f, "인이어 연결 방식")
    val rg = RadioGroup(this)
    val radios = OutputProfile.values().map { p ->
        RadioButton(this).apply { id = View.generateViewId(); text = p.label; textSize = 16f; tag = p }
    }
    radios.forEach { rg.addView(it) }
    rg.check(radios[s.profile.ordinal].id)
    add(f, rg, 0)
    val help = caption(profileHelp(s.profile))
    add(f, help, 4)
    check(f, "출력 장치에 따라 자동 전환 (블루투스 ↔ 유선)", s.autoRoute) { s.autoRoute = it }

    val (offLabel, offSeek) = seek(f, Settings.OFFSET_MIN, Settings.OFFSET_MAX, s.offsetFor(s.profile),
        { "화면 깜빡임 보정 (${s.profile.short}): ${fmtMs(it)}" }) { v -> s.setOffset(s.profile, v) }
    add(f, caption("박 표시를 귀에 들리는 클릭과 맞춥니다. 소리 타이밍 자체에는 영향을 주지 않습니다."), 2)
    val calBtn = PadButton(this).apply {
        text = "🎯 탭으로 자동 보정"; textSize = 16f
        background = pressable(C.SURFACE2, C.PRESSED, dp(12).toFloat(), C.ACCENT, dp(2))
        setTextColor(C.ACCENT)
    }
    f.addView(calBtn, LinearLayout.LayoutParams(MATCH, dp(52)).also { it.topMargin = dp(8) })

    rg.setOnCheckedChangeListener { _, id ->
        val p = radios.first { it.id == id }.tag as OutputProfile
        s.profile = p
        if (p != OutputProfile.BLUETOOTH) s.lastWiredProfile = p
        if (p == OutputProfile.WIRELESS && s.clickLength == 1) showToast("무선 송신기에는 '클릭 길이: 길게'도 시험해 보세요")
        app.applySettings()
        help.text = profileHelp(p)
        offSeek.progress = s.offsetFor(p) - Settings.OFFSET_MIN
        offLabel.text = "화면 깜빡임 보정 (${p.short}): ${fmtMs(s.offsetFor(p))}"
        refreshRouteChip()
    }

    // ---- 2. 소리 ----
    section(f, "클릭 소리")
    labeled(f, "소리 종류", spinner(ClickSounds.NAMES, s.sound) { s.sound = it; app.applySettings() })
    val preview = PadButton(this).apply {
        text = "🔈 미리 듣기"; textSize = 15f
        background = pressable(C.SURFACE2, C.PRESSED, dp(12).toFloat())
        onPress = {
            engine.preview(LEVEL_STRONG)
            ui.postDelayed({ engine.preview(LEVEL_NORMAL) }, 400)
            ui.postDelayed({ engine.preview(LEVEL_NORMAL) }, 800)
        }
    }
    f.addView(preview, LinearLayout.LayoutParams(MATCH, dp(48)).also { it.topMargin = dp(8) })
    labeled(f, "클릭 길이", spinner(ClickSounds.LENGTHS, s.clickLength) { s.clickLength = it; app.applySettings() })
    seek(f, 0, 100, (s.volume * 100).roundToInt(), { "앱 볼륨: $it%" }) { s.volume = it / 100f; app.applySettings() }
    seek(f, 0, 12, s.boostDb, { "부스트: +$it dB (찌그러짐 없이 체감 음량 ↑)" }) { s.boostDb = it; app.applySettings() }
    seek(f, 0, 100, (s.subVolume * 100).roundToInt(), { "분할음 볼륨: $it%" }) { s.subVolume = it / 100f; app.applySettings() }
    labeled(f, "출력 채널", spinner(arrayOf("양쪽 (L+R)", "왼쪽만 (L)", "오른쪽만 (R)"), s.channel) { s.channel = it; app.applySettings() })

    // ---- 3. 공연 ----
    section(f, "공연")
    check(f, "박마다 화면 깜빡임", s.flash) { s.flash = it }
    check(f, "이어폰·송신기 케이블이 빠지면 자동 정지 (스피커로 새는 것 방지)", s.stopOnUnplug) { s.stopOnUnplug = it }
    check(f, "정지하면 다음 곡을 자동으로 선택", s.autoNext) { s.autoNext = it }
    labeled(f, "화면 방향", spinner(arrayOf("자동 회전", "세로 고정", "가로 고정"), s.orientation) {
        if (it != s.orientation) { s.orientation = it; applyOrientation() }
    })

    // ---- 4. 상태 ----
    section(f, "오디오 상태")
    val stats = caption("", C.TEXT, 13f)
    add(f, stats, 2)
    val statsTask = object : Runnable {
        override fun run() {
            val lat = engine.measuredLatencyMs()
            val bufMs = engine.actualBufferFrames * 1000.0 / engine.sampleRate
            stats.text = "출력 장치: ${route?.label ?: "-"}   ·   모드: ${s.profile.short}\n" +
                "샘플레이트 ${engine.sampleRate} Hz · 버스트 ${engine.burst} 프레임\n" +
                "버퍼: ${engine.actualBufferFrames} 프레임 (${String.format("%.1f", bufMs)} ms)\n" +
                "저지연(FAST) 경로: ${if (engine.lowLatencyPath) "사용 중" else "이 기기에서 미지원"}\n" +
                "측정된 출력 지연: ${lat?.let { String.format("%.0f ms", it) } ?: "측정 중…"}\n" +
                "끊김 감지 → 버퍼 자동 증가: ${engine.underruns}회"
            ui.postDelayed(this, 500)
        }
    }

    // ---- 5. 체크리스트 ----
    section(f, "공연 전 체크리스트")
    add(f, caption(
        "• 휴대폰 '방해 금지' 켜기 — 알림음이 인이어로 들어가지 않게\n" +
            "• 설정 › 소리 › 터치음·키보드음 끄기\n" +
            "• 휴대폰 미디어 볼륨은 최대 근처, 실제 크기는 송신기/벨트팩/앰프에서 조절\n" +
            "• 절전 모드 끄기, 이 앱을 배터리 최적화에서 제외\n" +
            "• 무대에선 🔒 잠금 — START/STOP만 동작, 자물쇠 길게 눌러 해제\n" +
            "• 블루투스 페달/키보드: Space·Enter = 시작/정지, ←→ · PageUp/Down = 이전/다음 곡",
        C.DIM, 13f), 2)

    val dialog = builder().setTitle("설정").setView(scroll(f)).setPositiveButton("닫기", null).create()
    dialog.setOnDismissListener {
        ui.removeCallbacksAndMessages(null)
        repo.saveNow()
        refreshRouteChip()
    }
    calBtn.onPress = {
        dialog.dismiss()
        showCalibration()
    }
    dialog.show()
    statsTask.run()
}

// ------------------------------------------------------------------ 탭 보정

internal fun MainActivity.showCalibration() {
    if (engine.isPlaying) { showToast("정지한 뒤 보정하세요"); return }
    val s = settings
    val profile = s.profile
    val results = ArrayList<Double>()
    val f = form()
    add(f, caption("인이어로 들리는 클릭에 맞춰 아래 패드를 탭하세요.\n8번 이상 탭하면 정확해집니다. (60 BPM)", C.TEXT, 15f), 4)
    val result = caption("측정값: -", C.ACCENT, 22f).apply { gravity = Gravity.CENTER }
    add(f, result, 14)
    val padBtn = PadButton(this).apply {
        text = "TAP"; textSize = 40f
        background = pressable(C.SURFACE2, C.PRESSED, dp(20).toFloat(), C.ACCENT, dp(3))
        setTextColor(C.ACCENT)
    }
    f.addView(padBtn, LinearLayout.LayoutParams(MATCH, dp(200)).also { it.topMargin = dp(14) })

    fun median(): Int? {
        if (results.size < 4) return null
        val sorted = results.sorted()
        return sorted[sorted.size / 2].roundToInt()
    }

    padBtn.onPress = {
        val pf = engine.presentedFrameAt(padBtn.lastDownNanos)
        if (pf != null && recentTickCount > 0) {
            var best = Long.MAX_VALUE
            val n = minOf(recentTickCount, recentTickFrames.size)
            for (i in 0 until n) {
                val d = pf - recentTickFrames[i]
                if (abs(d) < abs(best)) best = d
            }
            val ms = best * 1000.0 / engine.sampleRate
            if (abs(ms) < 450) results.add(ms)
            val m = median()
            result.text = if (m == null) "탭 ${results.size}회… (4회 이상 필요)" else "측정값: ${fmtMs(m)}   (탭 ${results.size}회)"
        }
    }

    engine.start(ClickPattern(60.0, 4, 1, intArrayOf(LEVEL_STRONG, LEVEL_NORMAL, LEVEL_NORMAL, LEVEL_NORMAL), 0, false))
    builder().setTitle("화면 보정 — ${profile.short}").setView(f)
        .setPositiveButton("적용") { _, _ ->
            val m = median()
            if (m != null) {
                s.setOffset(profile, m)
                repo.saveNow()
                showToast("${profile.short} 보정값 ${fmtMs(s.offsetFor(profile))} 적용")
            } else showToast("탭이 부족해 적용하지 않았습니다")
        }
        .setNegativeButton("취소", null)
        .setOnDismissListener { engine.stop() }
        .show()
}
