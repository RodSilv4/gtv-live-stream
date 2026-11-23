package to.silva.gtvlivestream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowRequestTest {

    private val defaults = ShowDefaults(
        position = "bottom-right",
        widthPct = 38,
        heightPct = 38,
        durationS = 40,
        mute = true,
    )

    @Test
    fun validPayloadIsParsed() {
        val json = """
            {
              "url": "rtsp://user:pass@camera.example.com:554/stream1",
              "title": "Front Door",
              "subtitle": "Person · 7:42 PM",
              "position": "top-left",
              "width_pct": 42,
              "height_pct": 55,
              "duration_s": 25,
              "mute": false
            }
        """.trimIndent()

        val request = ShowRequestParser.parse(json, defaults)

        assertEquals("rtsp://user:pass@camera.example.com:554/stream1", request.url)
        assertEquals("Front Door", request.title)
        assertEquals("Person · 7:42 PM", request.subtitle)
        assertEquals("top-left", request.position)
        assertEquals(42, request.widthPct)
        assertEquals(55, request.heightPct)
        assertEquals(25, request.durationS)
        assertFalse(request.mute)
    }

    @Test
    fun defaultsAreAppliedWhenOptionalFieldsAreMissing() {
        val json = """{"url":"https://example.test/live.m3u8"}"""

        val request = ShowRequestParser.parse(json, defaults)

        assertEquals("Live", request.title)
        assertNull(request.subtitle)
        assertEquals("bottom-right", request.position)
        assertEquals(38, request.widthPct)
        assertEquals(38, request.heightPct)
        assertEquals(40, request.durationS)
        assertTrue(request.mute)
    }

    @Test
    fun schemesAreAcceptedCaseInsensitively() {
        val https = ShowRequestParser.parse("""{"url":"HTTPS://example.test/a.m3u8"}""", defaults)
        val rtsps = ShowRequestParser.parse("""{"url":"rtsps://example.test/stream"}""", defaults)
        val http = ShowRequestParser.parse("""{"url":"http://example.test/a.mp4"}""", defaults)

        assertEquals("HTTPS://example.test/a.m3u8", https.url)
        assertEquals("rtsps://example.test/stream", rtsps.url)
        assertEquals("http://example.test/a.mp4", http.url)
    }

    @Test
    fun percentagesAreClamped() {
        val low = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","width_pct":1,"height_pct":2}""",
            defaults,
        )
        assertEquals(15, low.widthPct)
        assertEquals(15, low.heightPct)

        val high = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","width_pct":400,"height_pct":1000}""",
            defaults,
        )
        assertEquals(100, high.widthPct)
        assertEquals(100, high.heightPct)
    }

    @Test
    fun durationIsClampedAndZeroIsPreserved() {
        val negative = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","duration_s":-30}""",
            defaults,
        )
        assertEquals(0, negative.durationS)

        val tooLong = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","duration_s":99999}""",
            defaults,
        )
        assertEquals(600, tooLong.durationS)

        val sticky = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","duration_s":0}""",
            defaults,
        )
        assertEquals(0, sticky.durationS)
    }

    @Test
    fun unknownPositionFallsBackToDefault() {
        val request = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","position":"diagonal"}""",
            defaults.copy(position = "top-right"),
        )
        assertEquals("top-right", request.position)
    }

    @Test
    fun invalidDefaultPositionFallsBackToBottomRight() {
        val request = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","position":"diagonal"}""",
            defaults.copy(position = "nonsense"),
        )
        assertEquals("bottom-right", request.position)
    }

    @Test
    fun titleAndSubtitleAreTrimmedAndSized() {
        val longTitle = "T".repeat(200)
        val longSubtitle = "S".repeat(300)
        val request = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","title":"  $longTitle  ","subtitle":"  $longSubtitle  "}""",
            defaults,
        )
        assertEquals(80, request.title.length)
        assertEquals(120, request.subtitle?.length)

        val blankSubtitle = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","subtitle":"   "}""",
            defaults,
        )
        assertNull(blankSubtitle.subtitle)

        val blankTitle = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","title":"   "}""",
            defaults,
        )
        assertEquals("Live", blankTitle.title)
    }

    @Test
    fun missingUrlThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("""{"title":"Front Door"}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("""{"url":"   "}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("{}", defaults)
        }
    }

    @Test
    fun unsupportedSchemeThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("""{"url":"file:///sdcard/a.mp4"}""", defaults)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("""{"url":"ftp://example.test/a.mp4"}""", defaults)
        }
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            ShowRequestParser.parse("not json at all", defaults)
        }
    }

    @Test
    fun numericStringsAreAcceptedForNumbers() {
        val request = ShowRequestParser.parse(
            """{"url":"http://example.test/a.mp4","width_pct":"50","duration_s":"15"}""",
            defaults,
        )
        assertEquals(50, request.widthPct)
        assertEquals(15, request.durationS)
    }
}
