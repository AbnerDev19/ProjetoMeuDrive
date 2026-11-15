package com.meudrive

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#FFFFFF")
    val SIDE = Color.parseColor("#F8F9FF")
    val TEXT = Color.parseColor("#0B1C30")
    val MUTED = Color.parseColor("#434655")
    val LINE = Color.parseColor("#E2E8F0")
    val ACCENT = Color.parseColor("#2563EB")

    private var jakarta: Typeface? = null
    fun font(c: Context): Typeface = jakarta ?: Typeface.createFromAsset(c.assets, "fonts/jakarta.ttf").also { jakarta = it }

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun shape(c: Context, fill: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(c, radius).toFloat()
        if (stroke != 0) setStroke(dp(c, 1), stroke)
    }

    fun text(c: Context, t: String, size: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = t
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(font(c), if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun primary(c: Context, t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        isAllCaps = false
        typeface = font(c)
        minHeight = dp(c, 48)
        minimumWidth = 0
        minWidth = 0
        maxLines = 3
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = shape(c, ACCENT, 12)
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        setOnClickListener { onClick() }
    }

    fun secondary(c: Context, t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        isAllCaps = false
        typeface = font(c)
        minHeight = dp(c, 48)
        minimumWidth = 0
        minWidth = 0
        maxLines = 3
        textSize = 13f
        gravity = android.view.Gravity.CENTER
        setTextColor(ACCENT)
        stateListAnimator = null
        background = shape(c, Color.parseColor("#EFF4FF"), 12)
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8))
        setOnClickListener { onClick() }
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c, BG, 20)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12) }
    }

    fun lp(c: Context, top: Int = 10, width: Int = -2) =
        LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(c, top) }
}
