package com.stageclick.metronome.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.stageclick.metronome.audio.LEVEL_NORMAL
import com.stageclick.metronome.audio.LEVEL_STRONG
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 모든 데이터는 기기 내부 저장소의 JSON 파일 하나에 저장 — 인터넷 불필요. */
class Repository(context: Context) {
    private val file = File(context.filesDir, "stageclick.json")
    private val main = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { saveNow() }

    val data: AppData = load()

    fun currentSetlist(): Setlist {
        val sl = data.setlists.firstOrNull { it.id == data.currentSetlistId } ?: data.setlists.first()
        data.currentSetlistId = sl.id
        return sl
    }

    fun currentSong(): Song? {
        val songs = currentSetlist().songs
        if (songs.isEmpty()) return null
        data.currentIndex = data.currentIndex.coerceIn(0, songs.size - 1)
        return songs[data.currentIndex]
    }

    /** 잦은 변경(템포 길게 누르기 등)은 묶어서 저장 */
    fun save() {
        main.removeCallbacks(saveTask)
        main.postDelayed(saveTask, 400)
    }

    fun saveNow() {
        main.removeCallbacks(saveTask)
        try {
            val json = JSONObject()
                .put("version", 1)
                .put("currentSetlistId", data.currentSetlistId)
                .put("currentIndex", data.currentIndex)
                .put("settings", data.settings.toJson())
                .put("setlists", JSONArray().also { a -> data.setlists.forEach { a.put(it.toJson()) } })
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString(1), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.e("StageClick", "save failed", e)
        }
    }

    private fun load(): AppData {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText(Charsets.UTF_8))
                val arr = o.optJSONArray("setlists") ?: JSONArray()
                val lists = ArrayList<Setlist>()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { lists.add(Setlist.fromJson(it)) }
                if (lists.isNotEmpty()) {
                    return AppData(lists, o.optString("currentSetlistId", lists[0].id), o.optInt("currentIndex", 0),
                        Settings.fromJson(o.optJSONObject("settings")))
                }
            }
        } catch (e: Exception) {
            Log.e("StageClick", "load failed — using defaults", e)
        }
        val demo = sampleSetlist()
        return AppData(arrayListOf(demo), demo.id, 0, Settings())
    }

    private fun sampleSetlist(): Setlist {
        val sl = Setlist(name = "예시 세트리스트")
        sl.songs.add(Song(title = "오프닝", bpm = 128.0, countInBars = 1, note = "드럼 카운트 후 전원 인"))
        sl.songs.add(Song(title = "발라드", bpm = 72.0, subdivision = 2, countInBars = 1))
        sl.songs.add(Song(title = "6/8 곡", bpm = 150.0, beatsPerBar = 6, beatUnit = 8,
            accents = intArrayOf(LEVEL_STRONG, LEVEL_NORMAL, LEVEL_NORMAL, LEVEL_STRONG, LEVEL_NORMAL, LEVEL_NORMAL)))
        sl.songs.add(Song(title = "카운트만 주는 곡", bpm = 96.0, countInBars = 1, stopAfterCountIn = true,
            note = "카운트인 1마디 후 클릭 자동 정지"))
        sl.songs.add(Song(title = "앵콜", bpm = 140.0, countInBars = 2))
        return sl
    }

    // ---------- 내보내기 / 가져오기 ----------

    fun exportJson(lists: List<Setlist>): String = JSONObject()
        .put("app", "StageClick").put("version", 1)
        .put("setlists", JSONArray().also { a -> lists.forEach { a.put(it.toJson()) } })
        .toString(1)

    /** 세트리스트 1개 또는 여러 개가 들어있는 JSON을 가져와 추가한다. 추가된 세트리스트 수를 반환. */
    fun importJson(text: String): Int {
        val trimmed = text.trim()
        val incoming = ArrayList<Setlist>()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { incoming.add(Setlist.fromJson(it)) }
        } else {
            val o = JSONObject(trimmed)
            val arr = o.optJSONArray("setlists")
            if (arr != null) {
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { incoming.add(Setlist.fromJson(it)) }
            } else if (o.has("songs")) {
                incoming.add(Setlist.fromJson(o))
            }
        }
        for (sl in incoming) {
            sl.id = newId()
            sl.songs.forEach { it.id = newId() }
            if (data.setlists.any { it.name == sl.name }) sl.name = sl.name + " (가져옴)"
            data.setlists.add(sl)
        }
        if (incoming.isNotEmpty()) saveNow()
        return incoming.size
    }
}
