package pl.luki.frankenflasher

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Ciemny motyw w stylu "liquid glass": polprzezroczyste karty na rozmytym, kolorowym tle. */
class Ui(private val ctx: Context) {
    companion object {
        val BG_TOP = Color.parseColor("#0B1220")
        val BG_BOTTOM = Color.parseColor("#05080F")
        val ACCENT = Color.parseColor("#3DDCC4")
        val ACCENT_DARK = Color.parseColor("#1FA595")
        val ACCENT_DIM = Color.parseColor("#1F3D3C")
        val VIOLET = Color.parseColor("#7C5CFF")
        val TEXT = Color.parseColor("#EEF3F8")
        val TEXT2 = Color.parseColor("#A9B6C6")
        val MUTED = Color.parseColor("#7D8B9D")
        val DANGER = Color.parseColor("#FF7A6E")
        val OK = Color.parseColor("#41D67A")
    }

    fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dp(v: Int) = dp(v.toFloat())

    // ------------------------------------------------------------ tlo

    /** Gradient + rozmyte "plamy" koloru - za szklanymi kartami daje efekt glebi. */
    fun background(): FrameLayout {
        val f = FrameLayout(ctx)
        f.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
        fun blob(color: Int, size: Int, gravity: Int, mx: Int, my: Int) {
            val v = View(ctx)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(size) / 2f
                colors = intArrayOf(color, Color.TRANSPARENT)
            }
            if (Build.VERSION.SDK_INT >= 31) v.setRenderEffect(RenderEffect.createBlurEffect(dp(40).toFloat(), dp(40).toFloat(), Shader.TileMode.DECAL))
            f.addView(v, FrameLayout.LayoutParams(dp(size), dp(size), gravity).apply { setMargins(mx, my, mx, my) })
        }
        blob(Color.argb(110, 61, 220, 196), 420, Gravity.TOP or Gravity.END, -dp(140), -dp(120))
        blob(Color.argb(90, 124, 92, 255), 380, Gravity.CENTER_VERTICAL or Gravity.START, -dp(170), 0)
        blob(Color.argb(60, 61, 160, 255), 360, Gravity.BOTTOM or Gravity.END, -dp(150), dp(60))
        return f
    }

    // ------------------------------------------------------------ szklo

    fun glass(radius: Float = 22f, tint: Int = Color.WHITE, alphaTop: Int = 26, alphaBottom: Int = 10, stroke: Int = 34): LayerDrawable {
        val r = dp(radius).toFloat()
        val body = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(alphaTop, Color.red(tint), Color.green(tint), Color.blue(tint)),
            Color.argb(alphaBottom, Color.red(tint), Color.green(tint), Color.blue(tint)),
        )).apply { cornerRadius = r; setStroke(dp(1), Color.argb(stroke, 255, 255, 255)) }
        // odblask na gornej krawedzi
        val shine = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.argb(38, 255, 255, 255), Color.TRANSPARENT,
        )).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
        return LayerDrawable(arrayOf(body, shine)).apply {
            setLayerInset(1, dp(1), dp(1), dp(1), 0)
            setLayerHeight(1, dp(radius * 1.4f))
        }
    }

    fun card(parent: ViewGroup, label: String? = null): LinearLayout {
        if (label != null) parent.addView(sectionLabel(label))
        val c = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glass()
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        parent.addView(c, lp(bottom = 14))
        return c
    }

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    // ------------------------------------------------------------ teksty

    fun title(t: String, sub: String? = null): LinearLayout {
        val l = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(10), dp(4), dp(14)) }
        l.addView(TextView(ctx).apply { text = t; textSize = 30f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
        if (sub != null) l.addView(TextView(ctx).apply { text = sub; textSize = 14f; setTextColor(TEXT2); setPadding(0, dp(4), 0, 0) })
        return l
    }

    fun sectionLabel(t: String) = TextView(ctx).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.12f; setTextColor(MUTED)
        setPadding(dp(6), dp(8), 0, dp(8))
    }

    fun body(t: String = "", size: Float = 14f, color: Int = TEXT2) = TextView(ctx).apply {
        text = t; textSize = size; setTextColor(color); setLineSpacing(0f, 1.15f)
    }

    fun value(t: String) = TextView(ctx).apply { text = t; textSize = 16f; setTextColor(TEXT); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    // ------------------------------------------------------------ przyciski

    private fun pressable(v: View) {
        v.isClickable = true
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.85f).setDuration(80).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
            false
        }
    }

    enum class Kind { PRIMARY, GLASS, DANGER }

    fun button(text: String, kind: Kind = Kind.GLASS, action: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        minHeight = dp(50)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        when (kind) {
            Kind.PRIMARY -> {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(ACCENT, ACCENT_DARK)).apply { cornerRadius = dp(16).toFloat() }
                setTextColor(Color.parseColor("#04201C"))
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            }
            Kind.GLASS -> { background = glass(16f, alphaTop = 30, alphaBottom = 14, stroke = 40); setTextColor(TEXT) }
            Kind.DANGER -> {
                background = glass(16f, tint = DANGER, alphaTop = 40, alphaBottom = 16, stroke = 60); setTextColor(DANGER)
            }
        }
        pressable(this)
        setOnClickListener { action() }
    }

    /** Rzad przyciskow o rownej szerokosci. */
    fun row(parent: ViewGroup, vararg b: TextView, top: Int = 0) {
        val r = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        b.forEachIndexed { i, v ->
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(10) })
        }
        parent.addView(r, lp(top = top, bottom = 10))
    }

    /** Duzy krok "1 / 2" jak w kreatorze. */
    fun step(n: Int, text: String, active: Boolean, action: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = if (active) glass(18f, tint = ACCENT, alphaTop = 36, alphaBottom = 14, stroke = 0).also {
            (it.getDrawable(0) as GradientDrawable).setStroke(dp(1.5f), ACCENT)
        } else glass(18f)
        val circle = TextView(ctx).apply {
            this.text = n.toString(); gravity = Gravity.CENTER; textSize = 16f
            setTextColor(if (active) ACCENT else TEXT2)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1.5f), if (active) ACCENT else MUTED) }
        }
        addView(circle, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(TextView(ctx).apply {
            this.text = text; textSize = 16f; setTextColor(TEXT); setPadding(dp(12), 0, 0, 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        pressable(this)
        setOnClickListener { action() }
    }

    // ------------------------------------------------------------ przelaczniki

    @Suppress("UseSwitchCompatOrMaterialCode")
    fun toggle(parent: LinearLayout, label: String, get: () -> Boolean, set: (Boolean) -> Unit, danger: Boolean = false) {
        if (parent.childCount > 0) parent.addView(View(ctx).apply { setBackgroundColor(Color.argb(22, 255, 255, 255)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        val sw = Switch(ctx).apply {
            text = label; textSize = 14.5f; setTextColor(if (danger) DANGER else TEXT)
            isChecked = get()
            setPadding(0, dp(10), 0, dp(10))
            val on = if (danger) DANGER else ACCENT
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(on, Color.parseColor("#C9D2DC")))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.argb(110, Color.red(on), Color.green(on), Color.blue(on)), Color.argb(60, 255, 255, 255)))
            setOnCheckedChangeListener { _, c -> set(c) }
        }
        parent.addView(sw, lp())
    }

    fun input(hint: String) = EditText(ctx).apply {
        this.hint = hint; isSingleLine = true; textSize = 15f
        setTextColor(TEXT); setHintTextColor(MUTED)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = glass(14f, alphaTop = 18, alphaBottom = 8)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    // ------------------------------------------------------------ dolna nawigacja

    class NavItem(val view: LinearLayout, val icon: ImageView, val label: TextView)

    fun navItem(iconRes: Int, text: String): NavItem {
        val l = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        val ic = ImageView(ctx).apply { setImageResource(iconRes) }
        l.addView(ic, LinearLayout.LayoutParams(dp(24), dp(24)))
        val tv = TextView(ctx).apply { this.text = text; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        l.addView(tv)
        pressable(l)
        return NavItem(l, ic, tv)
    }

    fun setNavSelected(n: NavItem, sel: Boolean) {
        val c = if (sel) ACCENT else TEXT2
        n.icon.imageTintList = ColorStateList.valueOf(c)
        n.label.setTextColor(c)
        n.label.typeface = Typeface.create("sans-serif-medium", if (sel) Typeface.BOLD else Typeface.NORMAL)
        n.view.background = if (sel) GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.argb(70, 61, 220, 196)) } else null
    }
}
