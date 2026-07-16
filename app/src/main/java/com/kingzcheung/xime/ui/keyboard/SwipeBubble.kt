package com.kingzcheung.xime.ui.keyboard

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.keyboard.KeyboardDimensions
import com.kingzcheung.xime.settings.SettingsPreferences

private val BubbleBodyHeight = KeyboardDimensions.BubbleHeightDown
private val BubblePointerHeight = KeyboardDimensions.BubblePointerHeight
private val BubbleCornerRadius = KeyboardDimensions.BubbleCornerRadius
private val BubbleScreenMargin = 4.dp

private val bubblePath = Path()
private val bubbleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val bubbleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val SHADOW_COLOR = android.graphics.Color.argb(0x44, 0, 0, 0)

data class BubbleDrawData(
    val boxLeft: Float,
    val boxTop: Float,
    val pathBodyLeft: Float,
    val pathBodyWidth: Float,
    val pointerLeftInBox: Float,
    val keyWidthPx: Float,
    val bodyHeightPx: Float,
    val pointerHeightPx: Float,
    val cornerRadiusPx: Float,
    val isLeftFlush: Boolean,
    val isRightFlush: Boolean,
    val bgColor: Int,
    val textColor: Int,
    val displayText: String?,
    val isLongPressMode: Boolean,
    val longPressItems: List<String>,
    val selectedLongPressIndex: Int,
    val bodyWidth: Float,
    val textStartX: Float,
    val chaiTypeface: Typeface,
    val shadowRadiusPx: Float,
    val textSizePx: Float,
    val selectedFontSizePx: Float,
    val normalFontSizePx: Float,
    val selectedBgRadiusPx: Float,
    val longPressIconBitmaps: List<Bitmap> = emptyList(),
    val accentArgb: Int = 0xFF8F73E2.toInt(),
)

@Composable
fun rememberSwipeBubbleDrawData(
    swipeState: SwipeState,
    keyBounds: Rect,
    keyBackgroundColor: Color,
    keyTextColor: Color,
    accentColor: Color = Color(0xFF8F73E2),
    keyWidth: Float,
    keyboardWidth: Float,
): BubbleDrawData? {
    val context = LocalContext.current
    val showPressBubble = SettingsPreferences.shouldShowPressBubble(context)
    if (!swipeState.isSwiping && !(showPressBubble && swipeState.isPressed) && !swipeState.isLongPress) return null

    val isLongPressMode = swipeState.isLongPress && swipeState.longPressItems.isNotEmpty()
    val displayText = if (isLongPressMode) null
        else if (swipeState.isPressed) swipeState.pressedText
        else swipeState.swipeText
    if (!isLongPressMode && displayText.isNullOrEmpty()) return null

    val density = LocalDensity.current
    val bodyHeightPx = with(density) { BubbleBodyHeight.toPx() }
    val pointerHeightPx = with(density) { (BubblePointerHeight + 5.dp).toPx() }
    val cornerRadiusPx = with(density) { BubbleCornerRadius.toPx() }
    val screenMarginPx = with(density) { BubbleScreenMargin.toPx() }
    val keyWidthPx = keyWidth
    val minBodyWidthPx = keyWidthPx * 1.8f
    val totalHeightPx = bodyHeightPx + pointerHeightPx
    val shadowRadiusPx = with(density) { 4.dp.toPx() }

    val accentArgb = accentColor.toArgb()
    val isDarkTheme = keyTextColor == Color(0xFFE8EAED)
    val bgColor = (if (swipeState.isDanger) {
        if (swipeState.isSwipeDown) Color(0xFF1A73E8) else Color(0xFFD93025)
    } else keyBackgroundColor).toArgb()
    val textColor = (if (swipeState.isDanger) Color.White else keyTextColor).toArgb()

    val chaiTypeface = AppFonts.chaiPuaTypeface

    val textPaint = remember {
        Paint().apply {
            textSize = with(density) { 14.sp.toPx() }
            isAntiAlias = true
        }
    }

    val bodyWidth = if (isLongPressMode) {
        val cellMin = if (swipeState.longPressDrawableIds.isNotEmpty())
            swipeState.longPressItems.size
        else
            maxOf(swipeState.longPressItems.size, 3)
        cellMin * keyWidthPx
    } else {
        maxOf(textPaint.measureText(displayText!!) + with(density) { 20.dp.toPx() }, minBodyWidthPx)
    }

    val textSizePx = with(density) { 14.sp.toPx() }
    val selectedFontSizePx = with(density) { 18.sp.toPx() }
    val normalFontSizePx = with(density) { 14.sp.toPx() }
    val selectedBgRadiusPx = with(density) { 6.dp.toPx() }

    val pointerCenterX = keyBounds.left + keyBounds.width / 2f
    val bodyLeft = (pointerCenterX - bodyWidth / 2f).coerceIn(
        screenMarginPx,
        maxOf(screenMarginPx, keyboardWidth - bodyWidth - screenMarginPx)
    )
    val bodyRight = bodyLeft + bodyWidth
    val pointerLeft = pointerCenterX - keyWidthPx / 2f
    val pointerRight = pointerLeft + keyWidthPx
    val boxLeft = minOf(bodyLeft, pointerLeft)
    val boxTop = keyBounds.top + keyBounds.height - totalHeightPx
    val boxRight = maxOf(bodyRight, pointerRight)

    val rightRoom = bodyRight - pointerRight
    val leftRoom = pointerLeft - bodyLeft
    val flushTolerancePx = with(density) { 10.dp.toPx() }
    val isLeftFlush = leftRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyLeft - pointerLeft) < 1f
    val isRightFlush = rightRoom < cornerRadiusPx + flushTolerancePx || kotlin.math.abs(bodyRight - pointerRight) < 1f
    val bodyLeftInBox = bodyLeft - boxLeft
    val pointerLeftInBox = pointerLeft - boxLeft
    val pointerRightInBox = pointerLeftInBox + keyWidthPx
    val pathBodyLeft = if (isLeftFlush && leftRoom <= cornerRadiusPx) pointerLeftInBox else bodyLeftInBox
    val pathBodyWidth = (if (isRightFlush && rightRoom <= cornerRadiusPx) pointerRightInBox else (bodyLeftInBox + bodyWidth)) - pathBodyLeft

    val paddingPx = with(density) { 10.dp.toPx() }

    val longPressIconBitmaps = remember(swipeState.longPressDrawableIds, textColor) {
        swipeState.longPressDrawableIds.mapNotNull { id ->
            val drawable = context.resources.getDrawable(id, context.theme)?.mutate() ?: return@mapNotNull null
            drawable.setTint(textColor)
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val bmpCanvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(bmpCanvas)
            bitmap
        }
    }

    return BubbleDrawData(
        boxLeft = boxLeft,
        boxTop = boxTop,
        pathBodyLeft = pathBodyLeft,
        pathBodyWidth = pathBodyWidth,
        pointerLeftInBox = pointerLeftInBox,
        keyWidthPx = keyWidthPx,
        bodyHeightPx = bodyHeightPx,
        pointerHeightPx = pointerHeightPx,
        cornerRadiusPx = cornerRadiusPx,
        isLeftFlush = isLeftFlush && leftRoom <= cornerRadiusPx,
        isRightFlush = isRightFlush && rightRoom <= cornerRadiusPx,
        bgColor = bgColor,
        textColor = textColor,
        displayText = displayText,
        isLongPressMode = isLongPressMode,
        longPressItems = swipeState.longPressItems,
        selectedLongPressIndex = swipeState.selectedLongPressIndex,
        bodyWidth = bodyWidth,
        textStartX = bodyLeftInBox + paddingPx,
        chaiTypeface = chaiTypeface,
        shadowRadiusPx = shadowRadiusPx,
        textSizePx = textSizePx,
        selectedFontSizePx = selectedFontSizePx,
        normalFontSizePx = normalFontSizePx,
        selectedBgRadiusPx = selectedBgRadiusPx,
        longPressIconBitmaps = longPressIconBitmaps,
        accentArgb = accentArgb,
    )
}

private fun buildBubblePath(data: BubbleDrawData): Path {
    val bodyLeft = data.pathBodyLeft
    val bodyWidth = data.pathBodyWidth
    val bodyHeight = data.bodyHeightPx
    val pointerLeft = data.pointerLeftInBox
    val pointerWidth = data.keyWidthPx
    val pointerHeight = data.pointerHeightPx
    val cornerRadius = data.cornerRadiusPx
    val isLeftFlush = data.isLeftFlush
    val isRightFlush = data.isRightFlush

    val bodyRight = bodyLeft + bodyWidth
    val bodyBottom = bodyHeight
    val pointerRight = pointerLeft + pointerWidth
    val pointerBottom = bodyBottom + pointerHeight

    val r = cornerRadius.coerceAtMost(bodyWidth / 2f).coerceAtMost(bodyHeight / 2f)
    val pr = cornerRadius.coerceAtMost(pointerWidth / 2f).coerceAtMost(pointerHeight / 2f)

    bubblePath.rewind()
    bubblePath.moveTo(bodyLeft + r, 0f)
    bubblePath.lineTo(bodyRight - r, 0f)
    bubblePath.quadTo(bodyRight, 0f, bodyRight, r)

    if (isRightFlush) {
        bubblePath.lineTo(bodyRight, bodyBottom)
        bubblePath.quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
    } else {
        bubblePath.lineTo(bodyRight, bodyBottom - r)
        bubblePath.quadTo(bodyRight, bodyBottom, bodyRight - r, bodyBottom)
        bubblePath.lineTo(pointerRight + pr, bodyBottom)
        bubblePath.quadTo(pointerRight, bodyBottom, pointerRight, bodyBottom + pr)
    }

    bubblePath.lineTo(pointerRight, pointerBottom - pr)
    bubblePath.quadTo(pointerRight, pointerBottom, pointerRight - pr, pointerBottom)
    bubblePath.lineTo(pointerLeft + pr, pointerBottom)
    bubblePath.quadTo(pointerLeft, pointerBottom, pointerLeft, pointerBottom - pr)
    bubblePath.lineTo(pointerLeft, bodyBottom + pr)

    if (isLeftFlush) {
        bubblePath.lineTo(pointerLeft, bodyBottom)
        bubblePath.lineTo(bodyLeft, bodyBottom)
        bubblePath.lineTo(bodyLeft, r)
        bubblePath.quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
    } else {
        bubblePath.quadTo(pointerLeft, bodyBottom, pointerLeft - pr, bodyBottom)
        bubblePath.lineTo(bodyLeft + r, bodyBottom)
        bubblePath.quadTo(bodyLeft, bodyBottom, bodyLeft, bodyBottom - r)
        bubblePath.lineTo(bodyLeft, r)
        bubblePath.quadTo(bodyLeft, 0f, bodyLeft + r, 0f)
    }

    bubblePath.close()
    return bubblePath
}

fun DrawScope.drawSwipeBubble(data: BubbleDrawData) {
    val path = buildBubblePath(data)

    drawIntoCanvas { composeCanvas ->
        val canvas = composeCanvas.nativeCanvas
        canvas.save()
        canvas.translate(data.boxLeft, data.boxTop)

        bubbleFillPaint.color = data.bgColor
        bubbleFillPaint.setShadowLayer(data.shadowRadiusPx, 0f, 0f, SHADOW_COLOR)
        canvas.drawPath(path, bubbleFillPaint)

        if (data.isLongPressMode) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            val accentColor = data.accentArgb
            val selectedBgColor = android.graphics.Color.argb(
                0x33, android.graphics.Color.red(accentColor),
                android.graphics.Color.green(accentColor), android.graphics.Color.blue(accentColor)
            )
            val cellWidth = data.bodyWidth / data.longPressItems.size

            data.longPressItems.forEachIndexed { index, item ->
                val itemLeft = index * cellWidth
                if (index == data.selectedLongPressIndex) {
                    bubbleBgPaint.color = selectedBgColor
                    val r = minOf(data.selectedBgRadiusPx, cellWidth / 2f)
                    canvas.drawRoundRect(
                        itemLeft, 0f, itemLeft + cellWidth, data.bodyHeightPx, r, r, bubbleBgPaint
                    )
                }
                if (data.longPressIconBitmaps.isNotEmpty() && index < data.longPressIconBitmaps.size) {
                    val icon = data.longPressIconBitmaps[index]
                    val iconSize = data.bodyHeightPx * 0.45f
                    val iconLeft = itemLeft + (cellWidth - iconSize) / 2f
                    val iconTop = (data.bodyHeightPx - iconSize) / 2f
                    canvas.drawBitmap(
                        icon, null,
                        android.graphics.RectF(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize),
                        null
                    )
                } else {
                    val fontSize = if (index == data.selectedLongPressIndex) data.selectedFontSizePx else data.normalFontSizePx
                    bubbleLabelPaint.color = if (index == data.selectedLongPressIndex) accentColor else data.textColor
                    bubbleLabelPaint.textSize = fontSize
                    bubbleLabelPaint.textAlign = Paint.Align.CENTER
                    val textY = data.bodyHeightPx / 2f - (bubbleLabelPaint.fontMetrics.ascent + bubbleLabelPaint.fontMetrics.descent) / 2f
                    canvas.drawText(item, itemLeft + cellWidth / 2f, textY, bubbleLabelPaint)
                }
            }
            canvas.restore()
        } else if (data.displayText != null) {
            canvas.save()
            canvas.clipRect(0f, 0f, data.bodyWidth, data.bodyHeightPx)
            bubbleTextPaint.color = data.textColor
            bubbleTextPaint.textSize = data.textSizePx
            bubbleTextPaint.textAlign = Paint.Align.CENTER
            bubbleTextPaint.typeface = data.chaiTypeface
            val textCenterX = data.pathBodyLeft + data.pathBodyWidth / 2f
            val textY = data.bodyHeightPx / 2f - (bubbleTextPaint.fontMetrics.ascent + bubbleTextPaint.fontMetrics.descent) / 2f
            canvas.drawText(data.displayText, textCenterX, textY, bubbleTextPaint)
            canvas.restore()
        }

        canvas.restore()
    }
}
