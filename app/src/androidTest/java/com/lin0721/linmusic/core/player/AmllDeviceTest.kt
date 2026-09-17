package com.lin0721.linmusic.core.player

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lin0721.linmusic.core.player.data.AmllLyricsClient
import com.lin0721.linmusic.core.player.data.LyricsCache
import com.lin0721.linmusic.core.player.domain.TtmlLyricParser
import com.lin0721.linmusic.core.player.domain.LyricAlignment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit device smoke tests; the live test requires internet access. */
@RunWith(AndroidJUnit4::class)
class AmllDeviceTest {
    @Test fun androidXmlParserSupportsWordsTranslationAndRejectsDoctype() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata"><body><div><p begin="1.001s" end="3s"><span begin="1.001s" end="2s">You</span> <span begin="2s" end="3s">&amp; I</span><span m:role="x-translation">你和我</span></p></div></body></tt>"""
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals(1001L, line.timeMs)
        assertEquals("You & I", line.text)
        assertEquals("你和我", line.translation)
        assertEquals(2, line.words.size)
        assertEquals(line, TtmlLyricParser.parse(xml.replace("<div>", "<div xmlns=\"\">" )).single())
        assertTrue(TtmlLyricParser.parse("<!DOCTYPE tt [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>$xml").isEmpty())
    }

    @Test fun liveAmllDownloadAndParse() = runBlocking {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "amll-device-test")
        root.deleteRecursively()
        val cache = LyricsCache(root)
        val started = android.os.SystemClock.elapsedRealtime()
        val client = AmllLyricsClient(cache)
        val xml = client.fetch(36990266)
        val lines = xml?.let(TtmlLyricParser::parse).orEmpty()
        Log.i("AmllDeviceTest", "live fetch: elapsed=${android.os.SystemClock.elapsedRealtime() - started}ms lines=${lines.size} wordLines=${lines.count { it.words.isNotEmpty() }} translations=${lines.count { !it.translation.isNullOrBlank() }}")
        assertTrue("Known AMLL song should download and parse on device", lines.isNotEmpty())
        assertTrue(lines.any { it.words.isNotEmpty() })
        assertEquals(xml, cache.getRaw(36990266))
        assertEquals(xml, client.fetch(36990266))
        root.deleteRecursively()
        Unit
    }

    @Test fun phaseTwoFeaturesOnDevice() {
        val xml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:m="http://www.w3.org/ns/ttml#metadata">
              <head><metadata><m:agent xml:id="v1"/><m:agent xml:id="v2"/></metadata></head>
              <body><div><p begin="3.712" end="6s" m:agent="v2">
                <span begin="3.712" end="5s">main</span>
                <span m:role="x-roman">romaji</span>
                <span m:role="x-bg" begin="5s" end="6s"><span begin="5s" end="6s">back</span></span>
              </p></div></body>
            </tt>
        """.trimIndent()
        val line = TtmlLyricParser.parse(xml).single()
        assertEquals(3712L, line.timeMs)
        assertEquals(LyricAlignment.END, line.alignment)
        assertEquals("romaji", line.romanization)
        assertNotNull(line.backgroundLine)
    }
}
