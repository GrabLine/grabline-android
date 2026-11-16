package dev.grabline.app.remote.connection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A scriptable PC speaking protocol 6 over fake sockets. */
class FakePc(val pcId: String = "pcA", val pcName: String = "Study PC") : WireFactory {
    enum class AuthMode { OK, UNAUTHORIZED_REPLY, CLOSE_4401 }

    sealed interface Mode {
        data class Unreachable(val delayMs: Long = 0) : Mode
        data object WrongKey : Mode
        data object Accept : Mode
    }

    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "fake-pc").apply { isDaemon = true } }
    private val json = Json

    val modes = ConcurrentHashMap<String, Mode>()
    @Volatile var defaultMode: Mode = Mode.Accept
    @Volatile var authMode = AuthMode.OK
    /** Replaces the whole hello frame when set (e.g. the v5 fixture). */
    @Volatile var helloFrame: String? = null
    @Volatile var authOkAddresses: List<String> = listOf("10.0.0.1", "10.0.0.2")
    @Volatile var authOkPort = 47821
    @Volatile var authOkName = pcName
    @Volatile var authOkFeatures: List<String> = listOf("jobs", "control", "global")

    /** type -> (request id, request body) -> reply frames; null = stay silent. */
    val handlers = ConcurrentHashMap<String, (String, JsonObject) -> List<String>?>()
    /** Pages answered by the default `jobs` handler: cursor "N" selects page N. */
    @Volatile var jobPages: List<List<String>> = listOf(emptyList())

    /** Like [handlers] but with the connection, so a handler can stream binary frames or drop the link. Checked first. */
    val connHandlers = ConcurrentHashMap<String, (Conn, String, JsonObject) -> List<String>?>()

    val attempts = CopyOnWriteArrayList<String>()
    val conns = CopyOnWriteArrayList<Conn>()

    init {
        handlers["subscribe"] = { id, _ ->
            listOf("""{"id":"$id","type":"subscribed","protocolVersion":6,"topics":["jobs","global"],"state":{"paused":false,"speed":0,"active":1,"queued":0}}""")
        }
        handlers["ping"] = { id, _ -> listOf("""{"id":"$id","type":"pong","protocolVersion":6,"appRunning":true}""") }
        handlers["jobs"] = { id, body ->
            val pages = jobPages
            val index = body["cursor"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val next = if (index + 1 < pages.size) "\"${index + 1}\"" else "null"
            listOf("""{"id":"$id","type":"jobs","protocolVersion":6,"jobs":[${pages[index].joinToString(",")}],"nextCursor":$next,"total":${pages.sumOf { it.size }}}""")
        }
    }

    val liveConns: List<Conn> get() = conns.filter { !it.closed }
    val lastLive: Conn? get() = liveConns.lastOrNull()
    fun attemptsTo(address: String) = attempts.count { it == address }

    override fun connect(address: String, port: Int, pin: String, listener: WireListener): WireSocket {
        attempts += address
        val conn = Conn(address, listener)
        when (val mode = modes[address] ?: defaultMode) {
            is Mode.Unreachable -> {
                conn.closed = true
                exec.schedule({ listener.onFailure(java.net.ConnectException("unreachable"), false) }, mode.delayMs, TimeUnit.MILLISECONDS)
            }
            Mode.WrongKey -> {
                conn.closed = true
                exec.execute { listener.onFailure(javax.net.ssl.SSLPeerUnverifiedException("pin"), true) }
            }
            Mode.Accept -> {
                conns += conn
                exec.execute {
                    listener.onOpen()
                    listener.onText(helloFrame ?: hello(6))
                }
            }
        }
        return conn
    }

    fun hello(version: Int) =
        """{"type":"hello","protocolVersion":$version,"minClientVersion":6,"pcId":"$pcId","pcName":"$pcName","nonce":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}"""

    fun authOk(id: String) =
        """{"id":"$id","type":"authOk","protocolVersion":6,"pcId":"$pcId","pcName":"$authOkName","appVersion":"1.4.0","appRunning":true,"serverTime":1,""" +
            """"addresses":[${authOkAddresses.joinToString(",") { "\"$it\"" }}],"port":$authOkPort,"features":[${authOkFeatures.joinToString(",") { "\"$it\"" }}]}"""

    fun error(id: String, code: String) = """{"id":"$id","type":"error","protocolVersion":6,"code":"$code","message":"x"}"""

    inner class Conn(val address: String, private val listener: WireListener) : WireSocket {
        val received = CopyOnWriteArrayList<String>()
        @Volatile var closed = false
        @Volatile var phoneCloseCode: Int? = null

        override fun send(text: String): Boolean {
            if (closed) return false
            received += text
            exec.execute { handle(text) }
            return true
        }

        override fun close(code: Int, reason: String) {
            if (closed) return
            closed = true
            phoneCloseCode = code
            exec.execute { listener.onClosed(code, reason) }
        }

        fun push(frame: String) = exec.execute { if (!closed) listener.onText(frame) }

        fun pushBinary(bytes: ByteArray) = exec.execute { if (!closed) listener.onBinary(bytes) }

        fun closeFromPc(code: Int) = exec.execute {
            if (!closed) {
                closed = true
                listener.onClosed(code, "")
            }
        }

        /** Ids and types of requests received so far, in order. */
        fun requests(): List<Pair<String, String>> = received.map {
            val o = json.parseToJsonElement(it).jsonObject
            o["id"]!!.jsonPrimitive.content to o["type"]!!.jsonPrimitive.content
        }

        private fun handle(text: String) {
            if (closed) return
            val body = json.parseToJsonElement(text).jsonObject
            val id = body["id"]?.jsonPrimitive?.content ?: return // notifications (fetchAck) are only recorded
            val type = body["type"]!!.jsonPrimitive.content
            if (type == "auth") {
                when (authMode) {
                    AuthMode.OK -> listener.onText(authOk(id))
                    AuthMode.UNAUTHORIZED_REPLY -> listener.onText(error(id, "unauthorized"))
                    AuthMode.CLOSE_4401 -> {
                        closed = true
                        listener.onClosed(4401, "")
                    }
                }
                return
            }
            val replies = connHandlers[type]?.invoke(this, id, body) ?: handlers[type]?.invoke(id, body)
            replies?.forEach { listener.onText(it) }
        }
    }
}

fun jobJson(id: Long, status: String = "downloading", downloaded: Long = 0) =
    """{"id":$id,"url":"https://e.com/$id","name":"f$id","status":"$status","downloaded":$downloaded,"total":1000,"speed":0,"createdAt":$id}"""

fun jobEvent(seq: Long, event: String, jobId: Long, extra: String = "") =
    """{"type":"event","protocolVersion":6,"event":"$event","seq":$seq,"ts":1,"jobId":$jobId,"status":"downloading"$extra}"""

fun jobObjEvent(seq: Long, event: String, job: String) =
    """{"type":"event","protocolVersion":6,"event":"$event","seq":$seq,"ts":1,"job":$job}"""

suspend fun <T : Any> eventually(timeoutMs: Long = 8_000, what: String = "condition", block: suspend () -> T?): T {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        block()?.let { return it }
        delay(20)
    }
    throw AssertionError("timed out waiting for $what")
}
