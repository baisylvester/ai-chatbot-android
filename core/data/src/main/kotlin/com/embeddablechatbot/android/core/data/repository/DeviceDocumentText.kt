package com.embeddablechatbot.android.core.data.repository

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Reads the text of office files on the phone, for formats the server cannot read without parsers
 * too large for its bundle: Excel (.xlsx), PowerPoint (.pptx) and legacy Word (.doc, best effort).
 *
 * Every file is untrusted: the number of zip entries, the bytes inflated and the characters returned are
 * capped, so a zip bomb or a huge sheet cannot exhaust memory. Returns null when nothing readable is found.
 */
object DeviceDocumentText {
    const val MAX_CHARS = 20_000
    private const val MAX_ENTRIES = 2_000
    private const val MAX_INFLATED = 40L * 1024 * 1024

    fun text(bytes: ByteArray): String? = runCatching {
        when {
            isZip(bytes) -> {
                val entries = unzip(bytes)
                when {
                    entries.keys.any { it.startsWith("xl/worksheets/") } -> xlsx(entries)
                    entries.keys.any { it.startsWith("ppt/slides/") } -> pptx(entries)
                    else -> null
                }
            }
            isOle(bytes) -> legacyWord(bytes)
            else -> null
        }
    }.getOrNull()?.let(::tidy)

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() && b[2] == 3.toByte() && b[3] == 4.toByte()
    private fun isOle(b: ByteArray) = b.size > 512 && b.copyOfRange(0, 8).contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))

    /** Only the XML parts needed, with total inflated bytes capped. */
    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = HashMap<String, String>()
        var inflated = 0L
        var count = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) break
                val name = entry.name
                val wanted = name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" ||
                    (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) ||
                    (name.startsWith("ppt/slides/slide") && name.endsWith(".xml"))
                if (!wanted) continue
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val read = zip.read(chunk)
                    if (read < 0) break
                    inflated += read
                    if (inflated > MAX_INFLATED) throw IllegalStateException("too large")
                    buffer.write(chunk, 0, read)
                }
                out[name] = buffer.toString(Charsets.UTF_8.name())
            }
        }
        return out
    }

    private fun number(name: String) = Regex("(\\d+)\\.xml$").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun xlsx(parts: Map<String, String>): String? {
        val shared = parts["xl/sharedStrings.xml"]?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si -> runs(si.groupValues[1], "t") }.toList()
        }.orEmpty()
        val names = parts["xl/workbook.xml"]?.let { Regex("<sheet [^>]*name=\"([^\"]*)\"").findAll(it).map { m -> unescape(m.groupValues[1]) }.toList() }.orEmpty()
        val out = StringBuilder()
        parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedBy(::number).forEachIndexed { index, sheet ->
            if (out.length >= MAX_CHARS) return@forEachIndexed
            out.append("## ").append(names.getOrNull(index) ?: "Sheet ${index + 1}").append('\n')
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(parts.getValue(sheet)).forEach { row ->
                if (out.length >= MAX_CHARS) return@forEach
                val cells = Regex("<c([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { cell ->
                    val attrs = cell.groupValues[1]; val body = cell.groupValues[2]
                    val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                    val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    when (type) {
                        "s" -> value?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "inlineStr" -> runs(body, "t")
                        else -> unescape(value.orEmpty())
                    }
                }.toList()
                if (cells.any { it.isNotBlank() }) out.append(cells.joinToString("\t").trimEnd()).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    private fun pptx(parts: Map<String, String>): String {
        val out = StringBuilder()
        parts.keys.filter { it.startsWith("ppt/slides/slide") }.sortedBy(::number).forEachIndexed { index, slide ->
            if (out.length >= MAX_CHARS) return@forEachIndexed
            out.append("## Slide ").append(index + 1).append('\n')
            Regex("<a:p>(.*?)</a:p>", RegexOption.DOT_MATCHES_ALL).findAll(parts.getValue(slide)).forEach { p ->
                val line = runs(p.groupValues[1], "a:t")
                if (line.isNotBlank()) out.append(line).append('\n')
            }
            out.append('\n')
        }
        return out.toString()
    }

    /** All text runs of [tag] inside [xml], joined. */
    private fun runs(xml: String, tag: String) =
        Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(xml).joinToString("") { unescape(it.groupValues[1]) }

    private fun unescape(s: String) = s.replace(Regex("&(#x?[0-9a-fA-F]+|lt|gt|amp|quot|apos);")) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"; "gt" -> ">"; "amp" -> "&"; "quot" -> "\""; "apos" -> "'"
            else -> (if (e.startsWith("#x")) e.drop(2).toIntOrNull(16) else e.drop(1).toIntOrNull())
                ?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) }.orEmpty()
        }
    }

    /**
     * Word 97–2003 keeps its text as runs of UTF-16 or 8-bit characters. Without a full parser, take the
     * long runs of readable text in either encoding and keep whichever finds more. Best effort only.
     */
    private fun legacyWord(bytes: ByteArray): String? {
        val wide = readableRuns(String(bytes, Charsets.UTF_16LE))
        val narrow = readableRuns(String(bytes, Charset.forName("windows-1252")))
        return listOf(wide, narrow).maxByOrNull { it.length }?.takeIf { it.length >= 40 }
    }

    private fun readableRuns(decoded: String): String {
        val text = Regex("[\\p{L}\\p{N}\\p{P}\\p{Zs}\\r\\n\\t€£$%+=<>|~^`]{20,}").findAll(decoded)
            .map { it.value }
            .filter { run -> run.count(Char::isLetter) >= run.length / 2 } // mostly words, not binary noise
            .joinToString("\n")
        return text.replace('\r', '\n')
    }

    private fun tidy(text: String) = text.replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_CHARS).ifBlank { null }
}
