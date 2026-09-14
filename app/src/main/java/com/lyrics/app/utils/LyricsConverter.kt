package com.lyrics.app.utils

import org.json.JSONObject
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

object LyricsConverter {

    private fun parseTime(t: String?): Double {
        if (t.isNullOrEmpty()) return 0.0

        if (t.endsWith("s")) {
            return t.dropLast(1).toDoubleOrNull() ?: 0.0
        }

        return if (":" in t) {
            val parts = t.split(":")
            when (parts.size) {
                3 -> parts[0].toInt() * 3600 + parts[1].toInt() * 60 + parts[2].toDouble()
                2 -> parts[0].toInt() * 60 + parts[1].toDouble()
                else -> t.toDoubleOrNull() ?: 0.0
            }
        } else {
            t.toDoubleOrNull() ?: 0.0
        }
    }

    private fun formatTime(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = sec % 60
        return "%02d:%06.3f".format(m, s)
    }

    private fun avoidDuplicateTime(lines: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return lines.map { line ->
            val match = Regex("""\[(.*?)]""").find(line)
            if (match == null) {
                line
            } else {
                var t = match.groupValues[1]
                while (used.contains(t)) {
                    val sec = parseTime(t) + 0.001
                    t = formatTime(sec)
                }
                used.add(t)
                line.replaceFirst(Regex("""\[.*?]"""), "[$t]")
            }
        }
    }

    // =========================
    // convert_ttml (Word & Line)
    // =========================
    fun convertTtml(ttml: String): String {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(InputSource(StringReader(ttml)))

        val NS_TT = "http://www.w3.org/ns/ttml"
        val NS_TTM = "http://www.w3.org/ns/ttml#metadata"

        val isLine = ttml.contains("itunes:timing=\"Line\"")
        val result = mutableListOf<String>()
        val pList = doc.getElementsByTagNameNS(NS_TT, "p")

        // Track agents across all <p> elements to assign V1/V2
        val agentMap = mutableMapOf<String, String>() // agent id -> "V1"/"V2"
        var agentCounter = 0

        fun resolveAgent(agentId: String): String {
            return agentMap.getOrPut(agentId) {
                agentCounter++
                "V$agentCounter"
            }
        }

        for (i in 0 until pList.length) {
            val p = pList.item(i) as Element
            val begin = p.getAttribute("begin")
            val pAgent = p.getAttributeNS(NS_TTM, "agent")

            if (isLine) {
                val text = p.textContent?.trim() ?: continue
                if (text.isEmpty()) continue
                val t = formatTime(parseTime(begin))
                val label = if (pAgent.isNotBlank()) "${resolveAgent(pAgent)}: " else ""
                result.add("[$t]$label$text")
            } else {
                // Word timing
                var mainLine = ""
                var bgLine = ""
                var mainTime: String? = null
                var bgTime: String? = null
                var mainAgent = ""

                val spans = p.childNodes
                for (j in 0 until spans.length) {
                    val node = spans.item(j)
                    if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                    val span = node as Element
                    if (span.localName != "span") continue

                    val role = span.getAttributeNS(NS_TTM, "role")
                    val spanAgent = span.getAttributeNS(NS_TTM, "agent")

                    if (role == "x-bg") {
                        val subSpans = span.getElementsByTagNameNS(NS_TT, "span")
                        for (k in 0 until subSpans.length) {
                            val sub = subSpans.item(k) as Element
                            val subText = sub.textContent ?: continue
                            if (subText.isEmpty()) continue
                            val b = formatTime(parseTime(sub.getAttribute("begin")))
                            val e = formatTime(parseTime(sub.getAttribute("end")))
                            if (bgTime == null) bgTime = b
                            bgLine += "<$b>$subText<$e>"
                            val tail = sub.nextSibling?.nodeValue
                            if (tail != null && tail.trim().isEmpty()) bgLine += " "
                        }
                    } else {
                        val spanText = span.textContent ?: continue
                        if (spanText.isEmpty()) continue
                        val b = formatTime(parseTime(span.getAttribute("begin")))
                        val e = formatTime(parseTime(span.getAttribute("end")))
                        if (mainTime == null) {
                            mainTime = b
                            // Resolve agent: span agent > p agent
                            val agentId = spanAgent.ifBlank { pAgent }
                            mainAgent = if (agentId.isNotBlank()) "${resolveAgent(agentId)}: " else ""
                        }
                        mainLine += "<$b>$spanText<$e>"
                        val tail = span.nextSibling?.nodeValue
                        if (tail != null && tail.trim().isEmpty()) mainLine += " "
                    }
                }

                if (mainLine.isNotEmpty() && mainTime != null)
                    result.add("[$mainTime]${mainAgent}$mainLine")
                if (bgLine.isNotEmpty() && bgTime != null)
                    result.add("[$bgTime]bg: $bgLine")
            }
        }

        return avoidDuplicateTime(result).joinToString("\n")
    }

    // =========================
    // convert_json_lyrics (Word) - LyricsPlus
    // =========================
    fun convertJsonLyrics(data: JSONObject): String {
        val lyricsList = data.optJSONArray("lyrics") ?: return ""
        val result = mutableListOf<String>()

        for (i in 0 until lyricsList.length()) {
            val line = lyricsList.getJSONObject(i)
            val syllabus = line.optJSONArray("syllabus") ?: continue
            if (syllabus.length() == 0) continue

            val isOpposite = line.optBoolean("oppositeTurn", false)
            val isBackground = line.optBoolean("background", false)

            val label = when {
                isBackground -> "bg: "
                isOpposite -> "V2: "
                else -> "V1: "
            }

            var mainLine = ""
            var bgLine = ""
            var mainStart: String? = null
            var bgStart: String? = null
            var insideBg = false

            for (j in 0 until syllabus.length()) {
                val syl = syllabus.getJSONObject(j)
                val text = syl.optString("text", "")
                val startMs = syl.optLong("time", 0)
                val durMs = syl.optLong("duration", 0)
                val start = formatTime(startMs / 1000.0)
                val end = formatTime((startMs + durMs) / 1000.0)

                val stripped = text.trimEnd(' ')
                val spaces = text.substring(stripped.length)

                if ("(" in text) insideBg = true

                if (!insideBg) {
                    if (mainStart == null) mainStart = start
                    mainLine += "<$start>$stripped<$end>$spaces"
                } else {
                    if (bgStart == null) bgStart = start
                    bgLine += "<$start>$stripped<$end>$spaces"
                }

                if (")" in text) insideBg = false
            }

            if (mainLine.isNotBlank() && mainStart != null)
                result.add("[$mainStart]$label$mainLine")
            if (bgLine.isNotBlank() && bgStart != null)
                result.add("[$bgStart]bg: $bgLine")
        }

        return avoidDuplicateTime(result).joinToString("\n")
    }

    // =========================
    // convert_json_line (Line) - LyricsPlus
    // =========================
    fun convertJsonLine(data: JSONObject): String {
        val lyricsList = data.optJSONArray("lyrics") ?: return ""
        val result = mutableListOf<String>()

        for (i in 0 until lyricsList.length()) {
            val line = lyricsList.getJSONObject(i)
            val timeMs = line.optLong("time", 0)
            val text = line.optString("text", "").trim()
            if (text.isEmpty()) continue

            val isOpposite = line.optBoolean("oppositeTurn", false)
            val isBackground = line.optBoolean("background", false)

            val label = when {
                isBackground -> "bg: "
                isOpposite -> "V2: "
                else -> "V1: "
            }

            val t = formatTime(timeMs / 1000.0)
            result.add("[$t]$label$text")
        }

        return avoidDuplicateTime(result).joinToString("\n")
    }

    // =========================
    // Karaoke 2
    // =========================
    fun toKaraoke2(lyrics: String): String {
        return lyrics.lines().joinToString("\n") { line ->
            // Extract prefix label like "V1: " or "bg: " after timestamp
            val timeMatch = Regex("""^\[.*?]""").find(line) ?: return@joinToString line
            val afterTime = line.removePrefix(timeMatch.value)
            val labelMatch = Regex("""^(V\d+|bg): """).find(afterTime)
            val label = if (labelMatch != null) labelMatch.value else ""

            val words = Regex("""<([\d:.]+)>([^<]*)<([\d:.]+)>""").findAll(line).toList()
            if (words.isEmpty()) return@joinToString line

            var result = timeMatch.value + label
            for (i in words.indices) {
                val start = words[i].groupValues[1]
                val text = words[i].groupValues[2]
                val end = words[i].groupValues[3]
                if (i == words.size - 1) {
                    result += "<$start>$text<$end>"
                } else {
                    result += "<$start>$text "
                }
            }
            result
        }
    }

    // =========================
    // Synced LRC
    // =========================
    fun toSynced(lyrics: String): String {
        return lyrics.lines().joinToString("\n") { line ->
            val timeMatch = Regex("""^\[(.*?)]""").find(line) ?: return@joinToString line
            val time = timeMatch.groupValues[1]
            val text = line.replace(Regex("""<.*?>"""), "").replace(Regex("""^\[.*?]"""), "").trim()
            "[$time]$text"
        }
    }

    // =========================
    // Plain
    // =========================
    fun toPlain(lyrics: String): String {
        return lyrics.lines().joinToString("\n") { line ->
            line.replace(Regex("""<.*?>"""), "").replace(Regex("""^\[.*?]"""), "").trim()
        }.trim()
    }
}
