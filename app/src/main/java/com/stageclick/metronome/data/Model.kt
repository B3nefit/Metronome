package com.stageclick.metronome.data

import com.stageclick.metronome.audio.ClickPattern
import com.stageclick.metronome.audio.LEVEL_NORMAL
import com.stageclick.metronome.audio.LEVEL_STRONG
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

class Song(
    var id: String = newId(),
    var title: String = "새 곡",
    var bpm: Double = 120.0,
    var beatsPerBar: Int = 4,
    var beatUnit: Int = 4,
    var subdivision: Int = 1,
    var accents: IntArray = defaultAccents(4),
    var countInBars: Int = 1,
    var stopAfterCountIn: Boolean = false,
    var note: String = "",
) {
    fun copy(newIdentity: Boolean = false) = Song(
        if (newIdentity) newId() else id, title, bpm, beatsPerBar, beatUnit, subdivision,
        accents.copyOf(), countInBars, stopAfterCountIn, note,
    )

    fun normalize() {
        bpm = Math.round(bpm.coerceIn(ClickPattern.MIN_BPM, ClickPattern.MAX_BPM) * 10) / 10.0
        beatsPerBar = beatsPerBar.coerceIn(1, 16)
        subdivision = subdivision.coerceIn(1, 4)
        countInBars = countInBars.coerceIn(0, 4)
        if (accents.size != beatsPerBar) {
            val old = accents
            accents = IntArray(beatsPerBar) { i -> if (i < old.size) old[i] else if (i == 0) LEVEL_STRONG else LEVEL_NORMAL }
        }
    }

    fun pattern() = ClickPattern(bpm, beatsPerBar, subdivision, accents.copyOf(), countInBars, stopAfterCountIn)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("bpm", bpm)
        .put("beatsPerBar", beatsPerBar).put("beatUnit", beatUnit).put("subdivision", subdivision)
        .put("accents", JSONArray().also { a -> accents.forEach { a.put(it) } })
        .put("countInBars", countInBars).put("stopAfterCountIn", stopAfterCountIn).put("note", note)

    companion object {
        fun defaultAccents(n: Int) = IntArray(n) { if (it == 0) LEVEL_STRONG else LEVEL_NORMAL }

        fun fromJson(o: JSONObject): Song {
            val bpb = o.optInt("beatsPerBar", 4)
            val acc = o.optJSONArray("accents")
            val s = Song(
                id = o.optString("id", "").ifEmpty { newId() },
                title = o.optString("title", "곡"),
                bpm = o.optDouble("bpm", 120.0).let { if (it.isNaN()) 120.0 else it },
                beatsPerBar = bpb,
                beatUnit = o.optInt("beatUnit", 4),
                subdivision = o.optInt("subdivision", 1),
                accents = if (acc != null) IntArray(acc.length()) { acc.optInt(it, LEVEL_NORMAL).coerceIn(0, 2) } else defaultAccents(bpb),
                countInBars = o.optInt("countInBars", 1),
                stopAfterCountIn = o.optBoolean("stopAfterCountIn", false),
                note = o.optString("note", ""),
            )
            s.normalize()
            return s
        }
    }
}

class Setlist(var id: String = newId(), var name: String, val songs: MutableList<Song> = ArrayList()) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name)
        .put("songs", JSONArray().also { a -> songs.forEach { a.put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Setlist {
            val sl = Setlist(o.optString("id", "").ifEmpty { newId() }, o.optString("name", "세트리스트"))
            val arr = o.optJSONArray("songs") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { sl.songs.add(Song.fromJson(it)) }
            return sl
        }
    }
}

enum class OutputProfile(val label: String, val short: String) {
    WIRED("유선 (이어폰 · IEM 직결)", "유선"),
    WIRELESS("무선 IEM 송신기", "무선 IEM"),
    BLUETOOTH("블루투스", "블루투스");

    companion object {
        fun of(name: String?) = values().firstOrNull { it.name == name } ?: WIRED
    }
}

class Settings {
    var profile = OutputProfile.WIRED
    /** 블루투스가 아닌 마지막 프로필 (자동 전환 시 복귀용) */
    var lastWiredProfile = OutputProfile.WIRED
    var autoRoute = true
    var offsetWiredMs = 0
    var offsetWirelessMs = 4
    var offsetBtMs = 0
    var sound = 0
    var clickLength = 1
    var volume = 0.9f
    var boostDb = 6
    var subVolume = 0.55f
    var channel = 0
    var flash = true
    var stopOnUnplug = true
    var autoNext = true
    /** 0 자동, 1 세로 고정, 2 가로 고정 */
    var orientation = 0

    fun offsetFor(p: OutputProfile) = when (p) {
        OutputProfile.WIRED -> offsetWiredMs
        OutputProfile.WIRELESS -> offsetWirelessMs
        OutputProfile.BLUETOOTH -> offsetBtMs
    }

    fun setOffset(p: OutputProfile, v: Int) {
        val c = v.coerceIn(OFFSET_MIN, OFFSET_MAX)
        when (p) {
            OutputProfile.WIRED -> offsetWiredMs = c
            OutputProfile.WIRELESS -> offsetWirelessMs = c
            OutputProfile.BLUETOOTH -> offsetBtMs = c
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("profile", profile.name).put("lastWiredProfile", lastWiredProfile.name).put("autoRoute", autoRoute)
        .put("offsetWiredMs", offsetWiredMs).put("offsetWirelessMs", offsetWirelessMs).put("offsetBtMs", offsetBtMs)
        .put("sound", sound).put("clickLength", clickLength).put("volume", volume.toDouble())
        .put("boostDb", boostDb).put("subVolume", subVolume.toDouble()).put("channel", channel)
        .put("flash", flash).put("stopOnUnplug", stopOnUnplug).put("autoNext", autoNext).put("orientation", orientation)

    companion object {
        const val OFFSET_MIN = -100
        const val OFFSET_MAX = 400

        fun fromJson(o: JSONObject?): Settings {
            val s = Settings()
            if (o == null) return s
            s.profile = OutputProfile.of(o.optString("profile"))
            s.lastWiredProfile = OutputProfile.of(o.optString("lastWiredProfile"))
            s.autoRoute = o.optBoolean("autoRoute", true)
            s.offsetWiredMs = o.optInt("offsetWiredMs", 0)
            s.offsetWirelessMs = o.optInt("offsetWirelessMs", 4)
            s.offsetBtMs = o.optInt("offsetBtMs", 0)
            s.sound = o.optInt("sound", 0).coerceIn(0, 4)
            s.clickLength = o.optInt("clickLength", 1).coerceIn(0, 2)
            s.volume = o.optDouble("volume", 0.9).toFloat().coerceIn(0f, 1f)
            s.boostDb = o.optInt("boostDb", 6).coerceIn(0, 12)
            s.subVolume = o.optDouble("subVolume", 0.55).toFloat().coerceIn(0f, 1f)
            s.channel = o.optInt("channel", 0).coerceIn(0, 2)
            s.flash = o.optBoolean("flash", true)
            s.stopOnUnplug = o.optBoolean("stopOnUnplug", true)
            s.autoNext = o.optBoolean("autoNext", true)
            s.orientation = o.optInt("orientation", 0).coerceIn(0, 2)
            return s
        }
    }
}

class AppData(
    val setlists: MutableList<Setlist>,
    var currentSetlistId: String,
    var currentIndex: Int,
    var settings: Settings,
)
