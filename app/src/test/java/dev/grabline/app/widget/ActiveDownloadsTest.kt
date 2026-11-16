package dev.grabline.app.widget

import dev.grabline.app.engine.PhoneJobStatus
import dev.grabline.app.remote.connection.PcLinkState
import dev.grabline.app.ui.downloads.DeviceFilter
import dev.grabline.app.ui.downloads.StateFilter
import dev.grabline.app.ui.downloads.fakeJob
import dev.grabline.app.ui.downloads.matches
import dev.grabline.app.ui.downloads.phoneJob
import dev.grabline.app.ui.downloads.toRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveDownloadsTest {
    private val online = PcLinkState.Online("PC", "1", emptySet(), "10.0.0.2")

    @Test fun onlyRunningQueuedAndWaitingCountAsActive() {
        val active = listOf(PhoneJobStatus.QUEUED, PhoneJobStatus.DOWNLOADING, PhoneJobStatus.WAITING_WIFI, PhoneJobStatus.WAITING_NETWORK, PhoneJobStatus.WAITING_CHARGING)
        val inactive = listOf(PhoneJobStatus.PAUSED, PhoneJobStatus.COMPLETED, PhoneJobStatus.FAILED, PhoneJobStatus.CANCELLED)
        active.forEach { assertTrue("$it", ActiveDownloads.fromPhone(phoneJob(1, it)) != null) }
        inactive.forEach { assertNull("$it", ActiveDownloads.fromPhone(phoneJob(1, it))) }
    }

    @Test fun theThreeNewestAcrossDevicesAreChosen() {
        val items = buildList {
            add(ActiveDownloads.fromPhone(phoneJob(1))!!)
            add(ActiveDownloads.fromPhone(phoneJob(5))!!)
            add(ActiveDownloads.fromPc(fakeJob("pc1", 3, "downloading"), online)!!)
            add(ActiveDownloads.fromPc(fakeJob("pc2", 9, "queued", pcName = "Laptop"), online)!!)
            add(ActiveDownloads.fromPc(fakeJob("pc1", 7, "downloading"), online)!!)
        }
        val shown = ActiveDownloads.newest(items)
        assertEquals(listOf(9L, 7L, 5L), shown.map { it.createdAt })
        assertEquals(listOf("Laptop", "Desk PC", null), shown.map { it.pcName })
    }

    @Test fun fewerThanThreeShowsWhatThereIs() {
        assertEquals(1, ActiveDownloads.newest(listOf(ActiveDownloads.fromPhone(phoneJob(2))!!)).size)
        assertTrue(ActiveDownloads.newest(emptyList()).isEmpty())
    }

    @Test fun progressIsAPercentOrUnknown() {
        assertEquals(10, ActiveDownloads.fromPhone(phoneJob(1))!!.percent)
        val unknown = phoneJob(1).copy(total = null)
        assertNull(ActiveDownloads.fromPhone(unknown)!!.percent)
    }

    @Test fun anUnreachablePcsRowsAreStaleAndLeftOut() {
        val offline = PcLinkState.Offline(1_000L)
        assertNull(ActiveDownloads.fromPc(fakeJob("pc1", 3, "downloading"), offline))
        assertNull(ActiveDownloads.fromPc(fakeJob("pc1", 3, "completed"), online))
        assertNull(ActiveDownloads.fromPc(fakeJob("pc1", 3, "paused"), online))
    }

    @Test fun theTileLabelShowsTheCountOrInvitesAnAdd() {
        val items = listOf(ActiveDownloads.fromPhone(phoneJob(1))!!, ActiveDownloads.fromPhone(phoneJob(2))!!)
        assertEquals(2, ActiveDownloads.count(items))
        assertEquals("2 active", TileLabel.text(ActiveDownloads.count(items), { "$it active" }, "Add a link"))
        assertEquals("Add a link", TileLabel.text(0, { "$it active" }, "Add a link"))
    }

    @Test fun theWidgetUpdatesEvery30SecondsWhileClosedAndCloselyWhileOpen() {
        assertEquals(30_000L, ActiveDownloads.updateIntervalMs(appOpen = false))
        assertTrue(ActiveDownloads.updateIntervalMs(appOpen = true) < ActiveDownloads.updateIntervalMs(appOpen = false))
    }

    /** Several PCs: the device chips pick any of the chosen PCs, and the default-device setting is covered in AddViewModelTest. */
    @Test fun deviceChipsWorkWithTwoOrMorePcs() {
        val a = toRow(fakeJob("pcA", 1, "downloading", pcName = "Desk"), online)
        val b = toRow(fakeJob("pcB", 2, "downloading", pcName = "Laptop"), online)
        val none = emptySet<StateFilter>()
        assertTrue(a.matches(setOf(DeviceFilter.Pc("pcA")), none))
        assertFalse(b.matches(setOf(DeviceFilter.Pc("pcA")), none))
        assertTrue(listOf(a, b).all { it.matches(setOf(DeviceFilter.Pc("pcA"), DeviceFilter.Pc("pcB")), none) })
        assertEquals(listOf(b), listOf(a, b).filter { it.matches(setOf(DeviceFilter.Pc("pcB")), setOf(StateFilter.Active)) })
    }
}
