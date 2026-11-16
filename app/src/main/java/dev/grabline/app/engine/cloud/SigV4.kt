package dev.grabline.app.engine.cloud

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A small AWS Signature Version 4 signer for S3 requests that carry no body (GET, HEAD). */
object SigV4 {
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    /**
     * @param path the already-encoded path ("/key/with%20space")
     * @param query already-encoded (name, value) pairs
     * @param headers extra headers to sign (lower-case names); `host` is added from [host]
     * @return headers to send: x-amz-date, x-amz-content-sha256 and authorization
     */
    fun sign(
        method: String,
        host: String,
        path: String,
        query: List<Pair<String, String>>,
        headers: Map<String, String>,
        accessKey: String,
        secretKey: String,
        region: String,
        now: Instant,
        service: String = "s3",
    ): Map<String, String> {
        val amzDate = stamp.format(now)
        val date = day.format(now)
        val all = sortedMapOf<String, String>()
        headers.forEach { (k, v) -> all[k.lowercase()] = v.trim() }
        all["host"] = host
        all["x-amz-content-sha256"] = EMPTY_SHA256
        all["x-amz-date"] = amzDate
        val signed = all.keys.joinToString(";")
        val canonicalQuery = query.sortedWith(compareBy({ it.first }, { it.second })).joinToString("&") { "${it.first}=${it.second}" }
        val canonical = listOf(
            method,
            path.ifEmpty { "/" },
            canonicalQuery,
            all.entries.joinToString("") { "${it.key}:${it.value}\n" },
            signed,
            EMPTY_SHA256,
        ).joinToString("\n")
        val scope = "$date/$region/$service/aws4_request"
        val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${hex(sha256(canonical.toByteArray()))}"
        var key = hmac("AWS4$secretKey".toByteArray(), date)
        key = hmac(key, region)
        key = hmac(key, service)
        key = hmac(key, "aws4_request")
        val signature = hex(hmac(key, toSign))
        return mapOf(
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to EMPTY_SHA256,
            "authorization" to "AWS4-HMAC-SHA256 Credential=$accessKey/$scope,SignedHeaders=$signed,Signature=$signature",
        )
    }

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun hex(b: ByteArray) = b.joinToString("") { String.format("%02x", it) }
}
