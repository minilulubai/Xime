package com.kingzcheung.xime.ui.keyboard

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

object AppFonts {
    private const val CHAI_PUA_FONT = "ChaiPUA-0.2.7-snow.ttf"

    private var initialized = false
    private lateinit var assetManager: AssetManager

    val chaiPuaTypeface: Typeface by lazy {
        Typeface.createFromAsset(assetManager, CHAI_PUA_FONT)
    }

    val chaiPuaFontFamily: FontFamily by lazy {
        FontFamily(Font(CHAI_PUA_FONT, assetManager))
    }

    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        assetManager = context.assets
    }
}
