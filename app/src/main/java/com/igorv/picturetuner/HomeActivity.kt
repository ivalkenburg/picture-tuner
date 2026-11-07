package com.igorv.picturetuner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class HomeActivity : Activity() {

    private var manifest: Manifest? = null
    private val formatButtons = mutableMapOf<Format, Button>()
    private val brandButtons = mutableMapOf<Brand, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        manifest = try {
            Manifest.load(this)
        } catch (e: Exception) {
            // Damaged install: say so instead of crashing. Tests stay disabled.
            findViewById<TextView>(R.id.results).text = "The test screens are missing or damaged (${e.javaClass.simpleName}). Please reinstall the app."
            findViewById<Button>(R.id.guided).isEnabled = false
            return
        }

        val formats = findViewById<LinearLayout>(R.id.formats)
        Format.entries.forEach { f ->
            formatButtons[f] = chip(formats, f.label) { Prefs.setFormat(this, f); refresh() }
        }
        val brands = findViewById<LinearLayout>(R.id.brands)
        Brand.entries.forEach { b ->
            brandButtons[b] = chip(brands, b.label) { Prefs.setBrand(this, b); refresh() }
        }
        findViewById<Button>(R.id.guided).setOnClickListener {
            lastTestId = null
            startActivity(Intent(this, PatternActivity::class.java).putExtra(PatternActivity.EXTRA_GUIDED, true))
        }
    }

    private var lastTestId: String? = null
    private val testButtons = mutableMapOf<String, Button>()
    /** Set on resume; the focus is placed once, so a passing system overlay doesn't move it back later. */
    private var placeFocus = false

    override fun onResume() {
        super.onResume()
        if (manifest == null) return
        refresh()
        placeFocus = true
        focusTarget()
    }

    /**
     * Focus the guided button (or the test the user came from). Done when the window really has focus:
     * a timer-based request can lose the race, and the first key press then lands on the HDR10 button,
     * where ▶ + OK silently switches the format.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && manifest != null && placeFocus) focusTarget()
    }

    private fun focusTarget() {
        val target = lastTestId?.let { testButtons[it] }?.takeIf { it.isFocusable } ?: findViewById<Button>(R.id.guided)
        if (!target.isFocused) target.requestFocus()
        if (hasWindowFocus()) placeFocus = false
    }

    private fun chip(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this, null, 0, R.style.TvButton)
        b.text = label
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = dp(10)
        parent.addView(b, lp)
        return b
    }

    private fun refresh() {
        val format = Prefs.format(this)
        val brand = Prefs.brand(this)
        formatButtons.forEach { (f, b) -> b.isSelected = f == format }
        brandButtons.forEach { (br, b) -> b.isSelected = br == brand }
        findViewById<TextView>(R.id.formatBlurb).text =
            format.blurb + " Each format has its own picture settings on your TV, so tune each one separately."

        val steps = Catalog.guided(format).size
        findViewById<Button>(R.id.guided).text = "▶  Guided calibration for ${format.label}\n" +
            "     $steps screens, step by step · about ${steps * 2} minutes"

        // saved results for this format
        val lines = Catalog.available(format).mapNotNull { t ->
            Prefs.result(this, format, t.id)?.let { (v, h) -> "${v.symbol}  ${t.title}: $h" }
        }
        findViewById<TextView>(R.id.results).text =
            if (lines.isEmpty()) "No saved ${format.label} results yet." else "Your last ${format.label} results:\n" + lines.joinToString("\n")

        // Explicit D-pad order on the left column (otherwise ▲ can jump to the test list on the right).
        val guidedBtn = findViewById<Button>(R.id.guided)
        val fmtBtn = formatButtons.getValue(format)
        val brandBtn = brandButtons.getValue(brand)
        if (fmtBtn.id == View.NO_ID) (formatButtons.values + brandButtons.values).forEach { it.id = View.generateViewId() }
        formatButtons.values.forEach { it.nextFocusDownId = brandBtn.id; it.nextFocusUpId = it.id }
        brandButtons.values.forEach { it.nextFocusUpId = fmtBtn.id; it.nextFocusDownId = guidedBtn.id }
        guidedBtn.nextFocusUpId = brandBtn.id
        guidedBtn.nextFocusDownId = guidedBtn.id

        // free-choice list
        val list = findViewById<LinearLayout>(R.id.tests)
        list.removeAllViews()
        testButtons.clear()
        Catalog.tests.forEach { t ->
            val available = format in t.formats && manifest?.has(t.family(format, 0), format) == true
            val b = Button(this, null, 0, R.style.TvButton)
            val sub = if (available) t.oneLiner else "Not available for ${format.label} (" + t.formats.joinToString(" / ") { it.label } + " only)"
            val normal = twoLine(t.title, sub, grey = true)
            val focused = twoLine(t.title, sub, grey = false)  // focused = light background, keep text dark
            b.text = normal
            b.onFocusChangeListener = View.OnFocusChangeListener { _, has -> b.text = if (has) focused else normal }
            b.isEnabled = available
            b.isFocusable = available
            testButtons[t.id] = b
            b.nextFocusLeftId = R.id.guided
            b.setOnClickListener {
                lastTestId = t.id
                startActivity(
                    Intent(this, PatternActivity::class.java)
                        .putExtra(PatternActivity.EXTRA_GUIDED, false)
                        .putExtra(PatternActivity.EXTRA_TEST, t.id),
                )
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            list.addView(b, lp)
        }
    }

    private fun twoLine(title: String, sub: String, grey: Boolean): CharSequence {
        val sb = SpannableStringBuilder(title)
        val start = sb.length
        sb.append("\n").append(sub)
        sb.setSpan(RelativeSizeSpan(0.82f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (grey) sb.setSpan(ForegroundColorSpan(0xFF9AA0AC.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
