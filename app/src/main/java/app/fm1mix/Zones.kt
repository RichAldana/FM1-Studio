package app.fm1mix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

private val AMBER = 0xFFF29B38.toInt()
private val INK = 0xFFD9D6CE.toInt()
private val DIM = 0xFF8E8C87.toInt()

/** The power button: a pill with the power symbol (a ring open at the top and a bar), green when on, red when off. */
class PowerButton(ctx: Context) : View(ctx) {
    var on = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val d = ctx.resources.displayMetrics.density

    init { contentDescription = tr("Marche / arrêt", "Power") }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val red = 0xFFE5534B.toInt()
        r.set(1.5f * d, 1.5f * d, w - 1.5f * d, h - 1.5f * d)
        if (on) {
            p.style = Paint.Style.FILL; p.color = 0xFF62D36E.toInt()
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * d; p.color = red
        }
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        val ic = if (on) 0xFF1E1F22.toInt() else red
        val cx = w / 2f
        val cy = h / 2f + 1f * d
        val rad = minOf(w, h) * 0.24f
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * d; p.strokeCap = Paint.Cap.ROUND; p.color = ic
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(r, -60f, 300f, false, p)
        c.drawLine(cx, cy - rad * 1.3f, cx, cy - rad * 0.15f, p)
    }
}

/**
 * One part of the screen that can show several pages, one at a time: a swipe that starts at the left or right edge
 * of the zone (the middle is the instrument's: keys glide, knobs turn) brings the next page in, or a tap on the dots
 * under it. The order wraps around, so a swipe either way reaches every page.
 */
class SwipeZone(ctx: Context, private val pages: List<View>, private val names: List<String>, start: Int,
                private val onPage: (Int) -> Unit) : ViewGroup(ctx) {
    var index = start.coerceIn(0, pages.size - 1)
        private set
    private val d = ctx.resources.displayMetrics.density
    private val edge = 34f * d
    private val stripH = (18f * d).toInt()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var x0 = 0f
    private var y0 = 0f
    private var fromEdge = false
    private var dragging = false
    private var drag = 0f
    private var anim: ValueAnimator? = null
    private var nameShownAt = 0L
    private val dots = object : View(ctx) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val n = pages.size
            val gap = 14f * d
            val x = width / 2f - (n - 1) * gap / 2f
            for (i in 0 until n) {
                p.color = if (i == index) AMBER else 0xFF5A5C62.toInt()
                c.drawCircle(x + i * gap, height / 2f, if (i == index) 3.6f * d else 2.8f * d, p)
            }
            if (System.currentTimeMillis() - nameShownAt < 1400) {
                p.color = DIM; p.textSize = 11f * d; p.textAlign = Paint.Align.LEFT
                c.drawText(names[index], x + n * gap, height / 2f + 4f * d, p)
                postInvalidateDelayed(300)
            }
        }
    }.apply { setOnClickListener { show((index + 1) % pages.size, true) } }

    init {
        pages.forEach { addView(it) }
        addView(dots)
        place()
    }

    /** Show page [i] (animated from the side it comes from). */
    fun show(i: Int, fromRight: Boolean) {
        if (i == index) return
        val w = width.toFloat()
        val old = index
        index = i
        onPage(i)
        nameShownAt = System.currentTimeMillis()
        pages[i].visibility = View.VISIBLE
        val dir = if (fromRight) 1f else -1f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                pages[old].translationX = -dir * w * t
                pages[i].translationX = dir * w * (1f - t)
            }
            doOnEnd { drag = 0f; place() }
            start()
        }
        dots.invalidate()
    }

    private fun ValueAnimator.doOnEnd(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(a: android.animation.Animator) = f()
    })

    /** only the current page shows, in place */
    private fun place() {
        for ((k, v) in pages.withIndex()) {
            v.translationX = 0f
            v.visibility = if (k == index) View.VISIBLE else View.INVISIBLE
        }
        dots.invalidate()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        for (v in pages) v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - stripH, MeasureSpec.EXACTLY))
        dots.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(stripH, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (v in pages) v.layout(0, 0, w, h - stripH)
        dots.layout(0, h - stripH, w, h)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; dragging = false
                fromEdge = e.y < height - stripH && (e.x < edge || e.x > width - edge)
            }
            MotionEvent.ACTION_MOVE -> if (fromEdge && !dragging && e.pointerCount == 1) {
                val dx = e.x - x0
                if (abs(dx) > slop * 1.5f && abs(dx) > 1.5f * abs(e.y - y0)) { dragging = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) {
            // (no page took the touch: it comes here straight, not through onInterceptTouchEvent)
            if (e.actionMasked != MotionEvent.ACTION_MOVE || !fromEdge) return fromEdge
            val dx = e.x - x0
            if (abs(dx) > slop * 1.5f && abs(dx) > 1.5f * abs(e.y - y0)) dragging = true else return true
        }
        val w = width.toFloat()
        val next = (index + 1) % pages.size
        val prev = (index - 1 + pages.size) % pages.size
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                drag = e.x - x0
                // a swipe to the right brings the page on the left in; to the left, the one on the right
                val other = if (drag > 0) prev else next
                for ((k, v) in pages.withIndex()) if (k != index && k != other) v.visibility = View.INVISIBLE
                pages[index].translationX = drag
                pages[other].visibility = View.VISIBLE
                pages[other].translationX = if (drag > 0) drag - w else drag + w
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (abs(drag) > w * 0.22f && e.actionMasked == MotionEvent.ACTION_UP) {
                    val other = if (drag > 0) prev else next
                    val fromRight = drag < 0
                    val old = index
                    index = other
                    onPage(other)
                    nameShownAt = System.currentTimeMillis()
                    val d0 = drag
                    anim = ValueAnimator.ofFloat(d0, if (fromRight) -w else w).apply {
                        duration = 160; interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            val x = it.animatedValue as Float
                            pages[old].translationX = x
                            pages[other].translationX = if (fromRight) x + w else x - w
                        }
                        doOnEnd { drag = 0f; place() }
                        start()
                    }
                } else {
                    val other = if (drag > 0) prev else next
                    val d0 = drag
                    anim = ValueAnimator.ofFloat(d0, 0f).apply {
                        duration = 140
                        addUpdateListener {
                            val x = it.animatedValue as Float
                            pages[index].translationX = x
                            pages[other].translationX = if (d0 > 0) x - w else x + w
                        }
                        doOnEnd { drag = 0f; place() }
                        start()
                    }
                }
                dots.invalidate()
            }
        }
        return true
    }
}

/**
 * The screen under the top bar: the middle zone (the FM-1's screen, encoders and buttons, as tall as they need at
 * this width, 62 % of the height at most) and the bottom zone (the keyboard: the rest). Nothing shows between them.
 */
class Zones(ctx: Context, private val mid: View, private val bottom: View, private val midRatio: Float) : ViewGroup(ctx) {
    init { addView(mid); addView(bottom) }

    private fun midH(w: Int, h: Int) = minOf((w * midRatio).toInt(), (h * 0.62f).toInt())

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        val m = midH(w, h)
        mid.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(m, MeasureSpec.EXACTLY))
        bottom.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - m, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val m = midH(w, b - t)
        mid.layout(0, 0, w, m)
        bottom.layout(0, m, w, b - t)
    }
}

/** A frame that clips its children (the pages sliding in stay inside their zone). */
class ClipFrame(ctx: Context) : FrameLayout(ctx) {
    init { clipChildren = true; clipToPadding = true }
}
