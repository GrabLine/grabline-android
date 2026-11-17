package dev.grabline.app.engine

import androidx.test.core.app.ApplicationProvider
import dev.grabline.app.GrabLineApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P2-02: a job added to the engine is picked up by the scheduled work (the test process is not visible, so this is
 * the persisted-job path; it uses the same engine run as the user-initiated job and the service) and completes.
 * Needs no notification permission, which also covers "notifications denied still downloads" (QA-32).
 */
class BackgroundDownloadTest {
    @Test
    fun addedJobCompletesThroughScheduledWork() = runBlocking {
        val body = ByteArray(300_000) { (it % 251).toByte() }
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", body.size.toString())
                .setBody(Buffer().write(body))
        }
        server.start()
        try {
            val container = ApplicationProvider.getApplicationContext<GrabLineApp>().container
            val url = server.url("/bg-${System.nanoTime()}.bin").toString()
            val added = container.phone.add(PhoneAddRequest(url))
            val done = withTimeout(90_000) {
                container.phone.jobs.first { list -> list.firstOrNull { it.id == added.id }?.status == PhoneJobStatus.COMPLETED }
            }.first { it.id == added.id }
            assertEquals(body.size.toLong(), done.total)
            assertNotNull(done.fileUri)
            container.phone.remove(added.id)
            // Removing the job keeps the file (by design); a test must not leave it in the user's Downloads.
            ApplicationProvider.getApplicationContext<GrabLineApp>().contentResolver
                .delete(android.net.Uri.parse(done.fileUri), null, null)
        } finally {
            server.shutdown()
        }
        Unit // JUnit needs a void test method; runBlocking would otherwise return the delete count
    }
}
