package com.fieldspec.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.log10
import kotlin.math.max

@Composable
fun SpectrumChart(
    spectrum: FloatArray,
    binsCount: Int,
    yAxisMode: Int,
    roiStart: Int,
    roiEnd: Int,
    calM: Float,
    calB: Float,
    isCalEnabled: Boolean,
    modifier: Modifier = Modifier,
    onTapBin: (Int) -> Unit,
    onRoiChanged: (Int, Int) -> Unit
) {
    var scaleX by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    
    var localRoiStart by remember(roiStart) { mutableIntStateOf(roiStart) }
    var localRoiEnd by remember(roiEnd) { mutableIntStateOf(roiEnd) }

    val textPaint = remember {
        Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 22f
            isAntiAlias = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(binsCount) {
                var dragStartBin = -1
                detectDragGestures(
                    onDragStart = { offset ->
                        val marginLeft = 110f
                        val w = size.width - marginLeft
                        val zoomedWidth = w * scaleX
                        val contentX = ((offset.x - marginLeft) + offsetX).coerceIn(0f, zoomedWidth)
                        val ratio = if (zoomedWidth > 0f) contentX / zoomedWidth else 0f
                        dragStartBin = (ratio * binsCount).toInt().coerceIn(0, binsCount - 1)
                        localRoiStart = dragStartBin
                        localRoiEnd = dragStartBin
                    },
                    onDragEnd = {
                        onRoiChanged(localRoiStart, localRoiEnd)
                    },
                    onDragCancel = {
                        onRoiChanged(localRoiStart, localRoiEnd)
                    },
                    onDrag = { change, _ ->
                        val marginLeft = 110f
                        val w = size.width - marginLeft
                        val zoomedWidth = w * scaleX
                        val contentX = ((change.position.x - marginLeft) + offsetX).coerceIn(0f, zoomedWidth)
                        val ratio = if (zoomedWidth > 0f) contentX / zoomedWidth else 0f
                        val currentBin = (ratio * binsCount).toInt().coerceIn(0, binsCount - 1)
                        if (dragStartBin != -1) {
                            localRoiStart = minOf(dragStartBin, currentBin)
                            localRoiEnd = maxOf(dragStartBin, currentBin)
                        }
                    }
                )
            }
            .pointerInput(binsCount) {
                detectTapGestures { offset ->
                    if (scaleX == 0f || binsCount == 0) return@detectTapGestures
                    localRoiStart = -1
                    localRoiEnd = -1
                    onRoiChanged(-1, -1) // Clear ROI
                    val marginLeft = 110f
                    val w = size.width - marginLeft
                    val zoomedWidth = w * scaleX
                    val contentX = ((offset.x - marginLeft) + offsetX).coerceIn(0f, zoomedWidth)
                    val ratio = if (zoomedWidth > 0f) contentX / zoomedWidth else 0f
                    val bin = (ratio * binsCount).toInt()
                    if (bin in 0 until binsCount) {
                        onTapBin(bin)
                    }
                }
            }
    ) {
        if (binsCount == 0 || spectrum.isEmpty()) return@Canvas

        val marginLeft = 110f
        val marginBottom = 70f
        val w = size.width - marginLeft
        val h = size.height - marginBottom

        val zoomedWidth = w * scaleX
        offsetX = offsetX.coerceIn(0f, max(0f, zoomedWidth - w))

        val displayValues = FloatArray(binsCount)
        var maxVal = 1f
        for (i in 0 until binsCount) {
            val rawValue = spectrum[i]
            val v = when (yAxisMode) {
                1 -> if (rawValue > 0f) log10(rawValue + 1.0).toFloat() else 0f
                2 -> rawValue * i
                else -> rawValue
            }
            displayValues[i] = v
            if (v > maxVal) maxVal = v
        }
        
        // Draw Y Axis Grids
        val gridLinesY = 5
        for (i in 0..gridLinesY) {
            val yPos = h - (i * (h / gridLinesY))
            val value = maxVal * (i.toFloat() / gridLinesY)
            
            drawLine(
                color = Color.DarkGray,
                start = Offset(marginLeft, yPos),
                end = Offset(size.width, yPos),
                strokeWidth = 1f
            )
            
            val label = String.format("%.1f", value)
            drawContext.canvas.nativeCanvas.drawText(label, 10f, yPos + 10f, textPaint)
        }
        drawContext.canvas.nativeCanvas.drawText("Rel.", 10f, 20f, textPaint)
        drawContext.canvas.nativeCanvas.drawText("Int.", 10f, 55f, textPaint)

        // Draw X Axis Grids
        val gridLinesX = 8
        for (i in 0..gridLinesX) {
            val rawX = i * (w / gridLinesX)
            val chartX = rawX + marginLeft
            
            drawLine(
                color = Color.DarkGray,
                start = Offset(chartX, 0f),
                end = Offset(chartX, h),
                strokeWidth = 1f
            )
            
            val viewRatio = rawX / w
            val scrollRatio = offsetX / zoomedWidth
            val realRatio = (viewRatio / scaleX) + scrollRatio
            val binIdx = realRatio * binsCount
            
            val label = if (isCalEnabled) {
                String.format("%.1f", calM * binIdx + calB)
            } else {
                binIdx.toInt().toString()
            }
            
            drawContext.canvas.nativeCanvas.drawText(label, chartX - 20f, size.height - 10f, textPaint)
        }
        
        val axisLabel = if (isCalEnabled) "Energy (keV)" else "Bin Channel"
        drawContext.canvas.nativeCanvas.drawText(axisLabel, size.width - 200f, size.height - 20f, textPaint)

        // Draw translucent ROI bounding box 
        val validRoi = localRoiStart in 0 until binsCount && localRoiEnd in 0 until binsCount && localRoiStart <= localRoiEnd
        if (validRoi) {
            val startX = marginLeft + ((localRoiStart.toFloat() / binsCount) * zoomedWidth) - offsetX
            val endX = marginLeft + (((localRoiEnd + 1).toFloat() / binsCount) * zoomedWidth) - offsetX
            
            val clampedStartX = maxOf(marginLeft, minOf(size.width, startX))
            val clampedEndX = maxOf(marginLeft, minOf(size.width, endX))
            
            if (clampedEndX > clampedStartX) {
                drawRect(
                    color = Color.Red.copy(alpha = 0.25f),
                    topLeft = androidx.compose.ui.geometry.Offset(clampedStartX, 0f),
                    size = Size(clampedEndX - clampedStartX, h)
                )
            }
        }

        // Draw Spectrum Bars
        val barWidth = max(1f, zoomedWidth / binsCount)
        for (i in 0 until binsCount) {
            val x = marginLeft + ((i.toFloat() / binsCount) * zoomedWidth) - offsetX
            if (x + barWidth < marginLeft || x > size.width) continue 
            
            val barColor = if (validRoi && i in localRoiStart..localRoiEnd) Color.Red else Color(0xFF64FFDA)
            
            val barH = (displayValues[i] / maxVal) * h
            drawRect(
                color = barColor, 
                topLeft = Offset(x, h - barH),
                size = Size(barWidth, barH)
            )
        }
    }
}
