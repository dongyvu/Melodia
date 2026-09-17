package com.lin0721.linmusic.core.player.domain

import java.io.StringReader
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** Parses AMLL TTML into Melodia's native lyric model. */
object TtmlLyricParser {
    const val MAX_LENGTH = 2 * 1024 * 1024
    private const val TTML = "http://www.w3.org/ns/ttml"

    fun parse(xml: String): List<LyricLine> {
        if (xml.isBlank() || xml.length > MAX_LENGTH ||
            Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(xml)) return emptyList()
        return try {
            val builder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> throw SAXException("External entities are forbidden") }
            builder.setErrorHandler(object : DefaultHandler() {
                override fun error(e: org.xml.sax.SAXParseException) { throw e }
                override fun fatalError(e: org.xml.sax.SAXParseException) { throw e }
            })
            val document = builder.parse(InputSource(StringReader(xml)))
            val root = document.documentElement
            if (root.localName != "tt" || root.namespaceURI != TTML) return emptyList()
            val alignments = parseAgentAlignments(document)
            val translations = parseExternalAnnotations(document, "translation")
            val romanizations = parseExternalAnnotations(document, "transliteration")
            val lines = mutableListOf<LyricLine>()
            fun visit(node: Element, depth: Int) {
                require(depth <= 64) { "TTML nesting is too deep" }
                if (node.localName == "p" && (node.namespaceURI == TTML || node.namespaceURI.isNullOrEmpty())) {
                    parseLine(node, alignments, translations, romanizations)?.let(lines::add)
                } else node.childElements().forEach { visit(it, depth + 1) }
            }
            root.childElements().filter { it.localName == "body" }.forEach { visit(it, 0) }
            lines.sortedBy { it.timeMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseLine(
        p: Element,
        alignments: Map<String, LyricAlignment>,
        translations: Map<String, String>,
        romanizations: Map<String, String>
    ): LyricLine? {
        val start = time(p.getAttribute("begin")) ?: return null
        val end = time(p.getAttribute("end")) ?: return null
        if (end <= start) return null
        val key = p.attribute("key")
        val alignment = alignments[p.attribute("agent")] ?: LyricAlignment.START
        val background = p.childElements().firstOrNull { it.role() == "x-bg" }
            ?.let { parseBackground(it, alignment) }
        return parseTrack(
            element = p,
            start = start,
            end = end,
            alignment = alignment,
            translation = p.directAnnotation("x-translation") ?: translations[key],
            romanization = p.directAnnotation("x-roman") ?: romanizations[key],
            backgroundLine = background
        )
    }

    private fun parseBackground(element: Element, alignment: LyricAlignment): LyricLine? {
        val timedChildren = element.childElements().filter { it.localName == "span" && it.role().isNullOrEmpty() }
        val start = time(element.getAttribute("begin"))
            ?: timedChildren.firstNotNullOfOrNull { time(it.getAttribute("begin")) }
            ?: return null
        val end = time(element.getAttribute("end"))
            ?: timedChildren.asReversed().firstNotNullOfOrNull { time(it.getAttribute("end")) }
            ?: return null
        if (end <= start) return null
        return parseTrack(
            element = element,
            start = start,
            end = end,
            alignment = alignment,
            translation = element.directAnnotation("x-translation"),
            romanization = element.directAnnotation("x-roman")
        )?.let(::stripBackgroundBrackets)
    }

    // AMLL 背景和声习惯用半角/全角圆括号包裹（如 "(Yeah)"），展示时去掉括号；
    // 逐字 timing 保留在去括号后的字词上，并用 words 拼接重建文本，
    // 保证与 LyricResolver 的严格一致校验（words 拼接 == 文本）兼容，不会退化丢词
    private fun stripBackgroundBrackets(line: LyricLine): LyricLine? {
        fun strip(s: String): String = s.filterNot { it == '(' || it == ')' || it == '（' || it == '）' }
        if (line.words.isEmpty()) {
            val text = strip(line.text).trim()
            if (text.isEmpty()) return null
            return line.copy(text = text)
        }
        val kept = line.words.mapNotNull { word ->
            val text = strip(word.text)
            if (text.isBlank()) null else word.copy(text = text)
        }.toMutableList()
        if (kept.isNotEmpty()) {
            kept[0] = kept[0].copy(text = kept[0].text.trimStart())
            if (kept[0].text.isEmpty()) kept.removeAt(0)
        }
        if (kept.isNotEmpty()) {
            kept[kept.lastIndex] = kept[kept.lastIndex].copy(text = kept[kept.lastIndex].text.trimEnd())
            if (kept[kept.lastIndex].text.isEmpty()) kept.removeAt(kept.lastIndex)
        }
        if (kept.isEmpty()) {
            val text = strip(line.text).trim()
            if (text.isEmpty()) return null
            return line.copy(text = text, words = emptyList())
        }
        return line.copy(text = kept.joinToString("") { it.text }, words = kept)
    }

    private fun parseTrack(
        element: Element,
        start: Long,
        end: Long,
        alignment: LyricAlignment,
        translation: String?,
        romanization: String?,
        backgroundLine: LyricLine? = null
    ): LyricLine? {
        val parts = mutableListOf<WordInfo>()
        val text = StringBuilder()
        var validWords = true
        var previousStart = start
        fun walk(node: Node, depth: Int) {
            require(depth <= 64) { "TTML nesting is too deep" }
            if (node is Element) {
                if (node.role() in setOf("x-translation", "x-roman", "x-bg")) return
                if (node.localName == "br") {
                    text.append(' ')
                    validWords = false
                    return
                }
                val timed = node.localName == "span" &&
                    (node.hasAttribute("begin") || node.hasAttribute("end"))
                if (timed) {
                    val before = text.length
                    for (i in 0 until node.childNodes.length) walk(node.childNodes.item(i), depth + 1)
                    val wordText = text.substring(before)
                    val begin = time(node.getAttribute("begin"))
                    val finish = time(node.getAttribute("end"))
                    if (begin == null || finish == null || begin < previousStart || begin < start ||
                        finish < begin || finish > end) validWords = false
                    else if (wordText.isNotBlank()) {
                        parts += WordInfo(wordText, begin - start, finish - begin)
                        previousStart = begin
                    }
                    return
                }
                for (i in 0 until node.childNodes.length) walk(node.childNodes.item(i), depth + 1)
            } else if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val value = node.nodeValue
                if (!(value.isBlank() && (value.contains('\n') || value.contains('\r')))) text.append(value)
            }
        }
        for (i in 0 until element.childNodes.length) walk(element.childNodes.item(i), 0)
        val content = text.toString().trim()
        if (content.isEmpty()) return null
        val words = if (validWords && parts.any { it.durationMs > 0 } &&
            parts.joinToString("") { it.text }.filterNot(Char::isWhitespace) == content.filterNot(Char::isWhitespace)) {
            attachInterstitialSpaces(content, parts)
        } else emptyList()
        return LyricLine(
            timeMs = start,
            durationMs = end - start,
            text = content,
            translation = translation?.trim()?.takeIf { it.isNotEmpty() },
            words = words,
            romanization = romanization?.trim()?.takeIf { it.isNotEmpty() },
            alignment = alignment,
            backgroundLine = backgroundLine
        )
    }

    private fun attachInterstitialSpaces(content: String, parts: List<WordInfo>): List<WordInfo> {
        var cursor = 0
        return parts.mapIndexed { index, word ->
            val token = word.text.trim()
            val at = content.indexOf(token, cursor).coerceAtLeast(cursor)
            val next = if (index == parts.lastIndex) content.length else
                content.indexOf(parts[index + 1].text.trim(), at + token.length)
                    .takeIf { it >= 0 } ?: (at + token.length)
            cursor = next
            word.copy(text = content.substring(at, next))
        }
    }

    private fun parseAgentAlignments(document: Document): Map<String, LyricAlignment> {
        val agents = document.getElementsByTagNameNS("*", "agent")
        return buildMap {
            for (i in 0 until agents.length) {
                val agent = agents.item(i) as? Element ?: continue
                val id = agent.attribute("id") ?: continue
                put(id, if (isEmpty()) LyricAlignment.START else LyricAlignment.END)
            }
        }
    }

    private fun parseExternalAnnotations(document: Document, containerName: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val containers = document.getElementsByTagNameNS("*", containerName)
        for (i in 0 until containers.length) {
            val container = containers.item(i) as? Element ?: continue
            container.childElements().filter { it.localName == "text" }.forEach { text ->
                val key = text.attribute("for")
                val value = text.textContent.trim()
                if (key != null && value.isNotEmpty()) result[key] = value
            }
        }
        return result
    }

    private fun Element.directAnnotation(role: String): String? = childElements()
        .firstOrNull { it.role() == role }?.textContent?.trim()?.takeIf { it.isNotEmpty() }

    private fun Element.role(): String? = attribute("role")

    private fun Element.attribute(localName: String): String? {
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            if ((attr.localName ?: attr.nodeName.substringAfter(':')) == localName && attr.nodeValue.isNotBlank()) {
                return attr.nodeValue
            }
        }
        return null
    }

    private fun Element.childElements(): List<Element> = buildList {
        for (i in 0 until childNodes.length) (childNodes.item(i) as? Element)?.let(::add)
    }

    internal fun time(value: String): Long? {
        val v = value.trim()
        val seconds = when {
            v.matches(Regex("\\d+(\\.\\d+)?ms")) -> v.dropLast(2).toBigDecimalOrNull()?.movePointLeft(3)
            v.matches(Regex("\\d+(\\.\\d+)?s")) -> v.dropLast(1).toBigDecimalOrNull()
            // AMLL also uses TTML's bare offset time, where the value is seconds.
            v.matches(Regex("\\d+(\\.\\d+)?")) -> v.toBigDecimalOrNull()
            v.matches(Regex("(?:\\d+:)?\\d{1,2}:\\d{2}(?:\\.\\d+)?")) -> {
                val fields = v.split(':').map { it.toBigDecimal() }
                val minute = BigDecimal(60)
                if (fields.last() >= minute || (fields.size == 3 && fields[1] >= minute)) return null
                fields.fold(BigDecimal.ZERO) { acc, n -> acc * minute + n }
            }
            else -> null
        } ?: return null
        return seconds.movePointRight(3).takeIf {
            it >= BigDecimal.ZERO && it <= BigDecimal(604800000)
        }?.toLong()
    }
}
