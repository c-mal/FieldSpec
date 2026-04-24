package com.fieldspec

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fieldspec.audio.PulseDetector
import com.fieldspec.audio.SpectrumModel
import com.fieldspec.audio.UsbAudioProcessor
import com.fieldspec.data.SettingsRepository
import com.fieldspec.service.AcquisitionService
import com.fieldspec.ui.SpectrumScreen
import com.fieldspec.ui.theme.FieldSpecTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var spectrumModel: SpectrumModel
    private lateinit var pulseDetector: PulseDetector
    private lateinit var usbAudioProcessor: UsbAudioProcessor

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] != true) {
            Toast.makeText(this, "Audio Permission Required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        settingsRepository = SettingsRepository(applicationContext)
        spectrumModel = SpectrumModel()
        pulseDetector = PulseDetector(spectrumModel)
        
        usbAudioProcessor = UsbAudioProcessor(
            getSystemService(AudioManager::class.java),
            pulseDetector
        ) { spectrumModel.state.value.bins }

        lifecycleScope.launch(Dispatchers.Default) {
            while (isActive) {
                val factor = settingsRepository.decayFactorFlow.first()
                if (usbAudioProcessor.isAcquiring) {
                    spectrumModel.applyDecayAndPublish(factor, 50L) // 50ms interval => 20Hz refresh
                } else {
                    spectrumModel.applyDecayAndPublish(1.0f, 50L) // Passing 1.0f pauses the decay
                }
                delay(50)
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            settingsRepository.customShapeFlow.collect { shapeCsv ->
                if (shapeCsv != null) {
                    val floats = shapeCsv.lines().mapNotNull { line ->
                        val parts = line.split(",")
                        if (parts.size == 2) parts[1].toFloatOrNull() else null
                    }
                    if (floats.isNotEmpty()) {
                        pulseDetector.setShapeTemplate(floats.toFloatArray())
                    }
                }
            }
        }


        requestPermissions()

        setContent {
            val state by spectrumModel.state.collectAsState()
            val bins by settingsRepository.binsFlow.collectAsState(initial = 1024)
            val decayFactor by settingsRepository.decayFactorFlow.collectAsState(initial = 1.0f)
            val yAxisMode by settingsRepository.yAxisModeFlow.collectAsState(initial = 0)
            val calEnabled by settingsRepository.calibrationEnabledFlow.collectAsState(initial = false)
            val calSlope by settingsRepository.calibrationSlopeFlow.collectAsState(initial = 1.0f)
            val calIntercept by settingsRepository.calibrationInterceptFlow.collectAsState(initial = 0.0f)
            val roiStart by settingsRepository.roiStartFlow.collectAsState(initial = -1)
            val roiEnd by settingsRepository.roiEndFlow.collectAsState(initial = -1)
            val lldBin by settingsRepository.lldBinFlow.collectAsState(initial = 0)
            val audioPassthroughEnabled by settingsRepository.audioPassthroughFlow.collectAsState(initial = false)
            
            var isAcquiring by remember { mutableStateOf(false) }
            var isCapturingShape by remember { mutableStateOf(false) }
            var showOscilloscope by remember { mutableStateOf(false) }

            LaunchedEffect(bins) { spectrumModel.setBins(bins) }
            LaunchedEffect(roiStart, roiEnd) { spectrumModel.setRoi(roiStart, roiEnd) }
            LaunchedEffect(lldBin) { pulseDetector.lldBin = lldBin }
            LaunchedEffect(audioPassthroughEnabled) { usbAudioProcessor.audioPassthroughEnabled = audioPassthroughEnabled }

            FieldSpecTheme {
                SpectrumScreen(
                    spectrumState = state,
                    isAcquiring = isAcquiring,
                    onStartStop = {
                        val svcIntent = Intent(this@MainActivity, AcquisitionService::class.java)
                        if (isAcquiring) {
                            usbAudioProcessor.stopAcquisition()
                            stopService(svcIntent)
                            isAcquiring = false
                        } else {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                startForegroundService(svcIntent)
                            } else {
                                startService(svcIntent)
                            }
                            lifecycleScope.launch {
                                isAcquiring = true
                                usbAudioProcessor.startAcquisition()
                            }
                        }
                    },
                    onClear = { spectrumModel.clear() },
                    decayFactor = decayFactor,
                    onDecayChanged = { f -> lifecycleScope.launch { settingsRepository.setDecayFactor(f) } },
                    yAxisMode = yAxisMode,
                    onYAxisModeChanged = { m -> lifecycleScope.launch { settingsRepository.setYAxisMode(m) } },
                    bins = bins,
                    onBinsChanged = { b -> lifecycleScope.launch { settingsRepository.setBins(b) } },
                    isCalEnabled = calEnabled,
                    onCalToggle = { e -> lifecycleScope.launch { settingsRepository.setCalibrationState(e) } },
                    roiStart = roiStart,
                    roiEnd = roiEnd,
                    onRoiChanged = { s, e -> lifecycleScope.launch { settingsRepository.setRoi(s, e) } },
                    isCapturingShape = isCapturingShape,
                    onCaptureShapeToggle = { showOscilloscope = true },
                    onStartShapeCapture = {
                        val wasAlreadyAcquiring = isAcquiring
                        // Auto-start acquisition if it isn't running
                        if (!isAcquiring) {
                            val svcIntent = Intent(this@MainActivity, AcquisitionService::class.java)
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                startForegroundService(svcIntent)
                            } else {
                                startService(svcIntent)
                            }
                            lifecycleScope.launch {
                                isAcquiring = true
                                usbAudioProcessor.startAcquisition()
                            }
                        }
                        isCapturingShape = true
                        pulseDetector.startShapeCapture()
                        lifecycleScope.launch {
                            delay(10_000)
                            if (isCapturingShape && pulseDetector.isCapturingProfile) {
                                isCapturingShape = false
                                val csv = pulseDetector.finalizeAndGetShapeCsv()
                                csv?.let { c ->
                                    lifecycleScope.launch { settingsRepository.setCustomShape(c) }
                                }
                                val rateKhz = usbAudioProcessor.actualSampleRate / 1000
                                val msg = if (csv != null)
                                    "Pulse shape captured @ ${rateKhz}kHz (${pulseDetector.profilePulsesCount} pulses)"
                                else
                                    "No pulses captured — check spectrometer connection"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                // Auto-stop acquisition if we started it ourselves
                                if (!wasAlreadyAcquiring && isAcquiring) {
                                    usbAudioProcessor.stopAcquisition()
                                    stopService(Intent(this@MainActivity, AcquisitionService::class.java))
                                    isAcquiring = false
                                }
                            }
                        }
                    },
                    calM = calSlope,
                    calB = calIntercept,
                    lldBin = lldBin,
                    onLldBinChanged = { b -> lifecycleScope.launch { settingsRepository.setLldBin(b) } },
                    audioPassthroughEnabled = audioPassthroughEnabled,
                    onAudioPassthroughChanged = { en -> lifecycleScope.launch { settingsRepository.setAudioPassthrough(en) } },
                    pulseDetector = pulseDetector,
                    onSaveCalibration = { m, b -> lifecycleScope.launch { settingsRepository.setCalibration(m, b, true) } },
                    showOscilloscope = showOscilloscope,
                    onCloseOscilloscope = { showOscilloscope = false }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
