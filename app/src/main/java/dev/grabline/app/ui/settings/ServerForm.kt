package dev.grabline.app.ui.settings

import dev.grabline.app.engine.cloud.AccountSecrets
import dev.grabline.app.engine.cloud.CloudAccount
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What the user typed on the server editor. Pure, so it can be tested. */
data class ServerForm(
    val service: String = "sftp",
    val label: String = "",
    val host: String = "",
    val port: String = "",
    val user: String = "",
    val password: String = "",
    val useKey: Boolean = false,
    val privateKey: String = "",
    val passphrase: String = "",
    val endpoint: String = "",
    val region: String = "",
    /** Editing an account that already has a saved private key: an empty key box then means "keep it". */
    val keepKey: Boolean = false,
) {
    enum class Problem { HOST, PORT, ENDPOINT, KEY }

    fun problem(): Problem? = when {
        host.isBlank() || host.any { it.isWhitespace() || it == '/' } -> Problem.HOST
        port.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } != true -> Problem.PORT
        service == "s3" && endpoint.isNotBlank() && endpoint.trim().toHttpUrlOrNull() == null -> Problem.ENDPOINT
        service == "sftp" && useKey && privateKey.isBlank() && !keepKey -> Problem.KEY
        else -> null
    }

    fun account(id: Long): CloudAccount = CloudAccount(
        id = id, service = service, host = host.trim(), port = port.toIntOrNull() ?: 0, username = user.trim(),
        label = label.trim(), endpoint = if (service == "s3") endpoint.trim().trimEnd('/') else "", region = if (service == "s3") region.trim() else "",
    )

    /**
     * The secrets to store, or null to keep the saved ones (nothing was typed).
     * Switching between password and key clears the one that is no longer used.
     */
    fun secrets(): AccountSecrets? {
        val key = service == "sftp" && useKey
        val typed = if (key) privateKey.isNotBlank() || (passphrase.isNotEmpty() && !keepKey) else password.isNotEmpty()
        if (!typed) return null
        return if (key) {
            AccountSecrets(privateKey = privateKey.trim() + "\n", passphrase = passphrase)
        } else {
            AccountSecrets(password = password)
        }
    }

    companion object {
        fun from(a: CloudAccount, hasKey: Boolean) = ServerForm(
            service = a.service, label = a.label, host = a.host, port = if (a.port > 0) a.port.toString() else "", user = a.username,
            useKey = hasKey, endpoint = a.endpoint, region = a.region, keepKey = hasKey,
        )
    }
}
