package com.igorv.picturetuner

import android.app.Activity
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Shows the calibration clips. Flow per test:
 *   [CHECK] -> [PREP] -> INTRO (explanation) -> PATTERN -> QUESTION -> FEEDBACK -> next test
 * Guided mode walks a fixed list and ends with a SUMMARY; free mode lets ◀ ▶ switch tests.
 */
@OptIn(UnstableApi::class)
class PatternActivity : Activity() {

    companion object {
        const val EXTRA_GUIDED = "guided"
        const val EXTRA_TEST = "test"
        private const val HINT_MS = 6000L
        private val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
        private val HELP_KEYS = setOf(KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE)
        private val JUMP_KEYS = setOf(
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
        private val PATTERN_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        ) + OK_KEYS + HELP_KEYS + JUMP_KEYS

        /** Device-check results already shown in this process (format + issue titles). */
        private val checkedThisSession = mutableSetOf<String>()

        /** Remote/keyboard number keys 0-9 -> digit, else null. */
        fun digitOf(code: Int): Int? = when (code) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
            else -> null
        }
        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }

    private enum class Stage { CHECK, PREP, INTRO, PATTERN, QUESTION, FEEDBACK, SUMMARY, ERROR, LEAVE }

    private lateinit var ctx: Ctx
    private var guided = true
    private var tests: List<TestDef> = emptyList()
    private var index = 0
    private var step = 0
    private var steps: List<Step> = emptyList()
    private var stage = Stage.INTRO
    private var loadedUri: String? = null
    /** Tests answered in this session: the guided summary must not show results from an earlier run. */
    private val answered = mutableSetOf<String>()
    /** The current test's pattern has been on screen (a finder's result is only meaningful after that). */
    private var looked = false

    private lateinit var device: DeviceInfo
    /** Set when 4K failed to decode on this device. */
    private var forced1080 = false
    private val use1080 get() = forced1080 || device.output1080
    private var decoderName: String? = null
    private var error: PlaybackException? = null

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var hint: TextView
    private lateinit var card: View
    private lateinit var cardStep: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardBody: TextView
    private lateinit var cardScroll: ScrollView
    private lateinit var cardButtons: LinearLayout
    private lateinit var cardStatus: TextView
    private lateinit var cardKeys: TextView
    /** Last option chosen per test in this session, so re-answering starts on it. */
    private val lastChoice = mutableMapOf<String, Int>()
    private lateinit var cardMore: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }

    private val test get() = tests[index]

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // HDMI hot-plug, a new output mode, or our own 24 Hz switch.
            val was1080 = use1080
            device = DeviceCheck.read(this@PatternActivity)
            if (tests.isNotEmpty() && use1080 != was1080) {
                loadedUri = null
                loadClip()
            }
            updateStatus()
            updateHint()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pattern)
        playerView = findViewById(R.id.player)
        hint = findViewById(R.id.hint)
        card = findViewById(R.id.card)
        cardStep = findViewById(R.id.cardStep)
        cardTitle = findViewById(R.id.cardTitle)
        cardBody = findViewById(R.id.cardBody)
        cardScroll = findViewById(R.id.cardScroll)
        cardButtons = findViewById(R.id.cardButtons)
        cardStatus = findViewById(R.id.cardStatus)
        cardKeys = findViewById(R.id.cardKeys)
        cardMore = findViewById(R.id.cardMore)
        device = DeviceCheck.read(this)

        val manifest = try {
            Manifest.load(this)
        } catch (e: Exception) {
            showFatal("The test screens are missing or damaged. Please reinstall the app.", e)
            return
        }
        ctx = Ctx(Prefs.format(this), Prefs.brand(this), manifest)
        guided = intent.getBooleanExtra(EXTRA_GUIDED, true)
        tests = (if (guided) Catalog.guided(ctx.format) else Catalog.available(ctx.format))
            .filter { manifest.has(it.family(ctx.format, 0), ctx.format) }
            .filter { it.kind != Kind.LADDER || it.stepsFor(ctx).isNotEmpty() }  // a ladder without steps can't be answered
        if (tests.isEmpty()) {
            showFatal("There are no test screens for ${ctx.format.label} in this version of the app.", null)
            return
        }
        index = intent.getStringExtra(EXTRA_TEST)?.let { id -> tests.indexOfFirst { it.id == id } }?.coerceAtLeast(0) ?: 0

        // Restore after the system closed the app in the background.
        var restoredStage: Stage? = null
        if (savedInstanceState != null) {
            index = savedInstanceState.getInt("index", index).coerceIn(0, tests.lastIndex)
            step = savedInstanceState.getInt("step", 0)
            forced1080 = savedInstanceState.getBoolean("forced1080", false)
            restoredStage = savedInstanceState.getString("stage")?.let { runCatching { Stage.valueOf(it) }.getOrNull() }
            savedInstanceState.getStringArrayList("answered")?.let { answered += it }
        }
        enterTest(resetStep = savedInstanceState == null)
        looked = savedInstanceState?.getBoolean("looked") ?: false
        when (restoredStage) {
            null -> start()
            Stage.PATTERN -> showPattern()
            Stage.SUMMARY -> showSummary()
            else -> showIntro()  // question/feedback/check/prep: resume at the explanation
        }
    }

    /** First screen: device warnings if any, then the guided checklist or the explanation. */
    private fun start() {
        val issues = DeviceCheck.issues(device, ctx.format, ctx.brand)
        // Show the device check once per format per app session, not every time a test is opened.
        val key = ctx.format.key + issues.joinToString("|") { it.title }
        if (issues.isNotEmpty() && checkedThisSession.add(key)) showCheck(issues) else afterCheck()
    }

    private fun afterCheck() = if (guided) showPrep() else showIntro()

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
        outState.putInt("step", step)
        outState.putBoolean("forced1080", forced1080)
        outState.putString("stage", stage.name)
        outState.putBoolean("looked", looked)
        outState.putStringArrayList("answered", ArrayList(answered))
    }

    // ------------------------------------------------------------------ playback

    /** The player only exists while visible, so other apps get the hardware video decoder back. */
    private fun createPlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().apply {
            setSeekParameters(SeekParameters.EXACT)
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String,
                    initializedTimestampMs: Long, initializationDurationMs: Long,
                ) {
                    this@PatternActivity.decoderName = decoderName
                    updateStatus()
                }
            })
            addListener(object : Player.Listener {
                override fun onPlayerError(e: PlaybackException) = onPlaybackError(e)
            })
        }
        playerView.player = player
        loadedUri = null
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
        loadedUri = null
    }

    private fun assetFor(variant: Int): String {
        val fam = test.family(ctx.format, variant)
        return "asset:///patterns/$fam${if (use1080) "_1080" else ""}_${ctx.format.key}.mp4"
    }

    private fun enterTest(resetStep: Boolean) {
        steps = when (test.kind) {
            Kind.LADDER -> test.stepsFor(ctx)
            Kind.VARIANTS -> test.variants.mapIndexed { i, s -> Step(i.toDouble(), s) }
            Kind.STATIC -> emptyList()
        }
        step = if (resetStep) test.startStep(steps) else step
        step = step.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
        looked = false
        error = null
        setRefreshRate(if (test.switchTo24Hz) 24000f / 1001f else null)
        loadClip()
    }

    private fun loadClip() {
        val p = player ?: return  // not visible; onStart loads it
        val uri = assetFor(if (test.kind == Kind.VARIANTS) step else 0)
        if (uri != loadedUri) {
            loadedUri = uri
            error = null
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
        }
        if (test.kind == Kind.LADDER) {
            // Each frame of a ladder clip is one step: pause on the exact frame.
            p.repeatMode = Player.REPEAT_MODE_OFF
            p.playWhenReady = false
            val fps = ctx.manifest.fps(test.family(ctx.format, 0))
            p.seekTo(floor(step * 1000.0 / fps).toLong())
        } else {
            p.repeatMode = Player.REPEAT_MODE_ONE
            p.playWhenReady = true
        }
        updateStatus()
        updateHint()
    }

    private fun onPlaybackError(e: PlaybackException) {
        if (!forced1080 && !device.output1080 && e.errorCode in DECODER_ERRORS) {
            forced1080 = true  // this device can't decode the 4K clips: use the 1080p ones
            loadedUri = null
            loadClip()
            return
        }
        error = e
        // Only interrupt while a pattern is on screen; otherwise showPattern() explains it when asked.
        if (stage == Stage.PATTERN) showError(e) else updateStatus()
    }

    /** Ask the display for a refresh rate (motion test); null = back to the default mode. */
    private fun setRefreshRate(target: Float?) {
        @Suppress("DEPRECATION") val display = windowManager.defaultDisplay ?: return
        val modeId = if (target == null) 0 else {
            val cur = display.mode
            display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { abs(it.refreshRate - target) }
                ?.takeIf { abs(it.refreshRate - target) < 0.1f }?.modeId ?: 0
        }
        val lp = window.attributes
        if (lp.preferredDisplayModeId != modeId) {
            lp.preferredDisplayModeId = modeId
            window.attributes = lp
        }
    }

    // ------------------------------------------------------------------ stages / cards

    private fun showCheck(issues: List<Issue>) {
        stage = Stage.CHECK
        val sb = SpannableStringBuilder()
        issues.forEach { i ->
            if (sb.isNotEmpty()) sb.append("\n\n")
            val s = sb.length
            sb.append(if (i.warning) "⚠  " else "ℹ  ").append(i.title)
            sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(if (i.warning) 0xFFE8C76A.toInt() else 0xFF8FB4F0.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n").append(i.text)
        }
        val warn = issues.any { it.warning }
        showCard("${ctx.format.label.uppercase()} · DEVICE CHECK", if (warn) "Check this first" else "Good to know", sb, listOf(
            (if (warn) "Continue anyway" else "Continue") to { afterCheck() },
            "Check again" to {
                device = DeviceCheck.read(this)
                DeviceCheck.issues(device, ctx.format, ctx.brand).let { if (it.isNotEmpty()) showCheck(it) else afterCheck() }
            },
            "Back to the menu" to { finish() },
        ))
    }

    private fun showPrep() {
        stage = Stage.PREP
        val c = ctx
        val sb = SpannableStringBuilder()
        bullet(sb, "Use the room light you normally watch in. A dim room works best.")
        bullet(sb, "Let the TV warm up for about 10 minutes.")
        bullet(sb, "Choose the most accurate picture mode: ${Text.name(c.brand, Setting.MODE)}. Your TV keeps separate settings for HDR10, Dolby Vision and SDR — you're tuning ${c.format.label} now.")
        bullet(sb, "Turn off ${Text.name(c.brand, Setting.ENERGY)}: they change brightness by themselves.")
        bullet(sb, "When a test screen is showing, check that your TV says ${c.format.label} (most TVs show a badge, or press the remote's info button).")
        if (c.brand == Brand.LG) {
            bullet(sb, "LG OLEDs slowly dim still pictures after a minute or two to protect the screen. Judge each pattern soon after it appears.")
        }
        showCard("Guided calibration · ${c.format.label}", "Before you start", sb,
            listOf("Start" to { showIntro() }))
    }

    private fun showIntro() {
        stage = Stage.INTRO
        val sb = SpannableStringBuilder()
        section(sb, "What this checks", test.what(ctx))
        section(sb, "Your goal", test.goal(ctx))
        section(sb, "How to adjust", test.how(ctx))
        test.note(ctx)?.let { section(sb, "Good to know", it) }
        val buttons = mutableListOf<Pair<String, () -> Unit>>("Show the test screen" to { showPattern() })
        // Finder (▲▼) results use the current step, so only offer the shortcut once the pattern was shown.
        if (!guided && (looked || test.answer(ctx) is Answer.Choice)) buttons += "I've looked: check my result" to { onLooked() }
        val keys = mutableListOf("OK select")
        if (tests.size > 1) keys += if (guided) "◀ ▶ previous / next step" else "◀ ▶ previous / next test"
        keys += if (guided) "BACK leave the guide" else "BACK menu"
        showCard(stepLabel(), test.title, sb, buttons, keys = keys)
    }

    /** Leaving the guide by accident would lose the run, so BACK asks first. */
    private fun showLeave() {
        stage = Stage.LEAVE
        showCard(stepLabel(), "Leave the guided calibration?", SpannableStringBuilder(
            "Your answers so far are saved and shown on the home screen. You can start the guide again any time."),
            listOf("Keep going" to { showIntro() }, "Leave" to { finish() }),
            keys = listOf("OK select", "BACK keep going"))
    }

    /** Move to another test. Guided: show its explanation. Free choice: go straight to its pattern. */
    private fun goTo(newIndex: Int, toPattern: Boolean) {
        if (newIndex == index || newIndex !in tests.indices) return
        index = newIndex
        enterTest(resetStep = true)
        if (toPattern) showPattern() else showIntro()
    }

    private fun neighbour(delta: Int): Int =
        if (guided) (index + delta).coerceIn(0, tests.lastIndex) else (index + delta + tests.size) % tests.size

    /** Number keys answer logo questions directly: 1-4 = that logo, 0 = none. */
    private fun answerByDigit(d: Int): Boolean {
        val a = test.answer(ctx) as? Answer.Choice ?: return false
        val i = a.options.indexOfFirst { it.label == (if (d == 0) "None" else "Logo $d") }
        if (i < 0) return false
        lastChoice[test.id] = i
        showFeedback(a.options[i].feedback)
        return true
    }

    private fun digitAnswers(): Boolean =
        (test.answer(ctx) as? Answer.Choice)?.options?.any { it.label.startsWith("Logo ") } == true

    private fun showPattern() {
        if (error != null) {  // don't show an empty screen; explain instead
            showError(error!!)
            return
        }
        stage = Stage.PATTERN
        looked = true
        card.visibility = View.GONE
        updateHint()
    }

    private fun onLooked() {
        when (val a = test.answer(ctx)) {
            is Answer.Slider -> showFeedback(a.evaluate(step, steps))
            is Answer.Choice -> showQuestion(a)
        }
    }

    private fun showQuestion(a: Answer.Choice) {
        stage = Stage.QUESTION
        val buttons = a.options.mapIndexed { i, o -> o.label to { lastChoice[test.id] = i; showFeedback(o.feedback) } } +
            ("◀  Look at the screen again" to { showPattern() })
        val keys = mutableListOf("OK select")
        if (digitAnswers()) keys.add(0, "1–4 logo, 0 none")
        keys += "BACK look again"
        showCard(stepLabel(), test.title, SpannableStringBuilder(a.question), buttons,
            focus = lastChoice[test.id] ?: 0, keys = keys)
    }

    private fun showFeedback(fb: Feedback) {
        stage = Stage.FEEDBACK
        Prefs.saveResult(this, ctx.format, test.id, fb)
        answered += test.id
        val sb = SpannableStringBuilder()
        val start = sb.length
        sb.append("${fb.verdict.symbol}  ${fb.headline}")
        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(verdictColor(fb.verdict)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (fb.advice.isNotEmpty()) sb.append("\n\n").append(fb.advice)
        if (fb.verdict == Verdict.ADJUST || fb.verdict == Verdict.OK) sb.append("\n\nAfter changing a setting, look at the screen again to check.")
        if (use1080 && test.id == "sharpness") sb.append("\n\nNote: the 1080p version of this test is playing, so single-pixel details may be resized on a 4K TV.")
        val buttons = mutableListOf<Pair<String, () -> Unit>>("◀  Look at the screen again" to { showPattern() })
        if (guided) {
            buttons += (if (index < tests.lastIndex) "Next: ${tests[index + 1].title}  ▶" else "Finish  ▶") to { next() }
        } else {
            if (index < tests.lastIndex) buttons += "Next test: ${tests[index + 1].title}  ▶" to { next() }
            buttons += "Back to the menu" to { finish() }
        }
        // Good result: the natural next action is moving on. Otherwise: go back and adjust.
        val moveOn = (fb.verdict == Verdict.GOOD || fb.verdict == Verdict.INFO) && buttons.size > 1
        showCard(stepLabel(), test.title, sb, buttons, focus = if (moveOn) 1 else 0,
            keys = listOf("OK select", "BACK look again"))
    }

    private fun showError(e: PlaybackException) {
        stage = Stage.ERROR
        val decoder = e.errorCode in DECODER_ERRORS
        val sb = SpannableStringBuilder()
        sb.append(
            if (decoder) "This device couldn't play the test video: its video decoder doesn't support this format" +
                (if (ctx.format != Format.SDR) " (10-bit ${ctx.format.label})" else " (10-bit video)") + ". Results can't be checked on this device."
            else "The test video couldn't be played.",
        )
        sb.append("\n\nTry again, or skip this test.")
        val s = sb.length
        sb.append("\n\nDetails: ${e.errorCodeName}${e.cause?.message?.let { " — $it" } ?: ""}")
        sb.setSpan(ForegroundColorSpan(0xFF6E7480.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val buttons = mutableListOf<Pair<String, () -> Unit>>(
            "Try again" to { error = null; forced1080 = false; loadedUri = null; loadClip(); showIntro() },
        )
        if (index < tests.lastIndex) buttons += "Skip to: ${tests[index + 1].title}  ▶" to { next() }
        else if (guided) buttons += "Finish  ▶" to { next() }
        buttons += "Back to the menu" to { finish() }
        showCard(stepLabel(), "${test.title}: video problem", sb, buttons)
    }

    /** For problems before any test can run (missing assets, nothing to show). */
    private fun showFatal(message: String, e: Exception?) {
        stage = Stage.ERROR
        val sb = SpannableStringBuilder(message)
        e?.let { sb.append("\n\nDetails: ${it.javaClass.simpleName}: ${it.message}") }
        showCard("PICTURE TUNER", "Something went wrong", sb, listOf("Back to the menu" to { finish() }))
    }

    private fun next() {
        if (index < tests.lastIndex) {
            index++
            enterTest(resetStep = true)
            showIntro()
        } else {
            showSummary()
        }
    }

    private fun showSummary() {
        stage = Stage.SUMMARY
        setRefreshRate(null)
        val sb = SpannableStringBuilder()
        tests.forEach { t ->
            val r = if (t.id in answered) Prefs.result(this, ctx.format, t.id) else null
            val start = sb.length
            sb.append(r?.first?.symbol ?: "–").append("  ")
            sb.setSpan(ForegroundColorSpan(verdictColor(r?.first)), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val b = sb.length
            sb.append(t.title)
            sb.setSpan(StyleSpan(Typeface.BOLD), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(": ").append(r?.second ?: "skipped").append("\n")
        }
        sb.append("\nYour TV remembers these settings per picture mode and format. To tune another format, pick it on the home screen and run the guide again.")
        showCard("Guided calibration · ${ctx.format.label}", "All done!", sb, listOf(
            "Back to the home screen" to { finish() },
            "Start again" to { index = 0; answered.clear(); enterTest(true); showIntro() },
        ))
    }

    private fun showCard(
        step: String, title: String, body: CharSequence, buttons: List<Pair<String, () -> Unit>>,
        focus: Int = 0, keys: List<String> = listOf("▲ ▼ choose", "OK select", "BACK menu"),
    ) {
        // "▲ ▼ choose" only when there is something to choose between.
        val choose = "▲ ▼ choose"
        val legend = keys.filter { it != choose }.toMutableList()
        if (buttons.size > 1) legend.add(0, choose)
        cardKeys.text = legend.joinToString("   ·   ")
        cardStep.text = step
        cardTitle.text = title
        cardBody.text = body
        cardScroll.scrollTo(0, 0)
        cardButtons.removeAllViews()
        buttons.forEachIndexed { i, (label, action) ->
            val b = Button(this, null, 0, R.style.TvButton)
            b.text = label
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            if (i > 0) lp.topMargin = (6 * resources.displayMetrics.density).toInt()
            cardButtons.addView(b, lp)
        }
        // Keep the card on screen: cap the body height to what the rest of the card leaves free (and at most
        // half the screen). If the text is longer, the body becomes focusable so ▲ from the buttons scrolls it,
        // and a cue says there's more.
        val density = resources.displayMetrics.density
        updateStatus()
        cardMore.visibility = View.VISIBLE  // count the cue while measuring the rest of the card
        cardScroll.layoutParams = cardScroll.layoutParams.apply { height = 0 }
        card.measure(
            View.MeasureSpec.makeMeasureSpec(card.layoutParams.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val screenH = resources.displayMetrics.heightPixels
        // 48 dp spare: top/bottom margin, plus room for the status line to gain a line later (decoder name).
        val maxBody = minOf((screenH * 0.50).toInt(), screenH - card.measuredHeight - (48 * density).toInt())
            .coerceAtLeast((60 * density).toInt())
        cardBody.measure(
            View.MeasureSpec.makeMeasureSpec((624 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val overflow = cardBody.measuredHeight > maxBody
        cardScroll.layoutParams = cardScroll.layoutParams.apply {
            height = if (overflow) maxBody else LinearLayout.LayoutParams.WRAP_CONTENT
        }
        cardScroll.isFocusable = overflow
        cardMore.visibility = if (overflow) View.VISIBLE else View.GONE
        card.visibility = View.VISIBLE
        hint.alpha = 0f
        cardButtons.post { (cardButtons.getChildAt(focus) ?: cardButtons.getChildAt(0))?.requestFocus() }
    }

    private fun stepLabel(): String =
        if (guided) "STEP ${index + 1} OF ${tests.size} · ${ctx.format.label.uppercase()}"
        else "${ctx.format.label.uppercase()} · FREE CHOICE"

    private fun section(sb: SpannableStringBuilder, head: String, text: String) {
        if (sb.isNotEmpty()) sb.append("\n\n")
        val s = sb.length
        sb.append(head.uppercase())
        sb.setSpan(StyleSpan(Typeface.BOLD), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(0xFF8FB4F0.toInt()), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("\n").append(text)
    }

    private fun bullet(sb: SpannableStringBuilder, text: String) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append("•  ").append(text)
    }

    private fun verdictColor(v: Verdict?): Int = when (v) {
        Verdict.GOOD -> 0xFF6FD08C.toInt()
        Verdict.OK -> 0xFFE8C76A.toInt()
        Verdict.ADJUST -> 0xFFF07C6C.toInt()
        Verdict.INFO -> 0xFF8FB4F0.toInt()
        null -> 0xFF8E94A0.toInt()
    }

    // ------------------------------------------------------------------ hint + status

    /** Motion test: the switch to 24 Hz is a request; the player may refuse or still be switching. */
    private fun refreshWarning(): String? =
        if (tests.isNotEmpty() && test.switchTo24Hz && stage != Stage.SUMMARY && !device.at24Hz())  // summary: back at the default rate
            String.format(Locale.US, "⚠ Screen is at %.2f Hz, not 24 Hz: motion may stutter (that's the player, not your TV)", device.refreshHz)
        else null

    private fun updateHint() {
        if (stage != Stage.PATTERN) return
        val parts = mutableListOf(if (guided) "${index + 1}/${tests.size}  ${test.title}" else test.title)
        if (steps.isNotEmpty()) parts += "▲▼ ${steps[step].label}  (${step + 1}/${steps.size})"
        parts += if (test.answer(ctx) is Answer.Slider) "OK = this is my answer" else "OK = answer"
        if (digitAnswers()) parts += "1–4 = logo, 0 = none"
        parts += "BACK = help"
        if (tests.size > 1) parts += if (guided) "◀▶ steps" else "◀▶ tests"
        hint.text = parts.joinToString("   ·   ") + (refreshWarning()?.let { "\n$it" } ?: "")
        hint.animate().cancel()
        hint.alpha = 1f
        handler.removeCallbacks(hideHint)
        handler.postDelayed(hideHint, HINT_MS)
    }

    private fun updateStatus() {
        if (!::ctx.isInitialized) return
        val parts = mutableListOf<String>()
        parts += if (use1080) "1080p clip" else "4K clip"
        parts += "TV reports: ${device.hdr.joinToString(", ").ifEmpty { "no HDR" }}"
        if (device.outputWidth > 0) parts += String.format(Locale.US, "output %d×%d @ %.3f Hz", device.outputWidth, device.outputHeight, device.refreshHz)
        decoderName?.let { parts += "decoder $it" }
        if (ctx.format == Format.DV && !device.hasDvDecoder) parts += "⚠ no Dolby Vision decoder: plays as HDR10"
        refreshWarning()?.let { parts += it }
        error?.let { parts += "error: ${it.errorCodeName}" }
        cardStatus.text = parts.joinToString("  ·  ")
    }

    // ------------------------------------------------------------------ keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        // Gamepad B / keyboard Esc act like BACK (consuming them below would block the system's BACK fallback).
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        val digit = digitOf(code)
        when (stage) {
            Stage.INTRO -> if (tests.size > 1 && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // Card buttons are stacked vertically, so ◀ ▶ are free for switching tests.
                // One test per press: holding ◀ ▶ would race through clips (and 24 Hz mode switches).
                if (down && event.repeatCount == 0) goTo(neighbour(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1), toPattern = false)
                return true
            }
            Stage.QUESTION -> if (digit != null && digitAnswers()) {
                if (down && event.repeatCount == 0) answerByDigit(digit)
                return true
            }
            else -> {}
        }
        if (stage != Stage.PATTERN) return super.dispatchKeyEvent(event)
        if (digit != null && digitAnswers()) {
            if (down && event.repeatCount == 0) answerByDigit(digit)
            return true
        }
        // Anything else (volume, mute, ...) goes to the system: the TV volume must keep working on a test screen.
        if (code !in PATTERN_KEYS) return super.dispatchKeyEvent(event)
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> moveStep(if (code == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> moveStep(5)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> moveStep(-5)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) {
                val n = neighbour(if (code == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                if (n != index) goTo(n, toPattern = !guided) else updateHint()
            }
            in HELP_KEYS -> showIntro()
            else -> if (event.repeatCount == 0) onLooked()  // OK / Enter / gamepad A
        }
        return true
    }

    private fun moveStep(delta: Int) {
        if (steps.isNotEmpty()) {
            val ns = (step + delta).coerceIn(0, steps.lastIndex)
            if (ns != step) {
                step = ns
                loadClip()
            }
        }
        updateHint()
    }

    private fun onBack() {
        when (stage) {
            Stage.PATTERN -> showIntro()
            Stage.QUESTION, Stage.FEEDBACK -> showPattern()
            Stage.LEAVE -> showIntro()
            Stage.INTRO -> if (guided) showLeave() else finish()
            Stage.CHECK, Stage.PREP, Stage.SUMMARY, Stage.ERROR -> finish()
        }
    }

    override fun onStart() {
        super.onStart()
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)
        device = DeviceCheck.read(this)
        if (tests.isEmpty()) return
        createPlayer()
        if (test.switchTo24Hz && stage != Stage.SUMMARY) setRefreshRate(24000f / 1001f)
        loadClip()
    }

    override fun onStop() {
        super.onStop()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        releasePlayer()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
