package dev.grabline.app.remote.security

import dev.grabline.app.remote.protocol.Base64Url
import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The shared test vectors (`protocol/fixtures/vectors.json`), the same file the desktop tests load. */
object Vectors {
    private val dir: File = File(
        checkNotNull(Vectors::class.java.classLoader!!.getResource("protocol/fixtures/vectors.json")) { "vectors.json not on the test classpath" }.toURI(),
    ).parentFile

    val root: JsonObject = Json.parseToJsonElement(File(dir, "vectors.json").readText()).jsonObject

    fun text(name: String): String = File(dir, name).readText()

    /** The `message` object of a fixture, as JSON text. */
    fun message(name: String): String = Json.parseToJsonElement(text(name)).jsonObject["message"]!!.toString()

    private fun str(vararg path: String): String {
        var o = root
        for (p in path.dropLast(1)) o = o[p]!!.jsonObject
        return o[path.last()]!!.jsonPrimitive.content
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    val code: String = str("pairingCode")
    val nonce: ByteArray = Base64Url.decode(str("nonce"))!!
    val pin: String = str("pinnedFingerprint")
    val pinBytes: ByteArray = hex(str("pinnedFingerprintHex"))
    val pcId: String = str("pcId")
    val deviceId: String = str("deviceId")
    val spki: ByteArray = Base64Url.decode(str("deviceKey", "publicKeySpkiDer"))!!
    val spkiSha256Hex: String = str("deviceKey", "publicKeySpkiDerSha256Hex")
    val codeProofInputHex: String = str("pair", "codeProofInputHex")
    val codeProof: String = str("pair", "codeProof")
    val serverProofInputHex: String = str("pair", "serverProofInputHex")
    val serverProof: String = str("pair", "serverProof")
    val transcriptHex: String = str("auth", "transcriptHex")
    val signatureDer: ByteArray = Base64Url.decode(str("auth", "signatureDer"))!!
    val qrUri: String = str("qrUri")

    val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))

    /** TEST-ONLY key pair from the public seed in vectors.json. */
    val keyPair: KeyPair by lazy {
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val d = BigInteger(str("deviceKey", "privateScalarHex"), 16)
        KeyPair(publicKey, KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(d, params)))
    }
}
