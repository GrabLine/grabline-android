package dev.grabline.app.ui.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipboardOfferTest {
    private val link = "https://example.com/video"

    @Test fun offersNewLink() = assertEquals(link, ClipboardOfferLogic.decide("look $link", true, null))

    @Test fun skipsSameLinkAgain() {
        val h = ClipboardOfferLogic.hash(link)
        assertNull(ClipboardOfferLogic.decide(link, true, h))
    }

    @Test fun offersDifferentLink() {
        val h = ClipboardOfferLogic.hash(link)
        assertEquals("https://other.com/a", ClipboardOfferLogic.decide("https://other.com/a", true, h))
    }

    @Test fun disabledOffersNothing() = assertNull(ClipboardOfferLogic.decide(link, false, null))

    @Test fun noLinkOffersNothing() {
        assertNull(ClipboardOfferLogic.decide("hello", true, null))
        assertNull(ClipboardOfferLogic.decide(null, true, null))
    }

    @Test fun takesFirstLink() =
        assertEquals("https://a.com/1", ClipboardOfferLogic.decide("https://a.com/1 https://b.com/2", true, null))

    @Test fun hashIsSha256HexAndNotTheLink() {
        val h = ClipboardOfferLogic.hash(link)
        assertEquals(64, h.length)
        assertNotEquals(link, h)
        assertEquals(h, ClipboardOfferLogic.hash(link))
    }
}
