/*
 * OmniTV Streaming Playlist Parser
 */
package app.omnitv.data.parser

import app.omnitv.data.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.util.regex.Pattern

/**
 * Fast streaming M3U/M3U8 playlist parser for low-resource TV devices.
 * Groups quality variants of the same channel and selects highest quality stream.
 */
object M3uParser {

    private val ATTRIBUTE_REGEX = Regex("""([\w-]+)="([^"]*)"""")

    data class Result(
        val channels: List<Channel>,
        val declaredEpgUrl: String?,
        val skippedEntries: Int,
    )

    fun parse(input: InputStream, sourceId: Long): Result =
        input.bufferedReader().use { parse(it, sourceId) }

    // Cache last parsed playlist to avoid full reparsing on refresh
    private var _cached: Pair<String, Result>? = null
    // Persistent parsed-channel cache (light, no heavy dep; uses file-backed storage)
    private val persistentCacheFile = java.io.File("/tmp/omnitv_parsed_cache.json") // replaced with context-based in real; using file for demo
    fun saveParsedCache(channels: List<app.omnitv.data.model.Channel>, sourceId: Long) {
        try { persistentCacheFile.writeText(channels.joinToString("\n") { it.streamUrl + "|" + it.name }) } catch (_: Exception) {}
    }
    fun loadParsedCache(sourceId: Long): List<app.omnitv.data.model.Channel>? {
        return try {
            if (!persistentCacheFile.exists()) return null
            val text = persistentCacheFile.readText()
            text.lines().map { line ->
                val parts = line.split("|")
                app.omnitv.data.model.Channel(name = parts.getOrNull(1) ?: "", streamUrl = parts.getOrNull(0) ?: "")
            }.filter { it.streamUrl.isNotBlank() }
        } catch (_: Exception) { null }
    }

    fun parseCached(text: String, sourceId: Long): Result {
        if (_cached != null && _cached!!.first == text) return _cached!!.second
        val r = parse(text, sourceId)
        _cached = text to r
        return r
    }

    fun parse(text: String, sourceId: Long): Result =
        parse(text.reader().buffered(), sourceId)

    data class QualityInfo(val rank: Int, val label: String, val cleanName: String, val groupKey: String)

    private val QUALITY_REGEX = Regex("""(?i)\b(4k|uhd|fhd|hd|sd|1080p|720p|576p|480p|hevc|h265|raw|fps)\b""")
    private val BRACKETS_REGEX = Regex("""[\[\(\]\)]""")
    private val SPACES_REGEX = Regex("""\s+""")
    private val ALPHANUM_REGEX = Regex("""[^a-z0-9]""")

    fun parseQualityAndClean(rawName: String): QualityInfo {
        var name = rawName.trim()
        var rank = 100 // default SD / base
        var label = ""

        val upper = name.uppercase()
        when {
            upper.contains("4K") || upper.contains("UHD") -> {
                rank = 500
                label = "4K"
            }
            upper.contains("FHD") || upper.contains("1080P") -> {
                rank = 400
                label = "FHD"
            }
            upper.contains("HD") || upper.contains("720P") -> {
                rank = 300
                label = "HD"
            }
            upper.contains("SD") || upper.contains("576P") || upper.contains("480P") -> {
                rank = 200
                label = "SD"
            }
        }

        // Clean out prefix tags like "US | " or "UK: "
        if (name.contains("|")) {
            name = name.substringAfterLast("|").trim()
        } else if (name.contains(" - ")) {
            name = name.substringAfterLast(" - ").trim()
        } else if (name.contains(":")) {
            name = name.substringAfterLast(":").trim()
        }

        // Strip quality indicators from channel name for display and grouping
        val cleanName = name
            .replace(QUALITY_REGEX, "")
            .replace(BRACKETS_REGEX, "")
            .trim()
            .replace(SPACES_REGEX, " ")

        val groupKey = cleanName.lowercase().replace(ALPHANUM_REGEX, "")

        return QualityInfo(
            rank = rank,
            label = label,
            cleanName = if (cleanName.isNotBlank()) cleanName else rawName,
            groupKey = if (groupKey.isNotBlank()) groupKey else rawName.lowercase(),
        )
    }

    fun parse(reader: BufferedReader, sourceId: Long): Result {
        val rawChannels = ArrayList<Channel>()
        var declaredEpgUrl: String? = null
        var skipped = 0

        var pendingName: String? = null
        var pendingAttributes: Map<String, String> = emptyMap()
        var pendingNumber: Int? = null
        var index = 0

        reader.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit

                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attributes = parseAttributes(line)
                    declaredEpgUrl = attributes["url-tvg"]
                        ?: attributes["x-tvg-url"]
                        ?: declaredEpgUrl
                }

                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttributes = parseAttributes(line)
                    pendingName = displayNameOf(line, pendingAttributes)
                    pendingNumber = pendingAttributes["tvg-chno"]?.toIntOrNull()
                }

                line.startsWith("#") -> Unit

                else -> {
                    val rawName = pendingName
                    if (rawName.isNullOrBlank()) {
                        skipped++
                    } else {
                        val attributes = pendingAttributes
                        val q = parseQualityAndClean(rawName)
                        val streamId = attributes["tvg-id"]?.takeIf { it.isNotBlank() } ?: q.groupKey

                        rawChannels += Channel(
                            sourceId = sourceId,
                            streamId = streamId,
                            name = rawName,
                            displayName = q.cleanName,
                            groupKey = q.groupKey,
                            qualityRank = q.rank,
                            qualityLabel = q.label,
                            categoryId = attributes["group-title"]?.takeIf { it.isNotBlank() } ?: "Live TV",
                            logoUrl = (attributes["tvg-logo"] ?: attributes["logo"])?.takeIf { it.isNotBlank() },
                            epgChannelId = attributes["tvg-id"]?.takeIf { it.isNotBlank() },
                            number = pendingNumber,
                            streamUrl = line,
                            sortIndex = index++,
                        )
                    }
                    pendingName = null
                    pendingAttributes = emptyMap()
                    pendingNumber = null
                }
            }
        }

        // Group channels by groupKey (or streamId) and retain quality variants as fallback streams
        val groupedVariants = LinkedHashMap<String, ArrayList<Channel>>()
        for (ch in rawChannels) {
            val key = if (ch.groupKey.isNotBlank()) ch.groupKey else ch.streamId
            groupedVariants.getOrPut(key) { ArrayList() }.add(ch)
        }

        val finalizedChannels = ArrayList<Channel>(groupedVariants.size)
        for (variants in groupedVariants.values) {
            if (finalizedChannels.size >= 12000) break // Exact 12,000 channel cap for low-end device stability
            // Sort descending: highest quality rank first
            val sorted = variants.sortedByDescending { it.qualityRank }
            val primary = sorted.first()
            val distinctStreams = sorted.map { it.streamUrl }.distinct()
            val alternatives = distinctStreams.filter { it != primary.streamUrl }
            finalizedChannels.add(primary.copy(alternativeStreamUrls = alternatives))
        }

        return Result(finalizedChannels, declaredEpgUrl, skipped)
    }

    private fun displayNameOf(line: String, attributes: Map<String, String>): String? {
        val afterComma = line.substringAfterLast(',', missingDelimiterValue = "").trim()
        return afterComma.takeIf { it.isNotBlank() }
            ?: attributes["tvg-name"]?.takeIf { it.isNotBlank() }
    }

    private fun parseAttributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].lowercase() to match.groupValues[2]
        }
}
