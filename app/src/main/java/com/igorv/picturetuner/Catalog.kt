package com.igorv.picturetuner

import java.util.Locale

/** TV settings the advice refers to. */
enum class Setting { BLACK, CONTRAST, TONE, PEAK, COLOR, GAMUT, SHARPNESS, FIT, MOTION, GRADATION, RANGE, MODE, ENERGY }

object Text {
    fun nits(v: Double): String = when {
        v < 0.01 -> String.format(Locale.US, "%.4f nits", v)
        v < 1 -> String.format(Locale.US, "%.3f nits", v)
        v < 10 -> String.format(Locale.US, "%.2f nits", v)
        v < 100 -> String.format(Locale.US, "%.1f nits", v)
        else -> String.format(Locale.US, "%,.0f nits", v)
    }

    /** Menu names. Only names we are confident about; wording can differ slightly per model year. */
    fun name(brand: Brand, s: Setting): String = when (brand) {
        Brand.LG -> when (s) {
            Setting.BLACK -> "Adjust Black Level"
            Setting.CONTRAST -> "Adjust Contrast"
            Setting.TONE -> "Dynamic Tone Mapping"
            Setting.PEAK -> "Peak Brightness"
            Setting.COLOR -> "Color Depth"
            Setting.GAMUT -> "Color Gamut"
            Setting.SHARPNESS -> "Adjust Sharpness"
            Setting.FIT -> "Just Scan (Aspect Ratio settings)"
            Setting.MOTION -> "TruMotion"
            Setting.GRADATION -> "Smooth Gradation"
            Setting.RANGE -> "Video Range"
            Setting.MODE -> "Filmmaker Mode (or Cinema)"
            Setting.ENERGY -> "Energy Saving and the AI brightness / AI picture options"
        }
        Brand.SAMSUNG -> when (s) {
            Setting.BLACK -> "Shadow Detail"
            Setting.CONTRAST -> "Contrast"
            Setting.TONE -> "HDR Tone Mapping"
            Setting.PEAK -> "Brightness (backlight)"
            Setting.COLOR -> "Color"
            Setting.GAMUT -> "Color Space"
            Setting.SHARPNESS -> "Sharpness"
            Setting.FIT -> "Fit to Screen (Picture Size settings)"
            Setting.MOTION -> "Picture Clarity Settings"
            Setting.GRADATION -> "gradient smoothing (if your TV has it)"
            Setting.RANGE -> "HDMI Black Level"
            Setting.MODE -> "Filmmaker Mode (or Movie)"
            Setting.ENERGY -> "Eco / power saving options and Adaptive Picture"
        }
        Brand.SONY -> when (s) {
            Setting.BLACK -> "Black level"
            Setting.CONTRAST -> "Contrast"
            Setting.TONE -> "HDR tone mapping"
            Setting.PEAK -> "Peak luminance"
            Setting.COLOR -> "Color"
            Setting.GAMUT -> "Color space"
            Setting.SHARPNESS -> "Sharpness and Reality Creation"
            Setting.FIT -> "Display area: Full pixel"
            Setting.MOTION -> "Motionflow"
            Setting.GRADATION -> "Smooth gradation"
            Setting.RANGE -> "HDMI video range"
            Setting.MODE -> "Professional or Custom"
            Setting.ENERGY -> "Power saving and the light sensor"
        }
        Brand.OTHER -> when (s) {
            Setting.BLACK -> "Brightness (sometimes called Black level)"
            Setting.CONTRAST -> "Contrast"
            Setting.TONE -> "Tone mapping (often 'Dynamic tone mapping' or 'HDR tone mapping')"
            Setting.PEAK -> "Backlight or peak brightness"
            Setting.COLOR -> "Color (saturation)"
            Setting.GAMUT -> "Color gamut / color space"
            Setting.SHARPNESS -> "Sharpness"
            Setting.FIT -> "the 'Just scan', 'Fit to screen' or 'Full pixel' picture size"
            Setting.MOTION -> "Motion smoothing (MEMC)"
            Setting.GRADATION -> "Smooth gradation / gradient smoothing"
            Setting.RANGE -> "HDMI range / black level (Auto or Limited)"
            Setting.MODE -> "Filmmaker, Cinema or Movie mode"
            Setting.ENERGY -> "energy saving and automatic brightness"
        }
    }

    fun toneAdvice(c: Ctx): String {
        if (c.format == Format.DV) {
            return "In Dolby Vision the TV maps highlights itself. Try another Dolby Vision picture mode (the most accurate one is usually called Cinema or Filmmaker) and make sure ${n(c, Setting.CONTRAST)} is not above its default."
        }
        return when (c.brand) {
            Brand.LG -> "Set Dynamic Tone Mapping to On (keeps more bright detail) and Peak Brightness to High."
            Brand.SAMSUNG -> "Set HDR Tone Mapping to Active."
            Brand.SONY -> "Set HDR tone mapping to 'Gradation preferred'."
            Brand.OTHER -> "Turn on tone mapping (often called 'Dynamic tone mapping') if your TV has it."
        }
    }

    fun hgigAdvice(c: Ctx): String = when (c.brand) {
        Brand.LG -> "set Dynamic Tone Mapping to HGIG"
        Brand.SAMSUNG -> "use Game Mode with HDR Tone Mapping set to HGIG"
        Brand.SONY -> "set HDR tone mapping to Off"
        Brand.OTHER -> "set tone mapping to HGIG or Off, if your TV has that option"
    }

    fun motionOff(c: Ctx): String = when (c.brand) {
        Brand.LG -> "set TruMotion to Off."
        Brand.SAMSUNG -> "set Picture Clarity Settings to Off."
        Brand.SONY -> "set Motionflow to Off."
        Brand.OTHER -> "turn off motion smoothing (MEMC)."
    }

    fun motionLight(c: Ctx): String = when (c.brand) {
        Brand.LG -> "TruMotion → Cinematic Movement"
        Brand.SAMSUNG -> "Picture Clarity Settings → Custom with a low Judder Reduction"
        Brand.SONY -> "Motionflow → True Cinema"
        Brand.OTHER -> "the lightest motion smoothing setting"
    }

    /** Banding fixes. Smooth-gradation controls REDUCE banding, so the advice is to turn them on, not off. */
    fun gradationAdvice(c: Ctx): String = when (c.brand) {
        Brand.LG -> "Set “Smooth Gradation” to Low (Medium if stripes remain), and turn off “Auto Dynamic Contrast”."
        Brand.SAMSUNG -> "Turn off “Contrast Enhancer” (dynamic contrast)."
        Brand.SONY -> "Set “Smooth gradation” to Low (Medium if stripes remain), and turn off “Adv. contrast enhancer”."
        Brand.OTHER -> "Turn on gradient smoothing (often called 'Smooth gradation') if your TV has it, and turn off dynamic contrast."
    }

    fun gamutAuto(c: Ctx): String = when (c.brand) {
        Brand.LG -> "Set Color Gamut to Auto Detect."
        Brand.SAMSUNG -> "Set Color Space to Auto."
        Brand.SONY -> "Set Live Color to Off."
        Brand.OTHER -> "Set the color gamut / color space to Auto (or the standard option)."
    }

    /** Name for use inside a sentence: real menu names are quoted. */
    fun n(c: Ctx, s: Setting) = if (c.brand == Brand.OTHER) name(c.brand, s) else "“${name(c.brand, s)}”"
}

object Catalog {
    private val HDR = setOf(Format.HDR10, Format.DV)
    private val ALL = setOf(Format.HDR10, Format.DV, Format.SDR)
    private fun n(c: Ctx, s: Setting) = Text.n(c, s)
    private fun logoLine(c: Ctx, family: String): String {
        val logos = c.manifest.logos(family, c.format)
        if (logos.isEmpty()) return ""
        return logos.mapIndexed { i, v ->
            "logo ${i + 1} = " + when {
                v == null || v < 0 -> "below black"
                c.format == Format.SDR && family == "black" -> "+${v.toInt()} above black"
                c.format == Format.SDR -> "level ${v.toInt()}"
                else -> Text.nits(v)
            }
        }.joinToString(", ")
    }

    private fun highestVisible(question: String, map: (Int) -> Feedback) = Answer.Choice(
        question,
        listOf(4, 3, 2, 1, 0).map { k -> Option(if (k == 0) "None" else "Logo $k", map(k)) },
    )

    val tests: List<TestDef> = listOf(
        // ------------------------------------------------------------------ black level
        TestDef(
            id = "black", title = "Black level", oneLiner = "Are blacks truly black, without hiding shadow detail?",
            formats = ALL, kind = Kind.STATIC, guided = true, family = { _, _ -> "black" },
            what = { c ->
                "Dark scenes need black to be really black, but the darkest details should still show. " +
                    "There are four logos on a black background. Logo 1 is 'blacker than black': a signal that should " +
                    "never be visible. Logo 2 is just above black. (${logoLine(c, "black")})"
            },
            goal = { "Logo 1 invisible. Logo 2 barely visible. Logos 3 and 4 clearly visible." },
            how = { c ->
                "Setting to use: ${n(c, Setting.BLACK)}. Higher makes shadows brighter, lower makes them darker. Use small steps — " +
                    "most TVs are already right at the default setting."
            },
            note = { c ->
                "Judge it in the room light you normally watch in. On LCD TVs logo 2 may stay hidden even when " +
                    "everything is right; then aim for logo 3 barely visible. Some players and TVs block 'blacker than black' " +
                    "completely, so logo 1 may never appear — then judge by logo 2 alone." +
                    (if (c.format == Format.SDR) "" else " Tone-mapping settings can also change dark details: if you change them later, run this test again.")
            },
            answer = { c ->
                Answer.Choice(
                    "What is the LOWEST-numbered logo you can see?",
                    listOf(
                        Option("Logo 1", Feedback(Verdict.ADJUST, "Blacks are too bright (grayish).",
                            "Lower ${n(c, Setting.BLACK)} by 1–2 steps and look again. If logo 1 stays visible even at low settings, set ${n(c, Setting.RANGE)} to Auto.")),
                        Option("Logo 2", Feedback(Verdict.GOOD, "Perfect: black is black and shadow detail is kept.",
                            "Logo 2 should be faint. If it looks as clear as logo 3, lower ${n(c, Setting.BLACK)} 1 step, as long as logo 2 stays faintly visible.")),
                        Option("Logo 3", Feedback(Verdict.OK, "Close. The very darkest details are hidden.",
                            "On an OLED, raise ${n(c, Setting.BLACK)} by 1 step and check that logo 1 stays invisible. If logo 1 appears, go back: your current setting is the best. On LCD TVs this result is normal.")),
                        Option("Logo 4", Feedback(Verdict.ADJUST, "Too dark: shadow detail is crushed.",
                            "Raise ${n(c, Setting.BLACK)} 1–2 steps at a time until logo 2 or 3 appears. A darker room also helps.")),
                        Option("None", Feedback(Verdict.ADJUST, "Much too dark, or the room is too bright.",
                            "Raise ${n(c, Setting.BLACK)} until logos 4 and 3 appear, and turn off ${n(c, Setting.ENERGY)}.")),
                    ),
                )
            },
        ),
        // ------------------------------------------------------------------ highlights 1000
        TestDef(
            id = "highlight1000", title = "Bright highlights", oneLiner = "Do bright details like clouds and lamps keep their detail?",
            formats = HDR, kind = Kind.STATIC, guided = true, family = { _, _ -> "highlight1000" },
            what = { c ->
                "Most HDR movies and series are made for up to 1,000 nits (nits measure brightness). Four squares are at that " +
                    "maximum, each with a logo a little darker than the square (${logoLine(c, "highlight1000")}). If your TV " +
                    "'clips', the brightest logos melt into the square, just like detail in a bright sky disappears."
            },
            goal = { "See all four logos. Logo 4 is meant to be faint." },
            how = { c ->
                if (c.format == Format.DV) "Dolby Vision handles this automatically; ${n(c, Setting.CONTRAST)} and the picture mode still matter."
                else "${n(c, Setting.TONE)} and ${n(c, Setting.PEAK)} have the biggest effect; ${n(c, Setting.CONTRAST)} too."
            },
            note = { c -> if (c.brand == Brand.LG) "LG OLEDs slowly dim a still picture after a minute or two. Judge the logos soon after the pattern appears." else null },
            answer = { c ->
                highestVisible("What is the HIGHEST-numbered logo you can still see?") { k ->
                    when (k) {
                        4 -> Feedback(Verdict.GOOD, "Excellent: all highlight detail is visible.")
                        3 -> Feedback(Verdict.OK, "Good. Only the very brightest detail is lost — common, and hard to notice in movies.", Text.toneAdvice(c))
                        else -> Feedback(Verdict.ADJUST, "Bright details are clipped: clouds, lamps and reflections will look flat white.",
                            Text.toneAdvice(c) + " Also try lowering ${n(c, Setting.CONTRAST)} 2–3 steps, and avoid Vivid or Dynamic picture modes.")
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ highlights 4000
        TestDef(
            id = "highlight4000", title = "Very bright highlights", oneLiner = "How your TV handles movies made for 4,000-nit screens.",
            formats = HDR, kind = Kind.STATIC, guided = true, family = { _, _ -> "highlight4000" },
            what = { c ->
                "Some movies (many in Dolby Vision) are made on 4,000-nit screens. No TV at home is that bright, so your TV has " +
                    "to squeeze those highlights into what it can show — that's called tone mapping. Logos: ${logoLine(c, "highlight4000")}, " +
                    "inside 4,000-nit squares."
            },
            goal = { "Logos 1 and 2 visible. Logo 3 may be faint; logo 4 often disappears — normal for nearly every TV." },
            how = { c -> if (c.format == Format.DV) "Dolby Vision tone-maps automatically; the picture mode matters most." else "${n(c, Setting.TONE)} matters most." },
            answer = { c ->
                highestVisible("What is the HIGHEST-numbered logo you can still see?") { k ->
                    when (k) {
                        4, 3 -> Feedback(Verdict.GOOD, "Excellent tone mapping: bright detail is kept.")
                        2 -> Feedback(Verdict.GOOD, "Normal. Your TV compresses the brightest 4,000-nit details, like almost every TV.")
                        else -> Feedback(Verdict.ADJUST, "Bright details are clipped hard.", Text.toneAdvice(c))
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ SDR whites
        TestDef(
            id = "white", title = "Bright whites", oneLiner = "Do white clouds and shirts keep their detail?",
            formats = setOf(Format.SDR), kind = Kind.STATIC, guided = true, family = { _, _ -> "white" },
            what = { c -> "Four squares at full white (level 235) with slightly darker white logos (${logoLine(c, "white")}). If ${n(c, Setting.CONTRAST)} is too high, the brightest logos melt into the white, like detail in clouds or a white shirt disappearing." },
            goal = { "See all four logos. Logo 4 is meant to be faint." },
            how = { c -> "Setting to use: ${n(c, Setting.CONTRAST)}." },
            answer = { c ->
                highestVisible("What is the HIGHEST-numbered logo you can still see?") { k ->
                    when (k) {
                        4 -> Feedback(Verdict.GOOD, "Perfect: all white detail is visible.")
                        3 -> Feedback(Verdict.OK, "Almost. The brightest detail is slightly clipped.", "Lower ${n(c, Setting.CONTRAST)} 1–2 steps.")
                        else -> Feedback(Verdict.ADJUST, "Whites are clipped.", "Lower ${n(c, Setting.CONTRAST)} until logo 4 appears.")
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ peak finder
        TestDef(
            id = "peak", title = "Peak brightness finder", oneLiner = "Find your TV's real brightness limit (handy for games).",
            formats = setOf(Format.HDR10), kind = Kind.LADDER, guided = true, family = { _, _ -> "peak" },
            stepHint = "logo brightness",
            what = {
                "A square at maximum HDR brightness with a logo inside. Use ▲ ▼ to make the logo brighter or darker. When the " +
                    "logo is brighter than your TV can show, it melts into the square. Where that happens is your TV's peak " +
                    "brightness (for a box covering 10% of the screen)."
            },
            goal = { "Press ▲ until the logo disappears, then press ▼ once so it's just visible again. Then press OK." },
            how = { c -> "For a true reading, turn tone mapping off: ${Text.hgigAdvice(c)}. With tone mapping on, the logo may never disappear. Afterwards, switch tone mapping back to what you use for movies." },
            answer = { c ->
                Answer.Slider { i, steps ->
                    val v = steps[i].value
                    when {
                        i == steps.lastIndex -> Feedback(Verdict.INFO, "The logo never disappeared.",
                            "Your TV is compressing highlights (tone mapping), so nothing clips — fine for movies. For a real peak reading, ${Text.hgigAdvice(c)} and try again.")
                        i == 0 -> Feedback(Verdict.INFO, "Even the dimmest step is at the limit (about ${Text.nits(v)}).",
                            "Check that the TV shows an HDR badge and that ${n(c, Setting.CONTRAST)} and ${n(c, Setting.PEAK)} are not turned down.")
                        else -> Feedback(Verdict.INFO, "Your TV shows detail up to about ${Text.nits(v)}.",
                            "Use ${v.toInt()} as the 'peak brightness' or 'max luminance' value in game and console HDR settings, with tone mapping on HGIG or Off. Measured on a box covering 10% of the screen; larger bright areas are dimmer. For movies, switch tone mapping back to your usual setting.")
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ colour
        TestDef(
            id = "color", title = "Color saturation", oneLiner = "Are strong colors rich, but not blotchy?",
            formats = ALL, kind = Kind.LADDER, guided = true, family = { _, _ -> "color" },
            stepHint = "color",
            what = {
                "Four squares in one strong color — use ▲ ▼ to go through red, green, blue, cyan, magenta and yellow. Each logo " +
                    "is the same color, only slightly less intense (logo 4 is the closest). If color is pushed too far, the most " +
                    "intense shades merge, and faces, flowers and skies look blotchy."
            },
            goal = { "In every color: logos 1–3 easy to see, logo 4 faint but visible." },
            how = { c -> "${n(c, Setting.COLOR)} sets how strong colors are. ${Text.gamutAuto(c)}" },
            answer = { c ->
                highestVisible("Going through all six colors: what's the HIGHEST logo you can see in EVERY color?") { k ->
                    when (k) {
                        4 -> Feedback(Verdict.GOOD, "Excellent: even the most intense colors keep their detail.")
                        3 -> Feedback(Verdict.OK, "Good. A tiny loss in the most intense colors, hard to notice.", "If you want, lower ${n(c, Setting.COLOR)} 1 step and look again.")
                        else -> if (c.format == Format.SDR) Feedback(Verdict.ADJUST, "Strong colors are clipping.",
                            "Lower ${n(c, Setting.COLOR)} 2–3 steps. ${Text.gamutAuto(c)} Avoid Vivid mode.")
                        else Feedback(Verdict.ADJUST, "Strong colors are clipping.",
                            "If ${n(c, Setting.COLOR)} is above its default, lower it back. ${Text.gamutAuto(c)} Avoid Vivid mode. " +
                                "If it is already at the default, leave it: these HDR colors are at the edge of what most TVs can show, " +
                                "and lowering it would make everyday colors dull.")
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ gradient
        TestDef(
            id = "gradient", title = "Smooth gradients", oneLiner = "Do skies and shadows fade smoothly, without stripes?",
            formats = ALL, kind = Kind.STATIC, guided = true, family = { _, _ -> "gradient" },
            what = {
                "Five bars fade from dark to bright: gray, a close-up of dark gray, then red, green and blue. Sunsets, skies and " +
                    "dark fog in movies look like this."
            },
            goal = { "Every bar fades smoothly, without stripes, steps or color blotches." },
            how = { c -> "Stripes usually come from picture processing. ${Text.gradationAdvice(c)} Avoid Vivid modes." },
            answer = { c ->
                Answer.Choice(
                    "What do you see?",
                    listOf(
                        Option("Smooth", Feedback(Verdict.GOOD, "Great: gradients are smooth.")),
                        Option("Faint, even steps — only when looking very closely", Feedback(Verdict.GOOD,
                            "That's normal: video has tiny steps right at the limit of what eyes can see.")),
                        Option("Clear stripes or color blotches", Feedback(Verdict.ADJUST, "Your TV adds banding.",
                            "${Text.gradationAdvice(c)} Use ${n(c, Setting.MODE)}.")),
                    ),
                )
            },
        ),
        // ------------------------------------------------------------------ sharpness / fit
        TestDef(
            id = "sharpness", title = "Sharpness & picture fit", oneLiner = "Is the whole picture shown, and are edges clean?",
            formats = ALL, kind = Kind.STATIC, guided = true, family = { _, _ -> "sharpness" },
            what = {
                "Three thin frames run around the screen; the outermost sits exactly on the edge of the picture (the small arrows " +
                    "point at it). In the middle, thin black and white lines sit on gray. The striped boxes left and right " +
                    "have single-pixel stripes."
            },
            goal = { "You can see the outermost frame on all four sides, and the lines have no bright or dark outlines (halos)." },
            how = { c ->
                "If the outer frame is cut off, the TV is zooming in: turn on ${n(c, Setting.FIT)}. If lines have halos, " +
                    "lower ${n(c, Setting.SHARPNESS)} until they're gone."
            },
            answer = { c ->
                Answer.Choice(
                    "What do you see?",
                    listOf(
                        Option("Outer frame on all sides, clean lines", Feedback(Verdict.GOOD, "Perfect: the full picture is shown without added sharpening.")),
                        Option("The outer frame is cut off somewhere", Feedback(Verdict.ADJUST, "The TV is cropping the picture.", "Turn on ${n(c, Setting.FIT)}.")),
                        Option("Lines have bright or dark outlines", Feedback(Verdict.ADJUST, "Sharpness is too high.", "Lower ${n(c, Setting.SHARPNESS)} until the outlines disappear.")),
                        Option("The striped boxes look wavy or patterned", Feedback(Verdict.OK, "The video is being resized somewhere.",
                            "Set your streaming box to output 4K. On a 1080p TV this is expected.")),
                    ),
                )
            },
        ),
        // ------------------------------------------------------------------ motion
        TestDef(
            id = "motion", title = "Motion", oneLiner = "Does movie motion look smooth and natural?",
            formats = ALL, kind = Kind.STATIC, guided = true, family = { _, _ -> "motion" }, switchTo24Hz = true,
            what = {
                "Bars and a logo move across the screen at a steady speed, at 24 frames per second — the rate almost every " +
                    "movie uses. The app asks your player to switch the screen to 24 Hz (the picture may go black for a moment)."
            },
            goal = { "Movement looks steady and even, like in a cinema. A little blur is normal at 24 frames per second." },
            how = { c -> "Motion settings: ${n(c, Setting.MOTION)}." },
            answer = { c ->
                Answer.Choice(
                    "How does the movement look?",
                    listOf(
                        Option("Steady and even", Feedback(Verdict.GOOD, "Great: movies will move the way they were filmed.")),
                        Option("Uneven or jerky: it stutters or hitches", Feedback(Verdict.ADJUST, "The screen isn't running at the movie's frame rate.",
                            "Check the refresh rate shown at the bottom of the explanation. Turn on frame-rate matching on your player (NVIDIA Shield: 'Match content frame rate'), then try again.")),
                        Option("Too smooth: looks like video or a soap opera", Feedback(Verdict.OK, "That's motion smoothing.",
                            "Movie fans usually turn it off: " + Text.motionOff(c))),
                        Option("Even, but fast movement flickers or strobes a bit", Feedback(Verdict.INFO, "That's how 24 frames per second looks on fast screens like OLED.",
                            "If it bothers you, try ${Text.motionLight(c)}.")),
                    ),
                )
            },
        ),
        // ------------------------------------------------------------------ shadow finder (library)
        TestDef(
            id = "shadow", title = "Shadow detail finder", oneLiner = "Find the darkest shade your TV can show.",
            formats = ALL, kind = Kind.LADDER, guided = false, family = { _, _ -> "shadow" },
            stepHint = "logo brightness",
            startStep = { steps -> (steps.size * 0.7).toInt() },  // start clearly visible, user steps down
            what = { "One logo on black. Use ▲ ▼ to make it brighter or darker." },
            goal = { "Press ▼ until the logo disappears, then press ▲ once so it's just visible again. Then press OK. Best done in a dark room." },
            how = { c -> "${n(c, Setting.BLACK)} moves this up or down. Do the Black level test first." },
            answer = { c ->
                Answer.Slider { i, steps ->
                    val v = steps[i].value
                    val sdr = c.format == Format.SDR
                    val raise = "Raise ${n(c, Setting.BLACK)} 1 step and re-check the Black level test (logo 1 must stay invisible)."
                    when {
                        i == 0 -> Feedback(Verdict.GOOD, "Even the darkest step is visible: excellent shadow detail.",
                            "If logo 1 on the Black level screen is visible too, black is slightly raised.")
                        (!sdr && v <= 0.02) || (sdr && v <= 4) -> Feedback(Verdict.GOOD, "Excellent: you can see down to ${steps[i].label}.")
                        (!sdr && v <= 0.06) || (sdr && v <= 8) -> Feedback(Verdict.OK, "Typical for LCD TVs or a lit room (${steps[i].label}).",
                            "On an OLED in a dark room: $raise")
                        else -> Feedback(Verdict.ADJUST, "Shadows are crushed (darkest visible: ${steps[i].label}).", raise)
                    }
                }
            },
        ),
        // ------------------------------------------------------------------ window / ABL (library)
        TestDef(
            id = "window", title = "Auto-dimming", oneLiner = "See how your TV dims large bright areas.",
            formats = HDR, kind = Kind.LADDER, guided = false, family = { _, _ -> "window" },
            stepHint = "box size",
            what = {
                "A bright box (1,000 nits) that you grow from 1% to 100% of the screen with ▲. Most TVs, and every OLED, dim " +
                    "the picture when a large part of the screen is bright, to protect the panel and limit power use."
            },
            goal = { "Nothing to set here — it shows how your TV behaves. Watch the box as you grow it: if it suddenly gets dimmer, that's auto-dimming (ABL)." },
            how = { c -> if (c.brand == Brand.LG) "${n(c, Setting.PEAK)} changes how bright highlights may get. This dimming can't be fully turned off." else "This dimming is built in and can't be fully turned off." },
            answer = { _ ->
                Answer.Choice(
                    "Did the box get dimmer as it grew?",
                    listOf(
                        Option("Yes", Feedback(Verdict.INFO, "That's normal auto-dimming.", "It's why bright snow or desert scenes look less dazzling than small highlights.")),
                        Option("No", Feedback(Verdict.INFO, "Your TV keeps its brightness well, even for large bright areas.")),
                    ),
                )
            },
        ),
        // ------------------------------------------------------------------ metadata (library)
        TestDef(
            id = "metadata", title = "HDR label effect", oneLiner = "See how your TV reacts to a movie's brightness label.",
            formats = setOf(Format.HDR10), kind = Kind.VARIANTS, guided = false,
            family = { _, v -> listOf("metadata1000", "metadata4000", "metadata10000")[v] },
            variants = listOf("labelled 1,000 nits", "labelled 4,000 nits", "labelled 10,000 nits"),
            stepHint = "movie label",
            what = {
                "Every HDR10 movie tells the TV how bright it was mastered: 1,000, 4,000 or 10,000 nits. These three versions " +
                    "show exactly the same picture, only the label differs. Switch with ▲ ▼."
            },
            goal = { "Nothing to set here. Compare: do the two brightest squares (on the right) get darker when the label says 4,000 or 10,000?" },
            how = { c -> "${n(c, Setting.TONE)} decides whether the TV follows the label." },
            answer = { c ->
                Answer.Choice(
                    "Did it get darker with the higher labels?",
                    listOf(
                        Option("Yes", Feedback(Verdict.INFO, "Your TV adjusts its tone mapping to the label.",
                            "Movies mastered for 4,000 nits will look a little darker than 1,000-nit movies. That's expected with ${n(c, Setting.TONE)} off.")),
                        Option("No", Feedback(Verdict.INFO, "Your TV ignores the label and judges the picture itself.",
                            "Typical with ${n(c, Setting.TONE)} on or set to HGIG.")),
                    ),
                )
            },
        ),
    )

    fun byId(id: String) = tests.first { it.id == id }

    fun available(f: Format) = tests.filter { f in it.formats }

    fun guided(f: Format) = tests.filter { it.guided && f in it.formats }
}
