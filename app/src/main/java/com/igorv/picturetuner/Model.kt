package com.igorv.picturetuner

import android.content.Context
import org.json.JSONObject

enum class Format(val key: String, val label: String, val blurb: String) {
    HDR10("hdr10", "HDR10", "Most HDR movies, series, streaming and games."),
    DV("dv", "Dolby Vision", "HDR with scene-by-scene info: Netflix, Disney+, Apple TV+ and many 4K Blu-rays."),
    SDR("sdr", "SDR", "Regular TV, YouTube and older content."),
}

enum class Brand(val label: String) { LG("LG"), SAMSUNG("Samsung"), SONY("Sony"), OTHER("Other") }

enum class Verdict(val symbol: String) { GOOD("✓"), OK("●"), ADJUST("✗"), INFO("ℹ") }

class Feedback(val verdict: Verdict, val headline: String, val advice: String = "")

class Option(val label: String, val feedback: Feedback)

/** Values for one step of an interactive (▲▼) screen. */
class Step(val value: Double, val label: String)

class Ctx(val format: Format, val brand: Brand, val manifest: Manifest)

sealed interface Answer {
    /** Multiple choice after looking at the pattern. */
    class Choice(val question: String, val options: List<Option>) : Answer

    /** The current ▲▼ step is the answer. */
    class Slider(val evaluate: (index: Int, steps: List<Step>) -> Feedback) : Answer
}

enum class Kind { STATIC, LADDER, VARIANTS }

class TestDef(
    val id: String,
    val title: String,
    val oneLiner: String,
    val formats: Set<Format>,
    val kind: Kind,
    val guided: Boolean,
    /** Asset family for a format (+ variant index for VARIANTS). */
    val family: (Format, Int) -> String,
    val variants: List<String> = emptyList(),
    val startStep: (List<Step>) -> Int = { 0 },
    val stepHint: String = "",
    val switchTo24Hz: Boolean = false,
    val what: (Ctx) -> String,
    val goal: (Ctx) -> String,
    val how: (Ctx) -> String,
    val note: (Ctx) -> String? = { null },
    val answer: (Ctx) -> Answer,
) {
    fun stepsFor(ctx: Ctx): List<Step> = ctx.manifest.steps(family(ctx.format, 0), ctx.format)
}

/** Reads assets/patterns/manifest.json written by tools/generate_patterns.py (single source of truth for levels). */
class Manifest(private val root: JSONObject) {
    private fun info(family: String, format: Format): JSONObject? {
        val fam = root.getJSONObject("families").optJSONObject(family) ?: return null
        val infos = fam.getJSONObject("info")
        // Dolby Vision clips share the HDR10 base layer, so they share its info.
        return infos.optJSONObject(if (format == Format.SDR) "sdr" else "hdr10")
    }

    fun fps(family: String): Double {
        val s = root.getJSONObject("families").getJSONObject(family).getString("fps")
        val parts = s.split("/")
        return if (parts.size == 2) parts[0].toDouble() / parts[1].toDouble() else s.toDouble()
    }

    fun has(family: String, format: Format): Boolean {
        val fam = root.getJSONObject("families").optJSONObject(family) ?: return false
        val arr = fam.getJSONArray("formats")
        return (0 until arr.length()).any { arr.getString(it) == format.key }
    }

    fun steps(family: String, format: Format): List<Step> {
        val arr = info(family, format)?.optJSONArray("steps") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val v = arr.get(i)
            when {
                v is String -> Step(i.toDouble(), v)
                family == "window" -> Step((v as Number).toDouble(), "${v}% of the screen")
                format == Format.SDR -> Step((v as Number).toDouble(), "+${v} above black")
                else -> Step((v as Number).toDouble(), Text.nits((v as Number).toDouble()))
            }
        }
    }

    /** Logo levels of a fixed screen, for the explanation (null entries = below black). */
    fun logos(family: String, format: Format): List<Double?> {
        val arr = info(family, format)?.optJSONArray("logos") ?: return emptyList()
        return (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getDouble(it) }
    }

    companion object {
        fun load(context: Context): Manifest =
            Manifest(JSONObject(context.assets.open("patterns/manifest.json").bufferedReader().readText()))
    }
}

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("tuner", Context.MODE_PRIVATE)

    fun format(c: Context) = Format.entries.firstOrNull { it.key == p(c).getString("format", null) } ?: Format.HDR10
    fun setFormat(c: Context, f: Format) = p(c).edit().putString("format", f.key).apply()

    fun brand(c: Context) = Brand.entries.firstOrNull { it.name == p(c).getString("brand", null) } ?: Brand.OTHER
    fun setBrand(c: Context, b: Brand) = p(c).edit().putString("brand", b.name).apply()

    fun saveResult(c: Context, f: Format, testId: String, fb: Feedback) =
        p(c).edit().putString("result_${f.key}_$testId", "${fb.verdict.name}|${fb.headline}").apply()

    fun result(c: Context, f: Format, testId: String): Pair<Verdict, String>? {
        val s = p(c).getString("result_${f.key}_$testId", null) ?: return null
        val parts = s.split("|", limit = 2)
        val v = Verdict.entries.firstOrNull { it.name == parts[0] } ?: return null  // tolerate old/garbled entries
        return v to parts.getOrElse(1) { "" }
    }

}
