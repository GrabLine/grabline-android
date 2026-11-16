package dev.grabline.app.engine.cloud

import dev.grabline.app.ui.entry.IncomingLinks
import dev.grabline.app.ui.entry.LinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudUrlTest {
    @Test fun inlineLoginIsParsedAndDecoded() {
        val t = CloudUrl.parse("ftp://joe:p%40ss@host.example:2121/dir/a%20b.bin")!!
        assertEquals("joe", t.user)
        assertEquals("p@ss", t.password)
        assertEquals(2121, t.port)
        assertEquals("/dir/a b.bin", t.path)
        assertEquals("ftp://joe@host.example:2121/dir/a%20b.bin", t.withoutPassword())
    }

    @Test fun suggestedFilename() {
        assertEquals("movie.mkv", CloudUrl.suggestedFilename("sftp://host/path/to/movie.mkv"))
        assertEquals("report final.pdf", CloudUrl.suggestedFilename("s3://bucket/report%20final.pdf"))
        assertEquals("download", CloudUrl.suggestedFilename("ftp://host/"))
    }

    @Test fun cloudSchemes() {
        assertTrue(CloudSchemes.isCloud("sftp://host/x"))
        assertTrue(CloudSchemes.isCloud("S3://bucket/key"))
        assertTrue(CloudSchemes.isCloud("webdavs://host/x"))
        assertTrue(CloudSchemes.isCloud("scp://host/x"))
        assertFalse(CloudSchemes.isCloud("https://host/x"))
        assertFalse(CloudSchemes.isCloud("gopher://host/x"))
    }

    @Test fun badLinksAreRejected() {
        assertNull(CloudUrl.parse("ftp://"))
        assertNull(CloudUrl.parse("ftp://host:99999/x"))
        assertNull(CloudUrl.parse("https://host/x"))
        assertNull(CloudUrl.parse("sftp://[::1/x"))
    }

    @Test fun ipv6HostKeepsItsBrackets() {
        val t = CloudUrl.parse("webdavs://qa:secret@[::1]:9123/file.bin?token=qa")!!
        assertEquals("::1", t.host)
        assertEquals("https://[::1]:9123/file.bin?token=qa", webDavHttpUrl(t.scheme, t.host, t.port ?: 0, t.rawPath, t.query))
    }

    @Test fun webdavDefaultPortsAreOmitted() {
        assertEquals("http://files.example/a", webDavHttpUrl("webdav", "files.example", 80, "/a", null))
        assertEquals("https://files.example:8443/a", webDavHttpUrl("webdavs", "files.example", 8443, "/a", null))
    }

    @Test fun folderLinksListAsSingleSlashFileLinks() {
        val t = CloudUrl.parse("sftp://example.test/")!!
        assertTrue(t.isFolder)
        assertEquals("sftp://example.test/file.bin", t.child("file.bin"))
        val deep = CloudUrl.parse("ftp://bob@h:21/a/b/")!!
        assertEquals("ftp://bob@h:21/a/b/x%20y.bin", deep.child("x y.bin"))
        assertFalse(CloudUrl.parse("ftp://h/a/b.bin")!!.isFolder)
    }

    @Test fun linksAreRecognisedInSharedText() {
        val found = IncomingLinks.extract("get sftp://host/a.zip, and (s3://bucket/key.bin) or ftps://u:p@h/x/ now")
        assertEquals(listOf("sftp://host/a.zip", "s3://bucket/key.bin", "ftps://u:p@h/x/"), found)
        assertEquals(LinkKind.CLOUD, LinkKind.classify("webdav://h/a"))
        assertEquals(LinkKind.PAGE, LinkKind.classify("gopher://h/a"))
    }
}
