package dev.grabline.app.engine.cloud

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The worked examples in the AWS Signature Version 4 documentation for S3. */
class SigV4Test {
    private val access = "AKIAIOSFODNN7EXAMPLE"
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val now = Instant.parse("2013-05-24T00:00:00Z")

    @Test fun getObjectWithRange() {
        val h = SigV4.sign(
            "GET", "examplebucket.s3.amazonaws.com", "/test.txt", emptyList(), mapOf("range" to "bytes=0-9"),
            access, secret, "us-east-1", now,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date," +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            h["authorization"],
        )
        assertEquals("20130524T000000Z", h["x-amz-date"])
    }

    @Test fun listObjectsWithQuery() {
        val h = SigV4.sign(
            "GET", "examplebucket.s3.amazonaws.com", "/", listOf("max-keys" to "2", "prefix" to "J"), emptyMap(),
            access, secret, "us-east-1", now,
        )
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request," +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date," +
                "Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            h["authorization"],
        )
    }
}
