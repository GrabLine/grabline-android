package dev.grabline.app.ui.settings

import dev.grabline.app.engine.cloud.CloudAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerFormTest {
    @Test fun validation() {
        assertEquals(ServerForm.Problem.HOST, ServerForm().problem())
        assertEquals(ServerForm.Problem.HOST, ServerForm(host = "a/b").problem())
        assertEquals(ServerForm.Problem.PORT, ServerForm(host = "h", port = "70000").problem())
        assertEquals(ServerForm.Problem.ENDPOINT, ServerForm(service = "s3", host = "b", endpoint = "minio").problem())
        assertEquals(ServerForm.Problem.KEY, ServerForm(service = "sftp", host = "h", useKey = true).problem())
        assertNull(ServerForm(service = "sftp", host = "h", useKey = true, keepKey = true).problem())
        assertNull(ServerForm(service = "s3", host = "b", endpoint = "https://minio.example:9000").problem())
    }

    @Test fun emptyPasswordKeepsTheSavedSecret() {
        assertNull(ServerForm(host = "h").secrets())
        assertEquals("pw", ServerForm(host = "h", password = "pw").secrets()!!.password)
    }

    @Test fun keyModeStoresTheKeyNotThePassword() {
        val s = ServerForm(service = "sftp", host = "h", useKey = true, privateKey = "KEY", passphrase = "pp", password = "old").secrets()!!
        assertEquals("", s.password)
        assertEquals("pp", s.passphrase)
        assertEquals("KEY\n", s.privateKey)
    }

    @Test fun accountDropsS3FieldsForOtherServices() {
        val a = ServerForm(service = "ftp", host = " h ", port = "2121", user = "u", endpoint = "https://x", region = "r").account(0)
        assertEquals(CloudAccount(service = "ftp", host = "h", port = 2121, username = "u"), a)
    }
}
