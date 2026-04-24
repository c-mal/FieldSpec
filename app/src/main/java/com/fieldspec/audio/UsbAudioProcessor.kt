package com.fieldspec.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class UsbAudioProcessor(
    private val audioManager: AudioManager,
    private val pulseDetector: PulseDetector,
    private val binsProvider: () -> Int
) {
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    var isAcquiring = false
    @Volatile var audioPassthroughEnabled = false

    /** The sample rate actually in use. Available after startAcquisition() returns. */
    @Volatile var actualSampleRate: Int = 48000
        private set

    // ────────────────────────────────────────────────────────────────────────
    // Sample rate negotiation — try each rate by actually constructing an
    // AudioRecord (getMinBufferSize alone is insufficient; some drivers accept
    // the query but throw on construction at unsupported rates).
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(
        sampleRate: Int,
        channelConfig: Int,
        audioFormat: Int
    ): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf == AudioRecord.ERROR_BAD_VALUE || minBuf <= 0) return null
            val bufSize = maxOf(minBuf * 4, 8192)
            val record = AudioRecord(
                MediaRecorder.AudioSource.UNPROCESSED,
                sampleRate, channelConfig, audioFormat, bufSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) record
            else { record.release(); null }
        } catch (e: Exception) {
            null
        }
    }

    /** Try rates highest-first; return first (record, rate, bufferSize) that works. */
    @SuppressLint("MissingPermission")
    private fun negotiateAudioRecord(
        channelConfig: Int,
        audioFormat: Int
    ): Triple<AudioRecord, Int, Int> {
        val candidates = intArrayOf(384000, 192000, 96000, 48000)
        for (rate in candidates) {
            val record = buildAudioRecord(rate, channelConfig, audioFormat) ?: continue
            val minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            val bufSize = maxOf(minBuf * 4, 8192)
            return Triple(record, rate, bufSize)
        }
        // Absolute fallback — 48kHz must work on any Android device with a mic
        val minBuf = AudioRecord.getMinBufferSize(48000, channelConfig, audioFormat)
            .let { if (it > 0) it else 4096 }
        val bufSize = maxOf(minBuf * 4, 8192)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            48000, channelConfig, audioFormat, bufSize
        )
        return Triple(record, 48000, bufSize)
    }

    // ────────────────────────────────────────────────────────────────────────
    // AudioTrack (passthrough) — always at 48kHz for speaker compatibility
    // ────────────────────────────────────────────────────────────────────────

    private fun buildPassthroughTrack(sampleRate: Int, audioFormat: Int): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)
            if (minBuf == AudioTrack.ERROR_BAD_VALUE || minBuf <= 0) return null
            val bufSize = maxOf(minBuf * 4, 8192)
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // startAcquisition
    // ────────────────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun startAcquisition() = withContext(Dispatchers.IO) {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Negotiate the best AudioRecord we can get
        val (record, sampleRate, bufferSize) = negotiateAudioRecord(channelConfig, audioFormat)
        audioRecord = record
        actualSampleRate = sampleRate

        // Inform PulseDetector so it can scale its window geometry
        pulseDetector.onSampleRateChanged(sampleRate)

        // Prefer the USB device if one is connected
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val usbInput = inputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }
        usbInput?.let { record.setPreferredDevice(it) }

        // Build passthrough AudioTrack at the negotiated sample rate
        audioTrack = buildPassthroughTrack(sampleRate, audioFormat)
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speaker = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        speaker?.let { audioTrack?.setPreferredDevice(it) }
        try { audioTrack?.play() } catch (e: Exception) { audioTrack = null }

        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record.startRecording()
            isAcquiring = true
            val pcmBuffer = ShortArray(bufferSize)

            while (isActive && isAcquiring) {
                try {
                    val readResult = record.read(pcmBuffer, 0, bufferSize)
                    if (readResult > 0) {
                        pulseDetector.processAudioFrame(pcmBuffer, readResult, binsProvider())
                        if (audioPassthroughEnabled) {
                            try { audioTrack?.write(pcmBuffer, 0, readResult) } catch (e: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
        stopAcquisition()
    }

    fun stopAcquisition() {
        isAcquiring = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        try { audioTrack?.stop() } catch (e: Exception) {}
        try { audioTrack?.release() } catch (e: Exception) {}
        audioTrack = null
    }
}
