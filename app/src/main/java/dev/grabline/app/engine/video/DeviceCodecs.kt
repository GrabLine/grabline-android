package dev.grabline.app.engine.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Which video codecs this phone decodes in hardware, for yt-dlp's format sort. */
object DeviceCodecs {
    /** "vp9" or "h264" when the phone lacks a hardware decoder for something better; null for no limit. */
    val preferred: String? by lazy {
        val hw = runCatching { hardwareDecoderTypes() }.getOrDefault(emptySet())
        when {
            "video/av01" in hw -> null
            "video/x-vnd.on2.vp9" in hw -> "vp9"
            else -> "h264"
        }
    }

    private fun hardwareDecoderTypes(): Set<String> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && isHardware(it) }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
}
