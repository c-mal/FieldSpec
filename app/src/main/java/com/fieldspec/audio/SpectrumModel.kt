package com.fieldspec.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicIntegerArray

data class SpectrumState(
    val spectrumCounts: FloatArray = FloatArray(1024),
    val rawCounts: IntArray = IntArray(1024),
    val totalCpm: Int = 0,
    val roiCpm: Int = 0,
    val roiGross: Int = 0,
    val bins: Int = 1024
)

class SpectrumModel {
    @Volatile private var bins = 1024
    
    // Pre-allocate to massive array max to avoid reallocation hit when switching
    private var spectrumCounts = FloatArray(2048)
    private var rawCounts = AtomicIntegerArray(2048)
    
    // Performance optimization: we buffer standard pulses without locking
    // 20 buckets of 100ms = 2 seconds.
    private val timeBuckets = AtomicIntegerArray(20)
    private val roiTimeBuckets = AtomicIntegerArray(20)
    @Volatile private var currentBucketIndex = 0
    @Volatile private var lastBucketTimeMs = System.currentTimeMillis()

    private val _state = MutableStateFlow(SpectrumState())
    val state: StateFlow<SpectrumState> = _state.asStateFlow()

    @Volatile var roiStart = -1
    @Volatile var roiEnd = -1

    fun setRoi(start: Int, end: Int) {
        roiStart = start
        roiEnd = end
    }

    fun setBins(newBins: Int) {
        if (newBins in 1..2048 && newBins != bins) {
            bins = newBins
            clear()
        }
    }

    fun addPulse(bin: Int) {
        if (bin !in 0 until bins) return
        
        rawCounts.incrementAndGet(bin)
        // spectrum counts are updated on the decay thread lock-free
        spectrumCounts[bin] += 1f
        
        val nowMs = System.currentTimeMillis()
        updateBucketsTime(nowMs)
        timeBuckets.incrementAndGet(currentBucketIndex)
        
        if (roiStart in 0 until bins && roiEnd in 0 until bins) {
            if (bin in roiStart..roiEnd) {
                roiTimeBuckets.incrementAndGet(currentBucketIndex)
            }
        }
    }

    private fun updateBucketsTime(nowMs: Long) {
        val diffMs = nowMs - lastBucketTimeMs
        if (diffMs >= 100) {
            val bucketsToAdvance = (diffMs / 100).toInt()
            val steps = Math.min(bucketsToAdvance, 20)
            for (i in 1..steps) {
                val nextIdx = (currentBucketIndex + 1) % 20
                timeBuckets.set(nextIdx, 0)
                roiTimeBuckets.set(nextIdx, 0)
                currentBucketIndex = nextIdx
            }
            lastBucketTimeMs += bucketsToAdvance * 100L
        }
    }

    // Called on a loop (e.g. 20Hz)
    fun applyDecayAndPublish(decayFactorPerSec: Float, intervalMs: Long) {
        updateBucketsTime(System.currentTimeMillis())
        
        var totalCpm = 0
        var roiCpm = 0
        for (i in 0 until 20) {
            totalCpm += timeBuckets.get(i)
            roiCpm += roiTimeBuckets.get(i)
        }
        totalCpm *= 30
        roiCpm *= 30

        var roiGross = 0
        if (roiStart in 0 until bins && roiEnd in 0 until bins && roiStart <= roiEnd) {
            for (i in roiStart..roiEnd) {
                roiGross += rawCounts.get(i)
            }
        }

        val outSpectrum = FloatArray(bins)
        val outRaw = IntArray(bins)
        
        val fraction = Math.pow(decayFactorPerSec.toDouble(), intervalMs.toDouble() / 1000.0).toFloat()
        
        for (i in 0 until bins) {
            var v = spectrumCounts[i]
            if (decayFactorPerSec < 1.0f) {
                v *= fraction
                if (v < 0.1f) v = 0f
                spectrumCounts[i] = v
            }
            outSpectrum[i] = v
            outRaw[i] = rawCounts.get(i)
        }

        _state.value = SpectrumState(
            spectrumCounts = outSpectrum,
            rawCounts = outRaw,
            totalCpm = totalCpm,
            roiCpm = roiCpm,
            roiGross = roiGross,
            bins = bins
        )
    }

    fun clear() {
        spectrumCounts = FloatArray(2048)
        for(i in 0 until 2048) {
            rawCounts.set(i, 0)
        }
        for(i in 0 until 20) {
            timeBuckets.set(i, 0)
            roiTimeBuckets.set(i, 0)
        }
    }
}
