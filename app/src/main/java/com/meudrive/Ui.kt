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
    val SIDE = Color.parseColor("#F7F7F5")
    val TEXT = Color.parseColor("#37352F")
    val MUTED = Color.parseColor("#787774")
    val LINE = Color.parseColor("#E9E9E7")
    val ACCENT = Color.parseColor("#2383E2")

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
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(c, 4), 0, dp(c, 4))
    }

    fun primary(c: Context, t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        isAllCaps = false
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = shape(c, ACCENT, 22)
        setPadding(dp(c, 20), 0, dp(c, 20), 0)
        setOnClickListener { onClick() }
    }

    fun secondary(c: Context, t: String, onClick: () -> Unit) = Button(c).apply {
        text = t
        isAllCaps = false
        setTextColor(TEXT)
        stateListAnimator = null
        background = shape(c, BG, 22, LINE)
        setPadding(dp(c, 20), 0, dp(c, 20), 0)
        setOnClickListener { onClick() }
    }

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        background = shape(c, BG, 14, LINE)
        setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(c, 12) }
    }

    fun lp(c: Context, top: Int = 10, width: Int = -2) =
        LinearLayout.LayoutParams(width, -2).apply { topMargin = dp(c, top) }
}
