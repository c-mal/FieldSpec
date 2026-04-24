package com.fieldspec.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        val BINS_KEY = intPreferencesKey("bins")
        val DECAY_FACTOR_KEY = floatPreferencesKey("decay_factor")
        val Y_AXIS_MODE_KEY = intPreferencesKey("y_axis_mode")
        val CALIBRATION_SLOPE = floatPreferencesKey("cal_slope")
        val CALIBRATION_INTERCEPT = floatPreferencesKey("cal_intercept")
        val CALIBRATION_ENABLED = booleanPreferencesKey("cal_enabled")
        val ROI_START = intPreferencesKey("roi_start")
        val ROI_END = intPreferencesKey("roi_end")
        val CUSTOM_SHAPE_KEY = stringPreferencesKey("custom_shape")
        val THRESHOLD_KEY = floatPreferencesKey("threshold")
        val LLD_BIN = intPreferencesKey("lld_bin")
        val AUDIO_PASSTHROUGH = booleanPreferencesKey("audio_passthrough")
    }

    val binsFlow: Flow<Int> = context.dataStore.data.map { pref -> pref[BINS_KEY] ?: 1024 }
    val thresholdFlow: Flow<Float> = context.dataStore.data.map { pref -> pref[THRESHOLD_KEY] ?: 50f }
    val decayFactorFlow: Flow<Float> = context.dataStore.data.map { pref -> pref[DECAY_FACTOR_KEY] ?: 1.0f }
    val yAxisModeFlow: Flow<Int> = context.dataStore.data.map { pref -> pref[Y_AXIS_MODE_KEY] ?: 0 } // 0=Linear, 1=Log, 2=EnergyWeighted
    val calibrationSlopeFlow: Flow<Float> = context.dataStore.data.map { pref -> pref[CALIBRATION_SLOPE] ?: 1.0f }
    val calibrationInterceptFlow: Flow<Float> = context.dataStore.data.map { pref -> pref[CALIBRATION_INTERCEPT] ?: 0.0f }
    val calibrationEnabledFlow: Flow<Boolean> = context.dataStore.data.map { pref -> pref[CALIBRATION_ENABLED] ?: false }
    val roiStartFlow: Flow<Int> = context.dataStore.data.map { pref -> pref[ROI_START] ?: -1 }
    val roiEndFlow: Flow<Int> = context.dataStore.data.map { pref -> pref[ROI_END] ?: -1 }
    val customShapeFlow: Flow<String?> = context.dataStore.data.map { pref -> pref[CUSTOM_SHAPE_KEY] }
    val lldBinFlow: Flow<Int> = context.dataStore.data.map { pref -> pref[LLD_BIN] ?: 0 }
    val audioPassthroughFlow: Flow<Boolean> = context.dataStore.data.map { pref -> pref[AUDIO_PASSTHROUGH] ?: false }

    suspend fun setBins(bins: Int) {
        context.dataStore.edit { it[BINS_KEY] = bins }
    }

    suspend fun setDecayFactor(factor: Float) {
        context.dataStore.edit { it[DECAY_FACTOR_KEY] = factor }
    }

    suspend fun setYAxisMode(mode: Int) {
        context.dataStore.edit { it[Y_AXIS_MODE_KEY] = mode }
    }

    suspend fun setCalibrationState(enabled: Boolean) {
        context.dataStore.edit { it[CALIBRATION_ENABLED] = enabled }
    }

    suspend fun setCalibration(slope: Float, intercept: Float, enabled: Boolean) {
        context.dataStore.edit { 
            it[CALIBRATION_SLOPE] = slope
            it[CALIBRATION_INTERCEPT] = intercept
            it[CALIBRATION_ENABLED] = enabled
        }
    }

    suspend fun setRoi(start: Int, end: Int) {
        context.dataStore.edit { 
            it[ROI_START] = start
            it[ROI_END] = end
        }
    }

    suspend fun setCustomShape(csvString: String) {
        context.dataStore.edit { it[CUSTOM_SHAPE_KEY] = csvString }
    }

    suspend fun setThreshold(t: Float) {
        context.dataStore.edit { it[THRESHOLD_KEY] = t }
    }

    suspend fun setLldBin(bin: Int) {
        context.dataStore.edit { it[LLD_BIN] = bin }
    }

    suspend fun setAudioPassthrough(enabled: Boolean) {
        context.dataStore.edit { it[AUDIO_PASSTHROUGH] = enabled }
    }
}
