package com.fieldspec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fieldspec.audio.PulseDetector
import com.fieldspec.audio.SpectrumState
import kotlinx.coroutines.delay

@Composable
fun SpectrumScreen(
    spectrumState: SpectrumState,
    isAcquiring: Boolean,
    onStartStop: () -> Unit,
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
    isCapturingShape: Boolean, // Kept for backwards signature
    onCaptureShapeToggle: () -> Unit,
    onStartShapeCapture: () -> Unit,
    calM: Float,
    calB: Float,
    lldBin: Int,
    onLldBinChanged: (Int) -> Unit,
    audioPassthroughEnabled: Boolean,
    onAudioPassthroughChanged: (Boolean) -> Unit,
    pulseDetector: PulseDetector,
    onSaveCalibration: (Float, Float) -> Unit,
    showOscilloscope: Boolean,
    onCloseOscilloscope: () -> Unit
) {
    var tappedBin by remember { mutableStateOf<Int?>(null) }
    var showCalDialog by remember { mutableStateOf(false) }
    var showPulseDialog by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            SpectrumChart(
                spectrum = spectrumState.spectrumCounts,
                binsCount = spectrumState.bins,
                yAxisMode = yAxisMode,
                roiStart = roiStart,
                roiEnd = roiEnd,
                calM = calM,
                calB = calB,
                isCalEnabled = isCalEnabled,
                onTapBin = { tappedBin = it },
                onRoiChanged = onRoiChanged
            )
            
            // ── Top banner: CPM + sparkline + controls ───────────────────────
            // rememberUpdatedState ensures the coroutine always reads the latest
            // totalCpm even though LaunchedEffect(Unit) only starts once.
            val latestCpm by rememberUpdatedState(spectrumState.totalCpm)
            var cpmHistory by remember { mutableStateOf(FloatArray(0)) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(500)
                    val cur = latestCpm.toFloat()
                    val prev = cpmHistory
                    cpmHistory = if (prev.size >= 120) {
                        FloatArray(120) { i -> if (i < 119) prev[i + 1] else cur }
                    } else {
                        FloatArray(prev.size + 1) { i -> if (i < prev.size) prev[i] else cur }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // CPM figure
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${spectrumState.totalCpm} CPM",
                        color = Color(0xFF00E5FF),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (spectrumState.roiCpm > 0) {
                        Text(
                            "ROI: ${spectrumState.roiCpm} CPM",
                            color = Color(0xFF80CBC4),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // Rolling CPM sparkline — 60 seconds at 0.5s resolution
                val sparkData = cpmHistory
                if (sparkData.size >= 2) {
                    androidx.compose.foundation.Canvas(
                        modifier = Modifier
                            .width(120.dp)
                            .height(38.dp)
                    ) {
                        val maxC = sparkData.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                        val w = size.width
                        val h = size.height
                        val dx = w / (sparkData.size - 1)
                        for (k in 0 until sparkData.size - 1) {
                            val x1 = k * dx
                            val y1 = h - (sparkData[k] / maxC) * h
                            val x2 = (k + 1) * dx
                            val y2 = h - (sparkData[k + 1] / maxC) * h
                            drawLine(
                                color = Color(0xFF00E5FF),
                                start = androidx.compose.ui.geometry.Offset(x1, y1),
                                end = androidx.compose.ui.geometry.Offset(x2, y2),
                                strokeWidth = 2.5f
                            )
                        }
                    }
                }

                Button(onClick = onClear, modifier = Modifier.height(36.dp)) {
                    Text("Clear")
                }

                Button(onClick = onStartStop, modifier = Modifier.height(36.dp)) {
                    Text(if (isAcquiring) "Stop" else "Start")
                }

                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Settings", tint = Color.White)
                }
            }
            
            tappedBin?.let { bin ->
                Card(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.8f))
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Bin: $bin")
                        if (isCalEnabled) {
                            val energy = calM * bin + calB
                            Text("Energy: ${String.format("%.2f keV", energy)}")
                        }
                        Text("Count: ${spectrumState.rawCounts.getOrElse(bin) { 0 }}")
                    }
                }
            }
        }

        if (showSettings) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { showSettings = false }) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)
                ) {
                    ControlPanels(
                        onClear = onClear,
                        decayFactor = decayFactor,
                        onDecayChanged = onDecayChanged,
                        yAxisMode = yAxisMode,
                        onYAxisModeChanged = onYAxisModeChanged,
                        bins = bins,
                        onBinsChanged = onBinsChanged,
                        isCalEnabled = isCalEnabled,
                        onCalToggle = onCalToggle,
                        roiStart = roiStart,
                        roiEnd = roiEnd,
                        onRoiChanged = onRoiChanged,
                        isCapturingShape = isCapturingShape,
                        onCaptureShapeToggle = onCaptureShapeToggle,
                        lldBin = lldBin,
                        onLldBinChanged = onLldBinChanged,
                        audioPassthroughEnabled = audioPassthroughEnabled,
                        onAudioPassthroughChanged = onAudioPassthroughChanged,
                        onOpenCalDialog = { showCalDialog = true }
                    )
                }
            }
        }
    }

    if (showCalDialog) {
        TwoPointCalDialog(
            onDismiss = { showCalDialog = false },
            onSave = { m, b ->
                onSaveCalibration(m, b)
                showCalDialog = false
            }
        )
    }

    if (showOscilloscope) {
        PulseDebugDialog(
            pulseDetector = pulseDetector,
            onDismiss = onCloseOscilloscope,
            onStartCapture = onStartShapeCapture
        )
    }
}

@Composable
fun TwoPointCalDialog(onDismiss: () -> Unit, onSave: (Float, Float) -> Unit) {
    var binStr by remember { mutableStateOf("") }
    var energyStr by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Single-Point Energy Calibration") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Bin 0 is always assumed to be 0 keV.\n" +
                    "Identify the bin of a known peak, then enter its energy.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = binStr,
                        onValueChange = { binStr = it },
                        label = { Text("Known Peak Bin") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = energyStr,
                        onValueChange = { energyStr = it },
                        label = { Text("Energy (keV)") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                // Live preview of the calibration slope
                val binVal = binStr.toFloatOrNull()
                val envVal = energyStr.toFloatOrNull()
                if (binVal != null && binVal > 0f && envVal != null && envVal > 0f) {
                    val slope = envVal / binVal
                    Text(
                        "Calibration: ${String.format("%.3f", slope)} keV/bin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val bin = binStr.toFloatOrNull() ?: 0f
                val energy = energyStr.toFloatOrNull() ?: 0f
                if (bin > 0f && energy > 0f) {
                    // Slope = energy/bin, intercept = 0 (passes through origin)
                    onSave(energy / bin, 0f)
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun PulseDebugDialog(pulseDetector: PulseDetector, onDismiss: () -> Unit, onStartCapture: () -> Unit) {
    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var remaining by remember { mutableIntStateOf(10) }
    
    LaunchedEffect(pulseDetector.isCapturingProfile) {
        while (pulseDetector.isCapturingProfile) {
            val elapsed = System.currentTimeMillis() - pulseDetector.shapeCaptureStartTime
            remaining = maxOf(0, 10 - (elapsed / 1000).toInt())
            delay(100)
        }
    }
    
    LaunchedEffect(Unit) {
        while (true) {
            val wv = pulseDetector.activeProfileAverage
            if (wv != null) {
                waveform = wv.copyOf() // Break reference
            }
            delay(100)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Average Scintillator Pulse") },
        text = {
            Column {
                if (pulseDetector.isCapturingProfile) {
                    Text(
                        "Acquiring: $remaining s remaining...",
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                ) {
                    val data = waveform
                    if (data == null || data.size < 2) {
                        Text(
                            "No shape calibrated.\nPress \"Begin\" to capture.",
                            color = Color.White,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.fillMaxSize().padding(12.dp)
                        ) {
                            val maxV = data.maxOrNull() ?: 1f
                            val minV = data.minOrNull() ?: 0f
                            val range = if (maxV - minV == 0f) 1f else maxV - minV
                            val w = size.width
                            val h = size.height
                            val dx = w / (data.size - 1)
                            for (i in 0 until data.size - 1) {
                                val x1 = i * dx
                                val y1 = h - ((data[i] - minV) / range) * h
                                val x2 = (i + 1) * dx
                                val y2 = h - ((data[i + 1] - minV) / range) * h
                                drawLine(
                                    Color.Green,
                                    androidx.compose.ui.geometry.Offset(x1, y1),
                                    androidx.compose.ui.geometry.Offset(x2, y2),
                                    strokeWidth = 3f
                                )
                            }
                        }
                    }
                }
                if (pulseDetector.profilePulsesCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Pulses captured: ${pulseDetector.profilePulsesCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Row {
                if (!pulseDetector.isCapturingProfile) {
                    Button(onClick = onStartCapture) {
                        Text("Begin 10s Capture")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
