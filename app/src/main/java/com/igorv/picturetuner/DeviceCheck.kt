package com.igorv.picturetuner

import android.app.Activity
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import kotlin.math.abs

/** What the device and the connected TV report. Read fresh whenever it may have changed (HDMI events). */
class DeviceInfo(
    val hdr: Set<String>,          // "HDR10", "Dolby Vision", "HDR10+", "HLG" as reported for the current output
    val hasDvDecoder: Boolean,
    val outputWidth: Int,
    val outputHeight: Int,
    val refreshHz: Float,
    val isShield: Boolean,
) {
    /** Pixel-exact patterns need clips that match the output resolution, not just what the decoder can do. */
    val output1080: Boolean get() = outputWidth in 1..3000

    fun at24Hz(): Boolean = abs(refreshHz - 24000f / 1001f) < 0.05f || abs(refreshHz - 24f) < 0.05f
}

class Issue(val warning: Boolean, val title: String, val text: String)

object DeviceCheck {

    private val dvDecoder by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/dolby-vision", ignoreCase = true) }
        }
    }

    fun read(activity: Activity): DeviceInfo {
        @Suppress("DEPRECATION") val display: Display? = activity.windowManager.defaultDisplay
        val mode = display?.mode
        val types: IntArray = when {
            display == null -> IntArray(0)
            Build.VERSION.SDK_INT >= 34 -> mode?.supportedHdrTypes ?: IntArray(0)
            else -> @Suppress("DEPRECATION") (display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0))
        }
        val names = types.toList().mapNotNull {
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
                Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
                else -> null
            }
        }.toSet()
        return DeviceInfo(
            hdr = names,
            hasDvDecoder = dvDecoder,
            outputWidth = mode?.physicalWidth ?: 0,
            outputHeight = mode?.physicalHeight ?: 0,
            refreshHz = mode?.refreshRate ?: 0f,
            isShield = Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true),
        )
    }

    private fun hdmiAdvice(brand: Brand): String = when (brand) {
        Brand.LG -> "On the TV, turn on “HDMI Ultra HD Deep Colour” (spelled “Color” in some regions) for this HDMI input."
        Brand.SAMSUNG -> "On the TV, turn on “Input Signal Plus” for this HDMI input."
        Brand.SONY -> "On the TV, set “HDMI signal format” to Enhanced for this HDMI input."
        Brand.OTHER -> "On the TV, turn on the enhanced / 4K HDR mode for this HDMI input (often called 'Deep Colour' or 'enhanced format')."
    }

    /** Problems that would make results not apply to the chosen format. Warnings ask the user to confirm. */
    fun issues(d: DeviceInfo, format: Format, brand: Brand): List<Issue> {
        val out = mutableListOf<Issue>()
        val label = format.label
        if (format != Format.SDR && d.hdr.isEmpty()) {
            out += Issue(true, "Your TV doesn't report HDR",
                "The TV (or the HDMI connection) tells this device it can't show HDR, so the test screens would be converted " +
                    "to SDR and the results wouldn't apply to your TV's $label settings. ${hdmiAdvice(brand)} Also use a " +
                    "high-speed HDMI cable, and check the box's own display settings allow HDR.")
        } else {
            if (format == Format.DV && "Dolby Vision" !in d.hdr) {
                out += Issue(true, "Your TV doesn't report Dolby Vision",
                    "The box will send these tests as HDR10 instead, so you would be tuning your TV's HDR10 mode, not its " +
                        "Dolby Vision mode. If your TV does support Dolby Vision: ${hdmiAdvice(brand)}")
            }
            if (format == Format.HDR10 && d.hdr.isNotEmpty() && "HDR10" !in d.hdr) {
                out += Issue(true, "Your TV doesn't report HDR10",
                    "The test screens may be converted before they reach the TV, so results may not apply to its HDR10 mode.")
            }
        }
        if (format == Format.DV && !d.hasDvDecoder) {
            out += Issue(true, "This device can't play Dolby Vision",
                "Its video decoder has no Dolby Vision support, so the tests play as HDR10 and you would be tuning your TV's " +
                    "HDR10 mode. To tune Dolby Vision, use a player with Dolby Vision support or your TV's built-in apps.")
        }
        if (d.isShield && format != Format.DV && "Dolby Vision" in d.hdr) {
            out += Issue(false, "NVIDIA Shield: check dynamic range matching",
                "If the Shield is set to always output Dolby Vision, $label tests are shown in a Dolby Vision picture mode. " +
                    "In the Shield's display settings, turn on matching the content's dynamic range. When a test screen " +
                    "is showing, your TV's info should say $label.")
        }
        if (d.output1080) {
            out += Issue(false, "Your box outputs ${d.outputWidth}×${d.outputHeight}",
                "The 1080p versions of the tests are used. That's fine for brightness and color. For the Sharpness test, set " +
                    "the box to 4K output if your TV is 4K.")
        }
        return out
    }
}
