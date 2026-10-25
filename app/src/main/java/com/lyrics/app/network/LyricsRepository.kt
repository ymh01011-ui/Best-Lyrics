package com.lyrics.app.network

import com.lyrics.app.model.LyricsResult
import com.lyrics.app.model.LyricsType
import com.lyrics.app.model.SongInfo
import com.lyrics.app.utils.LyricsConverter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class SearchResult(
    val title: String,
    val artist: String,
    val album: String,
    val duration: Int,
    val artworkUrl: String,
    val trackId: String
)

object LyricsRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) resp.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanTitle(title: String): String {
        return title.replace(
            Regex("""\s*\((?i:feat\.?|ft\.?|with|from)[^)]*\)"""), ""
        ).trim()
    }

    private fun cleanAlbum(album: String?): String? {
        if (album == null) return null
        return album.replace(Regex("""\s*\([^)]*\)"""), "").trim()
    }

    private fun cleanTitleFull(title: String): String {
        var cleaned = title.trim()
        val patterns = listOf(
            Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
            Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
            Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    private fun cleanArtistPrimary(artist: String): String {
        val separators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")
        var cleaned = artist.trim()
        for (sep in separators) {
            if (cleaned.contains(sep, ignoreCase = true)) {
                cleaned = cleaned.split(sep, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    // =========================
    // iTunes Search
    // =========================
    fun searchSongs(query: String, limit: Int = 10): List<SearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val body = get("https://itunes.apple.com/search?term=$encoded&entity=song&limit=$limit") ?: return emptyList()
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<SearchResult>()

        for (i in 0 until results.length()) {
            val track = results.getJSONObject(i)
            if (track.optString("kind") != "song") continue
            val title = cleanTitle(track.getString("trackName"))
            val artist = track.getString("artistName")
            var album = cleanAlbum(track.optString("collectionName", "")) ?: title
            if (album.contains("single", ignoreCase = true)) album = title
            val duration = (track.getLong("trackTimeMillis") / 1000).toInt()
            val artwork = track.optString("artworkUrl100", "").replace("100x100", "300x300")
            val trackId = track.getLong("trackId").toString()
            list.add(SearchResult(title, artist, album, duration, artwork, trackId))
        }
        return list
    }

    // =========================
    // Apple Music Top 100 Global Playlist
    // =========================
    fun getTopCharts(limit: Int = 10): List<SearchResult> {
        val playlistId = "pl.d25f5d1181894928af76c85c967f8f31"
        val body = get("https://itunes.apple.com/lookup?id=$playlistId&entity=song&limit=$limit")
            ?: return getFallbackCharts(limit)

        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return getFallbackCharts(limit)
        val list = mutableListOf<SearchResult>()

        for (i in 0 until results.length()) {
            val track = results.getJSONObject(i)
            if (track.optString("kind") != "song") continue
            val title = cleanTitle(track.getString("trackName"))
            val artist = track.getString("artistName")
            var album = cleanAlbum(track.optString("collectionName", "")) ?: title
            if (album.contains("single", ignoreCase = true)) album = title
            val duration = (track.getLong("trackTimeMillis") / 1000).toInt()
            val artwork = track.optString("artworkUrl100", "").replace("100x100", "300x300")
            val trackId = track.getLong("trackId").toString()
            list.add(SearchResult(title, artist, album, duration, artwork, trackId))
            if (list.size >= limit) break
        }

        return if (list.isNotEmpty()) list else getFallbackCharts(limit)
    }

    private fun getFallbackCharts(limit: Int): List<SearchResult> {
        val body = get("https://itunes.apple.com/search?term=top+hits+2025&entity=song&limit=$limit")
            ?: return emptyList()
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return emptyList()
        val list = mutableListOf<SearchResult>()

        for (i in 0 until results.length()) {
            val track = results.getJSONObject(i)
            if (track.optString("kind") != "song") continue
            val title = cleanTitle(track.getString("trackName"))
            val artist = track.getString("artistName")
            var album = cleanAlbum(track.optString("collectionName", "")) ?: title
            if (album.contains("single", ignoreCase = true)) album = title
            val duration = (track.getLong("trackTimeMillis") / 1000).toInt()
            val artwork = track.optString("artworkUrl100", "").replace("100x100", "300x300")
            val trackId = track.getLong("trackId").toString()
            list.add(SearchResult(title, artist, album, duration, artwork, trackId))
        }
        return list
    }

    fun toSongInfo(result: SearchResult) = SongInfo(
    title = result.title,
    artist = result.artist,
    album = result.album,
    duration = result.duration,
    artworkUrl = result.artworkUrl,
    trackId = result.trackId
)

    fun getSongData(trackId: String): SongInfo? {
    val body = get("https://itunes.apple.com/lookup?id=$trackId") ?: return null
    val json = JSONObject(body)
    if (json.getInt("resultCount") == 0) return null

    val results = json.getJSONArray("results")
    var track: JSONObject? = null
    for (i in 0 until results.length()) {
        val item = results.getJSONObject(i)
        if (item.optString("kind") == "song") { track = item; break }
    }
    track ?: return null

    val title = cleanTitle(track.getString("trackName"))
    val artist = track.getString("artistName")
    var album = cleanAlbum(track.optString("collectionName", "")) ?: title
    if (album.contains("single", ignoreCase = true)) album = title
    val duration = (track.getLong("trackTimeMillis") / 1000).toInt()

    // ✅ استخدم الـ trackId اللي جاي من الـ URL مش اللي iTunes بيرجعه
    return SongInfo(title, artist, album, duration, trackId = trackId)
}

    fun searchSong(title: String, artist: String): SongInfo? {
        val results = searchSongs("$title $artist", 1)
        return results.firstOrNull()?.let { toSongInfo(it) }
    }

    fun extractTrackId(url: String): String? {
        val m1 = Regex("""[?&]i=(\d+)""").find(url)
        if (m1 != null) return m1.groupValues[1]
        val m2 = Regex("""/(\d{6,})""").find(url)
        return m2?.groupValues?.get(1)
    }

    fun extractFromPage(url: String): Pair<String, String>? {
        val body = get(url) ?: return null
        val m = Regex("<title>(.*?)</title>").find(body) ?: return null
        var title = m.groupValues[1]
            .replace(" - YouTube Music", "")
            .replace(" - YouTube", "")
            .replace(" | Spotify", "")
        val parts = title.split(" - ")
        return if (parts.size >= 2) Pair(parts[1].trim(), parts[0].trim())
        else Pair(title.trim(), "")
    }

    fun parseManual(text: String): SongInfo? {
        if ("🎵" in text && "👤" in text) {
            var title: String? = null; var artist: String? = null
            var album: String? = null; var duration: Int? = null
            text.lines().forEach { line ->
                val l = line.trim()
                when {
                    l.startsWith("🎵") -> title = l.removePrefix("🎵").trim()
                    l.startsWith("👤") -> artist = l.removePrefix("👤").trim()
                    l.startsWith("💿") -> album = l.removePrefix("💿").trim()
                    l.startsWith("⏱") -> duration = l.removePrefix("⏱").replace("s", "").trim().toIntOrNull()
                }
            }
            if (title != null && artist != null && album != null && duration != null)
                return SongInfo(title!!, artist!!, album!!, duration!!)
        }
        if ("|" in text) {
            val parts = text.split("|").map { it.trim() }
            if (parts.size == 4) {
                val dur = parts[3].toIntOrNull() ?: return null
                return SongInfo(parts[0], parts[1], parts[2], dur)
            }
        }
        val m = Regex("""(.+?)\s*-\s*(.+)""").find(text)
        if (m != null) return searchSong(m.groupValues[1].trim(), m.groupValues[2].trim())
        return null
    }

    // =========================
    // 1. Apple Music (boidu)
    // =========================
    fun fetchAppleLyrics(song: SongInfo): LyricsResult? {
        val url = "https://lyrics-api.boidu.dev/getLyrics" +
                "?s=${encode(song.title)}&a=${encode(song.artist)}&al=${encode(song.album)}&d=${song.duration}"
        val body = get(url) ?: return null
        if (body.isBlank()) return null
        val json = try { JSONObject(body) } catch (e: Exception) { return null }

        val ttml = json.optString("ttml", "")
        if (ttml.isNotBlank()) {
            val isLine = ttml.contains("itunes:timing=\"Line\"")
            val converted = LyricsConverter.convertTtml(ttml)
            if (converted.isNotBlank())
                return LyricsResult("Apple Music", converted, if (isLine) LyricsType.LINE else LyricsType.WORD)
        }
        val plainLyrics = json.optString("lyrics", "")
        if (plainLyrics.isNotBlank())
            return LyricsResult("Apple Music", plainLyrics, LyricsType.LINE)
        return null
    }

    // =========================
    // 2. Apple Music 2 (Paxsenix)
    // =========================
    fun fetchPaxsenix(song: SongInfo): LyricsResult? {
        // Strategy 1: استخدم الـ trackId مباشرة لو موجود
        if (song.trackId.isNotBlank()) {
            val result = fetchPaxsenixById(song.trackId)
            if (result != null) return result
        }

        // Strategy 2: ابحث بـ title + artist
        val cleanedTitle = cleanTitleFull(song.title)
        val cleanedArtist = cleanArtistPrimary(song.artist)

        val queries = listOf(
            "$cleanedTitle $cleanedArtist",
            cleanedTitle,
            "${song.title} ${song.artist}"
        )

        for (query in queries) {
            val encoded = encode(query)
            val searchBody = get("https://lyrics.paxsenix.org/apple-music/search?q=$encoded") ?: continue
            if (searchBody.isBlank()) continue

            try {
                val arr = JSONArray(searchBody)
                if (arr.length() == 0) continue

                // اختار أقرب نتيجة بالـ duration
                var bestId: String? = null
                var bestDiff = Int.MAX_VALUE

                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val itemDuration = item.optInt("duration", 0)
                    val diff = abs(itemDuration - song.duration)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestId = item.optString("id", null)
                    }
                }

                if (bestId != null && bestDiff <= 5) {
                    val result = fetchPaxsenixById(bestId)
                    if (result != null) return result
                }
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    private fun fetchPaxsenixById(id: String): LyricsResult? {
        try {
            val body = get("https://lyrics.paxsenix.org/apple-music/lyrics?id=$id") ?: return null
            if (body.isBlank()) return null

            val json = JSONObject(body)

            // Priority 1: ttmlContent → convertTtml
            val ttml = json.optString("ttmlContent", "")
            if (ttml.isNotBlank()) {
                val isLine = ttml.contains("itunes:timing=\"Line\"")
                val converted = LyricsConverter.convertTtml(ttml)
                if (converted.isNotBlank())
                    return LyricsResult("Apple Music 2", converted, if (isLine) LyricsType.LINE else LyricsType.WORD)
            }

            // Priority 2: elrcMultiPerson
            val elrcMulti = json.optString("elrcMultiPerson", "")
            if (elrcMulti.isNotBlank())
                return LyricsResult("Apple Music 2", elrcMulti, LyricsType.LINE)

            // Priority 3: elrc
            val elrc = json.optString("elrc", "")
            if (elrc.isNotBlank())
                return LyricsResult("Apple Music 2", elrc, LyricsType.LINE)

            // Priority 4: plain
            val plain = json.optString("plain", "")
            if (plain.isNotBlank())
                return LyricsResult("Apple Music 2", plain, LyricsType.LINE)

            return null
        } catch (e: Exception) {
            return null
        }
    }

    // =========================
    // 3. LyricsPlus
    // =========================
    fun fetchLyricsPlus(song: SongInfo): LyricsResult? {
        val bases = listOf(
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus-seven.vercel.app"
        )
        for (base in bases) {
            try {
                val url = "$base/v2/lyrics/get?title=${encode(song.title)}&artist=${encode(song.artist)}&duration=${song.duration}"
                val body = get(url) ?: continue
                if (body.isBlank()) continue
                val json = JSONObject(body)
                val lyricsArr = json.optJSONArray("lyrics")
                if (lyricsArr == null || lyricsArr.length() == 0) continue
                val isLine = json.optString("type", "Word").equals("Line", ignoreCase = true)
                val converted = if (isLine) LyricsConverter.convertJsonLine(json) else LyricsConverter.convertJsonLyrics(json)
                if (converted.isNotBlank())
                    return LyricsResult("LyricsPlus", converted, if (isLine) LyricsType.LINE else LyricsType.WORD)
            } catch (e: Exception) { continue }
        }
        return null
    }

    // =========================
    // 4. LrcLib
    // =========================
    fun fetchLrcLib(song: SongInfo): LyricsResult? {
        val cleanedTitle = song.title
            .replace(Regex("""\s*\((?i:feat\.?|ft\.?|with|from)[^)]*\)"""), "")
            .replace(Regex("""\s*\[(?i:feat\.?|ft\.?|with|from)[^\]]*\]"""), "")
            .trim()

        val cleanedArtist = song.artist.split(
            " & ", " and ", ", ", " x ", " feat. ", " feat ", " ft. ", " ft ", " featuring "
        ).first().trim()

        var result = queryLrcLib(cleanedTitle, cleanedArtist, song.album, song.duration)
        if (result != null) return result

        result = queryLrcLib(cleanedTitle, cleanedArtist, null, song.duration)
        if (result != null) return result

        result = queryLrcLib(cleanedTitle, null, null, song.duration)
        if (result != null) return result

        result = queryLrcLibQ("$cleanedArtist $cleanedTitle", song.duration)
        return result
    }

    private fun queryLrcLib(title: String, artist: String?, album: String?, duration: Int): LyricsResult? {
        try {
            var url = "https://lrclib.net/api/search?track_name=${encode(title)}"
            if (artist != null) url += "&artist_name=${encode(artist)}"
            if (album != null) url += "&album_name=${encode(album)}"
            val body = get(url) ?: return null
            if (body.isBlank()) return null
            return pickBestLrcLibResult(JSONArray(body), duration)
        } catch (e: Exception) { return null }
    }

    private fun queryLrcLibQ(query: String, duration: Int): LyricsResult? {
        try {
            val body = get("https://lrclib.net/api/search?q=${encode(query)}") ?: return null
            if (body.isBlank()) return null
            return pickBestLrcLibResult(JSONArray(body), duration)
        } catch (e: Exception) { return null }
    }

    private fun pickBestLrcLibResult(arr: JSONArray, duration: Int): LyricsResult? {
        if (arr.length() == 0) return null
        val withLyrics = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val track = arr.getJSONObject(i)
            val hasSynced = !track.optString("syncedLyrics", "").isNullOrBlank()
            val hasPlain = !track.optString("plainLyrics", "").isNullOrBlank()
            if (hasSynced || hasPlain) withLyrics.add(track)
        }
        if (withLyrics.isEmpty()) return null
        val best = withLyrics
            .filter { abs(it.optInt("duration", 0) - duration) <= 5 }
            .minByOrNull { abs(it.optInt("duration", 0) - duration) }
            ?: withLyrics.minByOrNull { abs(it.optInt("duration", 0) - duration) }
            ?: return null
        val syncedLyrics = best.optString("syncedLyrics", "")
        val plainLyrics = best.optString("plainLyrics", "")
        return when {
            !syncedLyrics.isNullOrBlank() -> LyricsResult("LrcLib", syncedLyrics, LyricsType.LINE)
            !plainLyrics.isNullOrBlank() -> LyricsResult("LrcLib", plainLyrics, LyricsType.LINE)
            else -> null
        }
    }

    // =========================
    // 5. Apple Music 3 (Lossless archive)
    // =========================
    fun fetchLossless(song: SongInfo): LyricsResult? {
        val title = cleanTitleFull(song.title)
        val artist = cleanArtistPrimary(song.artist)
        val searchUrl = buildString {
            append("https://lyrics-api.binimum.org/?track=").append(encode(title))
            append("&artist=").append(encode(artist))
            if (song.duration > 0) append("&duration=").append(song.duration)
        }
        val body = getLossless(searchUrl) ?: return null
        return try {
            val arr = JSONObject(body).optJSONArray("results") ?: return null
            val best = (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("lyricsUrl").startsWith("http") }
                .minByOrNull { r ->
                    val byWord = if (r.optString("timing_type") == "word") 0 else 1
                    val drift = if (song.duration > 0) abs(r.optLong("duration") - song.duration) else 0L
                    byWord * 1_000_000L + drift
                } ?: return null
            val ttml = getLossless(best.getString("lyricsUrl")) ?: return null
            val converted = LyricsConverter.convertTtml(ttml)
            if (converted.isBlank()) return null
            val isLine = ttml.contains("itunes:timing=\"Line\"")
            LyricsResult("Apple Music 3", converted, if (isLine) LyricsType.LINE else LyricsType.WORD)
        } catch (e: Exception) { null }
    }

    private fun getLossless(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/ttml+xml, application/xml, application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36")
            .header("Referer", "https://lossless.wtf/")
            .header("Origin", "https://lossless.wtf")
            .build()
        client.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.body?.string()?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) { null }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
