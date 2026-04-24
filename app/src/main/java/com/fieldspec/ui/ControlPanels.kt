package com.fieldspec.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ControlPanels(
    onClear: () -> Unit,
    decayFactor: Float,
    onDecayChanged: (Float) -> Unit,
    yAxisMode: Int,
    onYAxisModeChanged: (Int) -> Unit,
    bins: Int,
    onBinsChanged: (Int) -> Unit,
    isCalEnabled: Boolean,
    onCalToggle: (Boolean) -> Unit,
    roiStart: Int,
    roiEnd: Int,
    onRoiChanged: (Int, Int) -> Unit,
    isCapturingShape: Boolean,
    onCaptureShapeToggle: () -> Unit,
    lldBin: Int,
    onLldBinChanged: (Int) -> Unit,
    audioPassthroughEnabled: Boolean,
    onAudioPassthroughChanged: (Boolean) -> Unit,
    onOpenCalDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .padding(8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Acquisition", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Spectrum")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                // Display Decay Speed (inverted: high slider = fast decay)
                // decayFactor is stored as 0.001..1.0 where 1.0 = no decay
                // decaySpeed = 1.0f - decayFactor maps 0.001..1.0 → 0.999..0.0 (fast..slow)
                val decaySpeed = 1.0f - decayFactor
                Text("Decay Speed: ${String.format("%.2f", decaySpeed)}")
                Slider(
                    value = decaySpeed,
                    onValueChange = { speed -> onDecayChanged(1.0f - speed) },
                    valueRange = 0.001f..0.999f
                )
                Text("Low Energy Threshold Bin (LLD): $lldBin")
                Slider(
                    value = lldBin.toFloat(),
                    onValueChange = { onLldBinChanged(it.toInt()) },
                    valueRange = 0f..30f
                )
                Text("Bins: $bins")
                Slider(
                    value = bins.toFloat(),
                    onValueChange = { onBinsChanged(it.toInt()) },
                    valueRange = 30f..2048f
                )
            }
        }
        
        Card {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Y-Axis Mode", style = MaterialTheme.typography.titleMedium)
                val modes = listOf("Linear", "Logarithmic", "Energy W.")
                modes.forEachIndexed { index, mode ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = (yAxisMode == index),
                            onClick = { onYAxisModeChanged(index) }
                        )
                        Text(mode)
                    }
                }
            }
        }
        
        Card {
            Column(modifier = Modifier.padding(8.dp)) {
                Text("Diagnostics & Cal", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = isCalEnabled, onCheckedChange = onCalToggle)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Cal")
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(checked = audioPassthroughEnabled, onCheckedChange = onAudioPassthroughChanged)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Audio Passthrough")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenCalDialog, modifier = Modifier.fillMaxWidth()) {
                    Text("Set Calibration Points")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onCaptureShapeToggle,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCapturingShape) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isCapturingShape) "Acquiring..." else "Calibrate Pulse Shape")
                }
            }
        }
    }
}
