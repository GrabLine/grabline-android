package dev.grabline.app.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingLinksTest {
    private fun x(s: String?) = IncomingLinks.extract(s)

    @Test fun plainUrl() = assertEquals(listOf("https://example.com/a.zip"), x("https://example.com/a.zip"))

    @Test fun youtubeShare() = assertEquals(
        listOf("https://youtu.be/abc123?si=xyz"),
        x("Cool video - YouTube\nhttps://youtu.be/abc123?si=xyz"),
    )

    @Test fun chromeTitleShape() = assertEquals(
        listOf("https://example.com/page"),
        x("Example Domain - https://example.com/page"),
    )

    @Test fun multipleLinks() = assertEquals(
        listOf("https://a.com/1", "http://b.org/2"),
        x("first https://a.com/1 then\nhttp://b.org/2 done"),
    )

    @Test fun magnetWithDn() = assertEquals(
        listOf("magnet:?xt=urn:btih:ABCDEF&dn=My+Linux+ISO&tr=udp%3A%2F%2Ftracker"),
        x("Get magnet:?xt=urn:btih:ABCDEF&dn=My+Linux+ISO&tr=udp%3A%2F%2Ftracker now"),
    )

    @Test fun trailingParen() = assertEquals(listOf("https://a.com/x"), x("(see https://a.com/x)"))

    @Test fun trailingPeriod() = assertEquals(listOf("https://a.com/x"), x("Go to https://a.com/x."))

    @Test fun trailingCommaAndQuestion() =
        assertEquals(listOf("https://a.com/x", "https://b.com/y"), x("https://a.com/x, https://b.com/y?"))

    @Test fun balancedParenKept() =
        assertEquals(listOf("https://en.wikipedia.org/wiki/Foo_(bar)"), x("https://en.wikipedia.org/wiki/Foo_(bar)"))

    @Test fun javascriptRejected() = assertTrue(x("javascript:alert(1)").isEmpty())

    @Test fun javascriptWrappingHttpRejected() = assertTrue(x("javascript:https://evil.com").isEmpty())

    @Test fun fileRejected() = assertTrue(x("file:///sdcard/a.txt").isEmpty())

    @Test fun intentRejected() = assertTrue(x("intent://scan/#Intent;scheme=zxing;end").isEmpty())

    @Test fun contentRejected() = assertTrue(x("content://media/external/1").isEmpty())

    @Test fun serverLinksAreAccepted() = assertEquals(listOf("ftp://example.com/a"), x("ftp://example.com/a"))

    @Test fun unknownSchemesRejected() = assertTrue(x("gopher://example.com/a").isEmpty())

    @Test fun dedupPreservesOrder() = assertEquals(
        listOf("https://a.com", "https://b.com"),
        x("https://a.com https://b.com https://a.com"),
    )

    @Test fun capAt300() {
        val text = (1..500).joinToString(" ") { "https://example.com/$it" }
        val out = x(text)
        assertEquals(300, out.size)
        assertEquals("https://example.com/1", out.first())
        assertEquals("https://example.com/300", out.last())
    }

    @Test fun emptyAndNull() {
        assertTrue(x(null).isEmpty())
        assertTrue(x("  ").isEmpty())
        assertTrue(x("no links here").isEmpty())
    }

    @Test fun uppercaseScheme() = assertEquals(listOf("HTTPS://Example.com/A"), x("HTTPS://Example.com/A"))

    @Test fun bareSchemeRejected() = assertTrue(x("https:// and http://").isEmpty())

    @Test fun magnetWithoutXtRejected() = assertTrue(x("magnet:?dn=foo").isEmpty())

    @Test fun nullHandledByKind() = assertNull(null as String?)
}
