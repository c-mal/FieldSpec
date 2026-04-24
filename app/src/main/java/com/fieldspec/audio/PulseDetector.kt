package com.fieldspec.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pulse detection engine aligned with ImpulseQt's pulsecatcher.py + shapecatcher.py.
 *
 * Detection flow (matches pulsecatcher.py):
 *  1. Write sample to ring buffer at current head
 *  2. Check if sample at [peak position inside window] is the window maximum
 *  3. If so: accumulate (shape mode) or bin (measurement mode)
 *  4. Advance head
 *
 * Shape math (matches functions.py):
 *  - pulse_height  = max(window) - min(window)
 *  - normalise     = subtract mean, divide by abs-max
 *  - distortion    = RMS of element-wise differences, scaled 0..100
 */
class PulseDetector(private val spectrumModel: SpectrumModel) {

    // ── Shape Matching ───────────────────────────────────────────────────────
    var isShapeMatchingEnabled = false
    /** Distortion tolerance 0..100. 50 = moderate. Only active after a template is captured. */
    var tolerance = 50f

    // ── Shape Template ───────────────────────────────────────────────────────
    @Volatile private var shapeTemplate: FloatArray? = null

    // Pulse window geometry
    var expectedLength = 68   // samples in window
    var pretrigger = 16       // samples before the peak inside the window

    // ── Shape Accumulation ───────────────────────────────────────────────────
    @Volatile var isCapturingProfile = false
    @Volatile var profilePulsesCount = 0
    @Volatile var shapeCaptureStartTime = 0L
    @Volatile private var profileAccumulator: FloatArray = FloatArray(expectedLength)

    // ── Audio Ring Buffer ────────────────────────────────────────────────────
    private val histLen = 4096
    private val audioHistory = FloatArray(histLen)
    private var head = 0

    // ── Baseline tracker (very slow DC tracking) ────────────────────────────
    private var baseline = 0f
    private val alpha = 0.0001f

    // ── LLD ─────────────────────────────────────────────────────────────────
    var lldBin = 0

    // ── Dead time counter ────────────────────────────────────────────────────
    // Counts down samples remaining in post-pulse lockout. Set to expectedLength
    // after each detected pulse to prevent the sliding window from re-detecting
    // the same pulse multiple times as it scrolls through the peak region.
    private var deadSamples = 0

    // ── Sample rate scaling ──────────────────────────────────────────────────
    // Base geometry: a ~0.67ms window centred on the peak.
    //   At 48kHz  → 32 samples total, peak at sample 6
    //   At 96kHz  → 64 samples,  peak at 12
    //   At 192kHz → 128 samples, peak at 25
    //   At 384kHz → 256 samples, peak at 51
    // This keeps the display focused on the main pulse body (fast rise +
    // ~300-400µs of exponential decay) without the noisy post-pulse tail.
    private val baseSampleRate    = 48000
    private val baseExpectedLength = 32   // ~0.67ms at 48kHz
    private val basePretrigger    = 6     // peak positioned ~1/5 from the left

    /**
     * Called by UsbAudioProcessor after it negotiates the actual sample rate.
     * Scales window size proportionally. Critically: if a shape template already
     * exists, it is RESAMPLED to the new length rather than discarded — this keeps
     * shape filtering active across restarts, which is essential for suppressing
     * USB codec artifacts that otherwise create false peaks.
     */
    fun onSampleRateChanged(sampleRate: Int) {
        val scale = sampleRate.toFloat() / baseSampleRate
        val newLen = (baseExpectedLength * scale).toInt().coerceAtLeast(16)
        val newPre = (basePretrigger * scale).toInt().coerceAtLeast(3)

        // Resample existing template to new length via linear interpolation
        val existing = shapeTemplate
        if (existing != null && existing.size != newLen) {
            shapeTemplate = resampleTemplate(existing, newLen)
        }

        expectedLength = newLen
        pretrigger = newPre
    }

    /**
     * Linearly interpolate a normalised template from its current length to [newLen].
     * Preserves the shape proportions regardless of sample count.
     */
    private fun resampleTemplate(src: FloatArray, newLen: Int): FloatArray {
        if (src.isEmpty() || newLen <= 0) return src
        val out = FloatArray(newLen)
        val ratio = (src.size - 1).toFloat() / (newLen - 1).toFloat().coerceAtLeast(1f)
        for (i in 0 until newLen) {
            val pos = i * ratio
            val lo = pos.toInt().coerceIn(0, src.size - 1)
            val hi = (lo + 1).coerceIn(0, src.size - 1)
            val frac = pos - lo
            out[i] = src[lo] * (1f - frac) + src[hi] * frac
        }
        return out
    }



    // ────────────────────────────────────────────────────────────────────────
    // Public API
    // ────────────────────────────────────────────────────────────────────────

    /** Load a previously saved normalised template (from DataStore CSV). */
    fun setShapeTemplate(newShape: FloatArray) {
        if (newShape.isEmpty()) return
        shapeTemplate = newShape
        expectedLength = newShape.size
        pretrigger = expectedLength / 4
        isShapeMatchingEnabled = true
    }

    /**
     * Live view of the average being accumulated, or the saved template if idle.
     * Polled every 100ms by the oscilloscope dialog.
     */
    val activeProfileAverage: FloatArray?
        get() {
            val n = profilePulsesCount
            val tmpl = shapeTemplate
            return when {
                n > 0 -> {
                    // Return live in-progress average
                    val acc = profileAccumulator
                    val len = minOf(expectedLength, acc.size)
                    FloatArray(len) { acc[it] / n.toFloat() }
                }
                tmpl != null -> tmpl  // Return saved template when idle
                else -> null
            }
        }

    /** Begin accumulating pulses into the average profile. */
    fun startShapeCapture() {
        // expectedLength and pretrigger are already correctly scaled by onSampleRateChanged()
        // Re-allocate accumulator to match current window size
        profileAccumulator = FloatArray(expectedLength)
        profilePulsesCount = 0
        shapeCaptureStartTime = System.currentTimeMillis()
        isCapturingProfile = true
    }

    /**
     * Called by MainActivity when the 10-second timer fires.
     * Finalises the accumulated average, saves it as the template, and returns it as CSV.
     * Returns null if no pulses were captured.
     */
    fun finalizeAndGetShapeCsv(): String? {
        isCapturingProfile = false
        val n = profilePulsesCount
        if (n == 0) return null

        val len = minOf(expectedLength, profileAccumulator.size)
        // Compute the mean waveform
        val avg = FloatArray(len) { profileAccumulator[it] / n.toFloat() }

        // Normalise to 0..1 for storage
        val norm = normaliseTemplate(avg)

        // Apply immediately as the active template
        shapeTemplate = norm
        isShapeMatchingEnabled = true
        pretrigger = expectedLength / 4

        // NOTE: do NOT reset profilePulsesCount here — activeProfileAverage
        // will continue returning the live average until a new capture begins,
        // which keeps the oscilloscope plot populated.

        val sb = StringBuilder()
        for (i in norm.indices) {
            sb.append(i).append(',').append(norm[i]).append('\n')
        }
        return sb.toString().trimEnd()
    }

    // ────────────────────────────────────────────────────────────────────────
    // Main audio processing — called per PCM frame from UsbAudioProcessor
    // ────────────────────────────────────────────────────────────────────────

    fun processAudioFrame(pcmBuffer: ShortArray, length: Int, bins: Int) {
        val winLen = expectedLength
        val posttrigger = winLen - pretrigger

        // We use 40000f as the max height. 16-bit PCM clips at 32767. 
        // Adding a typical analog baseline undershoot gives a max achievable height of ~36,000-38,000.
        // 40000f scales the physical hardware limitations directly to the right bounds of the graph.
        val binSize = 40000f / bins

        for (i in 0 until length) {
            val raw = pcmBuffer[i].toFloat()

            // Slow DC baseline tracker (same as ImpulseQt)
            baseline = baseline * (1f - alpha) + raw * alpha
            val amplitude = raw - baseline

            // 1. Write to ring buffer
            audioHistory[head] = amplitude

            // Dead-time lockout: still fill the ring buffer (audio continuity)
            // but skip pulse evaluation during the lockout window.
            if (deadSamples > 0) {
                deadSamples--
                head = (head + 1) % histLen
                continue
            }

            // 2. Candidate peak position (fixed offset from head, ImpulseQt: samples[peak])
            val peakIdx = (head - posttrigger + 1 + histLen) % histLen
            val peakCandidate = audioHistory[peakIdx]

            // Quick pre-filter: skip if candidate is below noise floor
            // The CSV dump showed GS-PRO baseline noise hash frequently reaches heights
            // of 40-80. We require candidate peaks to strictly clear this continuous analog noise.
            if (peakCandidate > 100f) {

                // 3. Extract window and find max/min (ImpulseQt: samples[peak] == max(samples))
                var maxVal = -Float.MAX_VALUE
                var minVal = Float.MAX_VALUE
                val window = FloatArray(winLen)
                for (j in 0 until winLen) {
                    val idx = (head - winLen + 1 + j + histLen) % histLen
                    val v = audioHistory[idx]
                    window[j] = v
                    if (v > maxVal) maxVal = v
                    if (v < minVal) minVal = v
                }

                // 4. Peak-is-max check — ImpulseQt: samples[peak] == max(samples)
                //    (epsilon handles float rounding at the peak plateau)
                if (peakCandidate >= maxVal - 0.001f) {
                    val height = maxVal - minVal

                    // ImpulseQt typically uses threshold ~100.
                    // We use 150f to guarantee we clip off the continuous background analog ripple,
                    // which prevents tens of thousands of false triggers from piling up in bin 0.
                    if (height > 150f) {


                        // ── Hardware Clipping Rejection ────────
                        // If the pulse touches the 16-bit PCM ceiling (32767), it is saturated.
                        // Saturated pulses have their tops chopped off, artificially mapping thousands
                        // of completely unique high energies to the exact same "height" of ~35,000.
                        // This exact height cluster is the "phantom peak" that appears everywhere.
                        if (maxVal > 32700f) {
                            deadSamples = winLen
                            continue
                        }

                        // Lock out for winLen samples after each detection.
                        // ImpulseQt relies on the sliding-window geometry preventing
                        // re-detection for clean scintillator pulses. In USB audio on

                        // Android, isochronous clock-sync corrections create brief
                        // step-function transients: without lockout, each slides through
                        // the 128-sample window 128 times → 128 counts at same height →
                        // massive sharp phantom peak. Dead time limits it to 1 count.
                        deadSamples = winLen
                        if (isCapturingProfile) {
                            // Shape capture: peak-align and accumulate
                            val maxIdx = window.indexOfMaxVal()
                            val aligned = alignPulse(window, maxIdx, pretrigger)
                            val acc = profileAccumulator
                            val accLen = minOf(winLen, acc.size)
                            for (j in 0 until accLen) {
                                acc[j] += aligned[j]
                            }
                            profilePulsesCount++
                        } else {
                            // ── Measurement mode ──────────────────────────────────
                            //
                            // Heuristic shape gates run even without a template.
                            // Real NaI(Tl) decay constant τ ≈ 230µs.
                            
                            val peakWinIdx = pretrigger
                            var artifactRejected = false

                            // 1. Slow-decay / Step function rejection
                            // USB packet-boundary steps remain at ~100% for hundreds
                            // of samples. Reject if > 75% peak roughly 166µs later.
                            val slowCheckIdx = peakWinIdx + (winLen / 4)
                            if (slowCheckIdx < winLen) {
                                val postPeakVal = window[slowCheckIdx]
                                if (postPeakVal > maxVal * 0.75f) {
                                    artifactRejected = true
                                }
                            }

                            // 2. Fast-decay / Impulse rejection 
                            // Buffer slip/underrun zero-insertions over a DC offset create
                            // single-sample impulses. At 192kHz the large window dilutes
                            // their RMS distortion so they slip past the shape filter.
                            // A real pulse won't drop below 20% in 20µs.
                            val fastCheckIdx = peakWinIdx + maxOf(1, winLen / 32)
                            if (fastCheckIdx < winLen) {
                                val fastPostPeakVal = window[fastCheckIdx]
                                val baselineThreshold = minVal + (height * 0.20f)
                                if (fastPostPeakVal < baselineThreshold) {
                                    artifactRejected = true
                                }
                            }

                            if (!artifactRejected) {
                                // Shape matching filter (ImpulseQt: distortion check)
                                var pass = true
                                if (isShapeMatchingEnabled) {
                                    val tmpl = shapeTemplate
                                    if (tmpl != null) {
                                        val normPulse = normalisePulse(window)
                                        val normTmpl = normalisePulse(tmpl)
                                        if (distortion(normPulse, normTmpl) > tolerance) {
                                            pass = false
                                        }
                                    }
                                }
                                if (pass) {
                                    // ImpulseQt: bin_index = int(height / bin_size)
                                    val binIndex = (height / binSize).toInt().coerceIn(0, bins - 1)
                                    if (binIndex > lldBin) {
                                        spectrumModel.addPulse(binIndex)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Advance head (ImpulseQt: samples.pop(0); samples.append(...))
            head = (head + 1) % histLen
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // DSP helpers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Shift pulse so its peak is at [targetPeak].
     * Mirrors shapecatcher.py:align_pulse().
     */
    private fun alignPulse(pulse: FloatArray, currentPeak: Int, targetPeak: Int): FloatArray {
        val shift = targetPeak - currentPeak
        val out = FloatArray(pulse.size)
        for (i in pulse.indices) {
            val src = i - shift
            out[i] = if (src in pulse.indices) pulse[src] else 0f
        }
        return out
    }

    /**
     * normalise_pulse from ImpulseQt functions.py:
     *   mean-centre, then divide by abs-max → result in [-1, 1]
     */
    private fun normalisePulse(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples
        val mean = samples.sum() / samples.size
        val demeaned = FloatArray(samples.size) { samples[it] - mean }
        val absMax = demeaned.maxOfOrNull { abs(it) }?.takeIf { it > 0f } ?: 1f
        return FloatArray(demeaned.size) { demeaned[it] / absMax }
    }

    /**
     * distortion from ImpulseQt functions.py:
     *   RMS of squared differences, scaled to 0..100
     */
    private fun distortion(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var sumSq = 0f
        for (i in 0 until n) {
            val diff = a[i] - b[i]
            sumSq += diff * diff
        }
        return (sqrt(sumSq / n) / 2f) * 100f  // 0..2 RMS → 0..100
    }

    /**
     * Normalise a raw average into 0..1 for template storage.
     */
    private fun normaliseTemplate(avg: FloatArray): FloatArray {
        val mn = avg.minOrNull() ?: 0f
        val mx = avg.maxOrNull() ?: 1f
        val range = if (mx == mn) 1f else mx - mn
        return FloatArray(avg.size) { (avg[it] - mn) / range }
    }

    private fun FloatArray.indexOfMaxVal(): Int {
        var maxIdx = 0
        var maxV = this[0]
        for (i in 1 until size) {
            if (this[i] > maxV) { maxV = this[i]; maxIdx = i }
        }
        return maxIdx
    }
}
