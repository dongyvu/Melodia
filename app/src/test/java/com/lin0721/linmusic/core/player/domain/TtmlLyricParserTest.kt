package com.lin0721.linmusic.core.player.domain

import org.junit.Assert.*
import org.junit.Test

class TtmlLyricParserTest {
    @Test fun duetRomanizationBackgroundAndExternalAnnotations() {
        val xml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata" xmlns:i="http://music.apple.com/lyric-ttml-internal">
              <head><metadata><m:agent xml:id="v1"/><m:agent xml:id="v2"/>
                <i:translations><i:translation><i:text for="L1">外部翻译</i:text></i:translation></i:translations>
                <i:transliterations><i:transliteration><i:text for="L1"><span>romaji </span><span>line</span></i:text></i:transliteration></i:transliterations>
              </metadata></head>
              <body><div>
                <p begin="1s" end="3s" m:agent="v2" i:key="L1"><span begin="1s" end="3s">main</span>
                  <span m:role="x-bg" begin="2500ms" end="4s"><span begin="2500ms" end="4s">(back)</span><span m:role="x-translation">背景翻译</span><span m:role="x-roman">background roman</span></span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals(LyricAlignment.END, line.alignment)
        assertEquals("外部翻译", line.translation)
        assertEquals("romaji line", line.romanization)
        val background = requireNotNull(line.backgroundLine)
        assertEquals(2500L, background.timeMs)
        assertEquals(1500L, background.durationMs)
        assertEquals("back", background.text)
        assertEquals("背景翻译", background.translation)
        assertEquals("background roman", background.romanization)
        assertEquals(LyricAlignment.END, background.alignment)
        assertEquals(listOf(WordInfo("back", 0, 1500)), background.words)
    }

    @Test fun backgroundBracketsAreStrippedKeepingWordTiming() {
        // 括号在无时间的文本节点里：原逐字校验本来就过不了，去括号后整行展示
        val outer = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s">(<t:span begin="2500ms" end="4s">back</t:span>)</t:span></t:p>""").single()
        assertEquals("back", requireNotNull(outer.backgroundLine).text)
        assertTrue(requireNotNull(outer.backgroundLine).words.isEmpty())
        // 全角括号同样去除
        val fullWidth = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s"><t:span begin="2500ms" end="4s">（合唱）</t:span></t:span></t:p>""").single()
        assertEquals("合唱", requireNotNull(fullWidth.backgroundLine).text)
        // 只剩括号的背景行直接丢弃
        val empty = parse("""<t:p begin="1s" end="4s"><t:span begin="1s" end="2s">main</t:span><t:span m:role="x-bg" begin="2500ms" end="4s">()</t:span></t:p>""").single()
        assertNull(empty.backgroundLine)
    }

    @Test fun inlineRomanizationOverridesExternalRomanization() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata" xmlns:i="http://music.apple.com/lyric-ttml-internal"><head><metadata><i:transliterations><i:transliteration><i:text for="L1">external</i:text></i:transliteration></i:transliterations></metadata></head><body><div><p begin="1s" end="2s" i:key="L1"><span begin="1s" end="2s">歌</span><span m:role="x-roman">uta</span></p></div></body></tt>"""
        assertEquals("uta", TtmlLyricParser.parse(xml).single().romanization)
    }

    @Test fun amllExportsCanResetDefaultNamespaceInBody() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata"><head><metadata xmlns=""><p begin="1s" end="2s">not lyrics</p></metadata></head><body><div xmlns=""><p begin="1s" end="2s"><span begin="1s" end="2s">hello</span><span m:role="x-translation">你好</span></p></div></body></tt>"""
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals("hello", line.text)
        assertEquals("你好", line.translation)
        assertEquals(1, line.words.size)
    }

    private fun parse(body: String) = TtmlLyricParser.parse("""
        <t:tt xmlns:t="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata">
        <t:body><t:div>$body</t:div></t:body></t:tt>
    """.trimIndent())

    @Test fun wordsTranslationNamespacesAndEntities() {
        val line = parse("""<t:p begin="00:01.000" end="00:04.000"><t:span begin="1s" end="2s">You</t:span> <t:span begin="2500ms" end="4s">&amp; I</t:span><t:span m:role="x-translation">你&#x548c;我</t:span><t:span m:role="x-roman">ignored</t:span><t:span m:role="x-bg">background</t:span></t:p>""").single()
        assertEquals("You & I", line.text)
        assertEquals("你和我", line.translation)
        assertEquals(3000L, line.durationMs)
        assertEquals(listOf(WordInfo("You ", 0, 1000), WordInfo("& I", 1500, 1500)), line.words)
    }

    @Test fun plainLinesAndStableDuplicateTimes() {
        val lines = parse("""<t:p begin="2s" end="3s">third</t:p><t:p begin="1s" end="2s">first</t:p><t:p begin="1s" end="2s">second</t:p><t:p begin="1s" end="2s"> </t:p>""")
        assertEquals(listOf("first", "second", "third"), lines.map { it.text })
        assertTrue(lines.all { it.words.isEmpty() })
    }

    @Test fun invalidLinesAreSkipped() {
        assertTrue(parse("""<t:p end="2s">missing</t:p><t:p begin="3s" end="2s">reversed</t:p><t:p begin="1s" end="1s">zero</t:p>""").isEmpty())
    }

    @Test fun invalidWordTimingFallsBackWithoutLosingText() {
        for (attributes in listOf("begin=\"2s\" end=\"1s\"", "begin=\"1s\"", "begin=\"0s\" end=\"1s\"", "begin=\"1s\" end=\"5s\"")) {
            val line = parse("""<t:p begin="1s" end="3s"><t:span $attributes>hello</t:span></t:p>""").single()
            assertEquals("hello", line.text)
            assertTrue(line.words.isEmpty())
        }
    }

    @Test fun zeroDurationWordsAreSafeAndUntimedTextIsPreserved() {
        val line = parse("""<t:p begin="1s" end="3s"><t:span begin="1s" end="1s">a</t:span><t:span begin="1s" end="3s">b</t:span></t:p>""").single()
        assertEquals(0L, line.words.first().durationMs)
        val partial = parse("""<t:p begin="1s" end="3s">prefix<t:span begin="1s" end="3s">word</t:span></t:p>""").single()
        assertEquals("prefixword", partial.text)
        assertTrue(partial.words.isEmpty())
    }

    @Test fun malformedAndUntrustedXmlAreRejected() {
        for (xml in listOf("<tt>", "<html>error</html>",
            """<!DOCTYPE tt [<!ENTITY x SYSTEM "file:///etc/passwd">]><tt xmlns="http://www.w3.org/ns/ttml">&x;</tt>""",
            "x".repeat(TtmlLyricParser.MAX_LENGTH + 1))) assertTrue(TtmlLyricParser.parse(xml).isEmpty())
    }

    @Test fun timestamps() {
        assertEquals(3723456L, TtmlLyricParser.time("1:02:03.456"))
        assertEquals(1234L, TtmlLyricParser.time("1.234s"))
        assertEquals(1234L, TtmlLyricParser.time("1234ms"))
        assertEquals(3712L, TtmlLyricParser.time("3.712"))
        assertEquals(1001L, TtmlLyricParser.time("1.001s"))
        for (value in listOf("-1s", "NaNs", "1:60.0", "1:61:00", "", "99999999999999999s"))
            assertNull(TtmlLyricParser.time(value))
    }

    @Test fun mixedBareSecondsAndClockTimesKeepEarlyLyrics() {
        val lines = parse("""
            <t:p begin="3.712" end="11.037"><t:span begin="3.712" end="11.037">early</t:span></t:p>
            <t:p begin="59.917" end="1:01.280"><t:span begin="59.917" end="1:01.280">boundary</t:span></t:p>
            <t:p begin="1:02.962" end="1:05.656"><t:span begin="1:02.962" end="1:05.656">later</t:span></t:p>
        """)
        assertEquals(listOf(3712L, 59917L, 62962L), lines.map { it.timeMs })
        assertEquals(listOf("early", "boundary", "later"), lines.map { it.text })
        assertTrue(lines.all { it.words.isNotEmpty() })
    }
}
