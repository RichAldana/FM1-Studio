package app.fm1mix

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*

/**
 * The app's menus: a sheet that rises from the bottom (thumb reach), with pages pushed into it like a phone's settings:
 * a header (back, title, close), then groups of rows in rounded cards. One kind of row per job:
 *  - [Page.nav]: a title, what it is set to on the right, and a chevron: opens a page;
 *  - [Page.choice]: one of a list: the chosen one ticked (the list shows every option and what each does);
 *  - [Page.toggle], [Page.slider] (its value shown), [Page.action] (a destructive one in red);
 *  - [Page.note]: a line of explanation under a group.
 * Back (the arrow, the phone's back, a swipe down on the header) goes up a page, then closes.
 * After the patterns of Android's and iOS's settings and Material 3 bottom sheets.
 */
class Sheet(private val act: Activity) {
    companion object {
        val BG = 0xFF1E1F22.toInt()
        val CARD = 0xFF2A2B2F.toInt()
        val LINE = 0xFF3A3B40.toInt()
        val INK = Color.WHITE
        val DIM = 0xFF9A9892.toInt()
        val AMBER = 0xFFF29B38.toInt()
        val RED = 0xFFE5534B.toInt()
        val GREEN = 0xFF62D36E.toInt()
    }

    private val dialog = Dialog(act)
    private val stack = ArrayList<Pair<String, Page.() -> Unit>>()
    private val back: TextView
    private val title: TextView
    private val scroll: ScrollView
    private var onClose: (() -> Unit)? = null
    /** the current page (for its own live updates) */
    var page: Page? = null
        private set

    fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * act.resources.displayMetrics.density

    init {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadii = floatArrayOf(dpf(22f), dpf(22f), dpf(22f), dpf(22f), 0f, 0f, 0f, 0f)
            }
        }
        // the handle and the header: a swipe down there closes
        val handle = View(act).apply { background = GradientDrawable().apply { cornerRadius = dpf(3f); setColor(0xFF55565C.toInt()) } }
        root.addView(handle, LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(8) })
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        back = TextView(act).apply {
            text = "‹"; textSize = 30f; setTextColor(INK); gravity = Gravity.CENTER
            contentDescription = tr("Retour", "Back")
            setOnClickListener { pop() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = TextView(act).apply {
            textSize = 18f; setTextColor(INK); typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(act).apply {
            text = "✕"; textSize = 18f; setTextColor(DIM); gravity = Gravity.CENTER; contentDescription = tr("Fermer", "Close")
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        var downY = 0f
        val swipe = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                MotionEvent.ACTION_UP -> { if (e.rawY - downY > dp(90)) dialog.dismiss(); true }
                else -> true
            }
        }
        handle.setOnTouchListener(swipe)
        title.setOnTouchListener(swipe)
        root.addView(header)
        scroll = ScrollView(act).apply { isFillViewport = false; clipToPadding = false; setPadding(0, 0, 0, dp(24)) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP && stack.size > 1) { pop(); true } else false
        }
        dialog.setOnDismissListener { onClose?.invoke() }
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.55f)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setWindowAnimations(android.R.style.Animation_InputMethod)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** at most 85 % of the screen's height, scrolling inside */
    private fun fitHeight() {
        val max = (act.resources.displayMetrics.heightPixels * 0.85f).toInt()
        scroll.measure(View.MeasureSpec.makeMeasureSpec(act.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val lp = scroll.layoutParams
        lp.height = if (scroll.measuredHeight > max - dp(80)) max - dp(80) else ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.layoutParams = lp
    }

    fun onClose(f: () -> Unit): Sheet { onClose = f; return this }

    /** Show the sheet with its first page. */
    fun show(t: String, build: Page.() -> Unit): Sheet {
        stack.clear()
        stack.add(Pair(t, build))
        render()
        dialog.show()
        return this
    }

    fun push(t: String, build: Page.() -> Unit) { stack.add(Pair(t, build)); render(); scroll.scrollTo(0, 0) }
    fun pop() { if (stack.size > 1) { stack.removeAt(stack.size - 1); render() } else dialog.dismiss() }
    /** build the current page again (its values changed) */
    fun refresh() { val y = scroll.scrollY; render(); scroll.post { scroll.scrollTo(0, y) } }
    fun close() = dialog.dismiss()
    val isShowing: Boolean get() = dialog.isShowing

    private fun render() {
        val (t, build) = stack.last()
        title.text = t
        back.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
        val p = Page(this)
        page = p
        p.build()
        scroll.removeAllViews()
        scroll.addView(p.root)
        fitHeight()
    }

    /** A page: groups of rows. */
    inner class Page(val sheet: Sheet) {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        private var card: LinearLayout? = null
        val ctx: Activity get() = act

        /** a new group, with an optional heading */
        fun group(heading: String? = null) {
            if (heading != null) root.addView(TextView(act).apply {
                text = heading.uppercase(); textSize = 12f; setTextColor(DIM); letterSpacing = 0.06f
                setPadding(dp(8), dp(16), dp(8), dp(6))
            })
            else root.addView(View(act), LinearLayout.LayoutParams(1, dp(12)))
            card = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { cornerRadius = dpf(16f); setColor(CARD) }
                clipToOutline = true
            }
            root.addView(card)
        }

        private fun target(): LinearLayout = card ?: run { group(); card!! }

        /** add a row to the current group (a thin line between rows) */
        fun add(v: View) {
            val c = target()
            if (c.childCount > 0) c.addView(View(act).apply { setBackgroundColor(LINE) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { leftMargin = dp(16) })
            c.addView(v)
        }

        /** a free view outside the cards (a search field, chips...) */
        fun raw(v: View) { card = null; root.addView(v) }

        private fun texts(t: String, sub: String?, color: Int = INK): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(act).apply { text = t; textSize = 16f; setTextColor(color) })
            if (!sub.isNullOrEmpty()) addView(TextView(act).apply { text = sub; textSize = 13f; setTextColor(DIM); setPadding(0, dp(2), 0, 0) })
        }

        private fun rowOf(left: View, right: View?, onClick: (() -> Unit)?): LinearLayout = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56); setPadding(dp(16), dp(10), dp(12), dp(10))
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (right != null) addView(right)
            if (onClick != null) {
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null,
                    ColorDrawable(Color.WHITE))
                setOnClickListener { onClick() }
            }
        }

        /** opens something: its title, what it is set to, a chevron */
        fun nav(t: String, value: String? = null, sub: String? = null, onClick: () -> Unit) {
            val right = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                if (value != null) addView(TextView(act).apply {
                    text = value; textSize = 14f; setTextColor(DIM); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    maxWidth = dp(150); setPadding(dp(8), 0, dp(6), 0)
                })
                addView(TextView(act).apply { text = "›"; textSize = 22f; setTextColor(DIM) })
            }
            add(rowOf(texts(t, sub), right, onClick))
        }

        /** one option of a choice: ticked when chosen */
        fun choice(t: String, sub: String? = null, chosen: Boolean, onClick: () -> Unit) {
            val tick = TextView(act).apply { text = if (chosen) "✓" else ""; textSize = 18f; setTextColor(AMBER); setPadding(dp(8), 0, dp(4), 0) }
            add(rowOf(texts(t, sub, if (chosen) AMBER else INK), tick) { onClick(); sheet.refresh() })
        }

        fun toggle(t: String, sub: String? = null, on: Boolean, onChange: (Boolean) -> Unit) {
            val sw = Switch(act).apply { isChecked = on; setOnCheckedChangeListener { _, v -> onChange(v) } }
            add(rowOf(texts(t, sub), sw) { sw.isChecked = !sw.isChecked })
        }

        /** a value from [min] to [max]; [label] shows it */
        fun slider(t: String, v: Int, min: Int, max: Int, label: (Int) -> String, onChange: (Int) -> Unit) {
            val value = TextView(act).apply { text = label(v); textSize = 14f; setTextColor(DIM) }
            val box = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(12), dp(8))
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(TextView(act).apply { text = t; textSize = 16f; setTextColor(INK) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(value)
                })
                addView(SeekBar(act).apply {
                    this.max = max - min; progress = v - min
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { if (u) { value.text = label(p + min); onChange(p + min) } }
                        override fun onStartTrackingTouch(s: SeekBar) {}
                        override fun onStopTrackingTouch(s: SeekBar) {}
                    })
                })
            }
            add(box)
        }

        /** a button-like row; [danger]: in red */
        fun action(t: String, sub: String? = null, danger: Boolean = false, onClick: () -> Unit) {
            add(rowOf(texts(t, sub, if (danger) RED else AMBER), null, onClick))
        }

        /** a line of information (a value shown, nothing to touch) */
        fun info(t: String, value: String) {
            add(rowOf(TextView(act).apply { text = t; textSize = 15f; setTextColor(DIM) },
                TextView(act).apply { text = value; textSize = 15f; setTextColor(INK); gravity = Gravity.END; maxWidth = dp(220) }, null))
        }

        /** a paragraph inside a card */
        fun text(t: String, size: Float = 14f, selectable: Boolean = false) {
            add(TextView(act).apply { text = t; textSize = size; setTextColor(INK); setPadding(dp(16), dp(12), dp(16), dp(12)); setTextIsSelectable(selectable) })
        }

        /** a line of explanation under the group */
        fun note(t: String) {
            card = null
            root.addView(TextView(act).apply { text = t; textSize = 12f; setTextColor(DIM); setPadding(dp(8), dp(6), dp(8), 0) })
        }
    }
}
