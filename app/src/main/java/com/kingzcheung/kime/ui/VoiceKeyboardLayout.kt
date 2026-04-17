package com.kingzcheung.kime.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sqrt

@Composable
fun VoiceKeyboardLayout(
    keyBackgroundColor: Color,
    keyTextColor: Color,
    specialKeyBackgroundColor: Color,
    modifier: Modifier = Modifier,
    bottomActive: Boolean = false,
    leftActive: Boolean = false,
    rightActive: Boolean = false
) {
    val inactiveColor = Color(0xFFE8E8E8)  // 统一的浅灰色
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(specialKeyBackgroundColor.copy(alpha = 0.3f)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部：语音可视化区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 音频频谱动画
                AudioSpectrumAnimation(
                    modifier = Modifier.size(120.dp, 80.dp)
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "正在聆听...",
                    color = keyTextColor.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }
        }
        
// 中间：左右两个等边三角形按钮（中间角是大圆角）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
// 左按钮：等边三角形，左侧垂直边贴合左边缘，中间角大圆角
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    val sideLength = canvasHeight * 1.3f
                    
                    val Ax = 0f
                    val Ay = -sideLength * 0.15f
                    val Bx = 0f
                    val By = canvasHeight + sideLength * 0.15f
                    val Cx = sideLength * sqrt(3f) / 2f
                    val Cy = canvasHeight / 2f
                    
                    val cornerRadius = sideLength * 0.3f
                    
                    val AC_dist = sqrt((Cx - Ax) * (Cx - Ax) + (Cy - Ay) * (Cy - Ay))
                    val ACx = Ax + (Cx - Ax) * cornerRadius / AC_dist
                    val ACy = Ay + (Cy - Ay) * cornerRadius / AC_dist
                    
                    val BC_dist = sqrt((Cx - Bx) * (Cx - Bx) + (Cy - By) * (Cy - By))
                    val BCx = Bx + (Cx - Bx) * cornerRadius / BC_dist
                    val BCy = By + (Cy - By) * cornerRadius / BC_dist
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    
                    drawPath(
                        path = path,
                        color = if (leftActive) Color.White else inactiveColor
                    )
                }
                
                // 文本标签（放在三角形中心偏左位置）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "撤回",
                        color = if (leftActive) Color.Black else Color.DarkGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            
            // 右按钮：等边三角形，右侧垂直边贴合右边缘，中间角大圆角
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    
                    val sideLength = canvasHeight * 1.3f
                    
                    val Ax = canvasWidth
                    val Ay = -sideLength * 0.15f
                    val Bx = canvasWidth
                    val By = canvasHeight + sideLength * 0.15f
                    val Cx = canvasWidth - sideLength * sqrt(3f) / 2f
                    val Cy = canvasHeight / 2f
                    
                    val cornerRadius = sideLength * 0.3f
                    
                    val AC_dist = sqrt((Cx - Ax) * (Cx - Ax) + (Cy - Ay) * (Cy - Ay))
                    val ACx = Ax + (Cx - Ax) * cornerRadius / AC_dist
                    val ACy = Ay + (Cy - Ay) * cornerRadius / AC_dist
                    
                    val BC_dist = sqrt((Cx - Bx) * (Cx - Bx) + (Cy - By) * (Cy - By))
                    val BCx = Bx + (Cx - Bx) * cornerRadius / BC_dist
                    val BCy = By + (Cy - By) * cornerRadius / BC_dist
                    
                    val path = Path().apply {
                        moveTo(Ax, Ay)
                        lineTo(ACx, ACy)
                        quadraticBezierTo(Cx, Cy, BCx, BCy)
                        lineTo(Bx, By)
                        close()
                    }
                    drawPath(
                        path = path,
                        color = if (rightActive) Color.White else inactiveColor
                    )
                }
                
                // 文本标签（放在三角形中心偏右位置）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = "搜索",
                        color = if (rightActive) Color.Black else Color.DarkGray,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        
        // 底部按钮 Canvas（独立）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(
                modifier = Modifier.fillMaxSize()
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                
                val centerX = canvasWidth / 2f
                val baseRadius = canvasWidth * 0.8f
                val centerY = baseRadius + canvasHeight * 0.05f
                
                val bottomPath = Path().apply {
                    moveTo(0f, canvasHeight)
                    lineTo(canvasWidth, canvasHeight)
                    lineTo(canvasWidth, 0f)
                    arcTo(
                        Rect(centerX - baseRadius, centerY - baseRadius, centerX + baseRadius, centerY + baseRadius),
                        startAngleDegrees = 0f,
                        sweepAngleDegrees = -180f,
                        forceMoveTo = false
                    )
                    lineTo(0f, canvasHeight)
                    close()
                }
                
                // 渐变：从弧形顶部（白色/浅灰色 80%透明）到完全透明（底部边缘）
                val baseColor = if (bottomActive) Color.White else inactiveColor
                drawPath(
                    path = bottomPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            baseColor.copy(alpha = 0.8f),  // 顶部：80%透明
                            baseColor.copy(alpha = 0.5f),  // 中间：50%透明
                            baseColor.copy(alpha = 0.0f)   // 底部边缘：完全透明
                        ),
                        startY = 0f,
                        endY = canvasHeight
                    )
                )
            }
            
            Text(
                text = "松开结束",
                color = if (bottomActive) Color.DarkGray else Color.Gray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun AudioSpectrumAnimation(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    
    val barCount = 7
    val animations = List(barCount) { index ->
        infiniteTransition.animateFloat(
            initialValue = 0.3f + index * 0.05f,
            targetValue = 0.8f - index * 0.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = 300 + index * 50,
                    easing = FastOutSlowInEasing
                ),
                repeatMode = RepeatMode.Reverse
            ),
            label = "bar_$index"
        )
    }
    
    val colors = listOf(
        Color(0xFFFF6B6B),  // 红
        Color(0xFFFF9F43),  // 橙
        Color(0xFFFFEAA7),  // 黄
        Color(0xFF55EFC4),  // 绿
        Color(0xFF54A0FF),  // 蓝
        Color(0xFF5F27CD),  // 紫
        Color(0xFFFF6B6B),  // 红（循环）
    )
    
    Canvas(modifier = modifier) {
        val barWidth = size.width / (barCount * 2f)
        val spacing = barWidth
        val maxHeight = size.height
        
        animations.forEachIndexed { index, animatable ->
            val animatedHeight by animatable
            val barHeight = maxHeight * animatedHeight
            
            val x = spacing + index * (barWidth + spacing)
            val y = (maxHeight - barHeight) / 2f
            
            drawRoundRect(
                color = colors[index].copy(alpha = 0.7f + animatedHeight * 0.3f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}