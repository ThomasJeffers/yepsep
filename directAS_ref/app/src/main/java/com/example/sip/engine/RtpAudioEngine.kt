package com.example.sip.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.net.Network
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.math.sin

class RtpAudioEngine {
    private val scope = CoroutineScope(Dispatchers.IO)

    private var rtpSocket: DatagramSocket? = null
    @Volatile private var isTransmitting = false
    @Volatile private var isReceiving = false

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var transmitJob: Job? = null
    private var receiveJob: Job? = null
    private var keepaliveJob: Job? = null

    private val _micAudioLevel = MutableStateFlow(0f)
    val micAudioLevel: StateFlow<Float> = _micAudioLevel.asStateFlow()

    private val sampleRate = 8000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val samplesPerPacket = 160
    private var ssrc = kotlin.random.Random.nextInt() or 1
    private var markerNext = true
    private var audioManager: AudioManager? = null
    @Volatile private var rebuildTrackRequested = false
    private val trackLock = Any()

    private val _boundPort = MutableStateFlow(0)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()
    private val _rtpTxCount = MutableStateFlow(0L)
    val rtpTxCount: StateFlow<Long> = _rtpTxCount.asStateFlow()
    private val _rtpRxCount = MutableStateFlow(0L)
    val rtpRxCount: StateFlow<Long> = _rtpRxCount.asStateFlow()

    fun init(preferredPort: Int = 0, network: Network? = null, context: Context? = null): Int {
        if (context != null) {
            audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }
        try {
            rtpSocket?.close()
            val ds = DatagramSocket(null)
            ds.reuseAddress = true
            if (network != null) {
                try {
                    network.bindSocket(ds)
                    Log.d(TAG, "RTP socket bound to selected Network before port bind")
                } catch (e: Exception) {
                    Log.w(TAG, "RTP network bindSocket failed: ${e.message}")
                }
            }
            val port = bindEvenRtpPort(ds, preferredPort)
            ds.soTimeout = 1000
            rtpSocket = ds
            ssrc = kotlin.random.Random.nextInt() or 1
            _boundPort.value = port
            _rtpTxCount.value = 0
            _rtpRxCount.value = 0
            Log.i(TAG, "RTP SOCKET BOUND localPort=$port ssrc=${ssrc.toUInt()}")
            return port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTP Socket: ${e.message}")
            _boundPort.value = 0
            return 0
        }
    }

    private fun bindEvenRtpPort(ds: DatagramSocket, preferred: Int): Int {
        val start = if (preferred >= 1024 && preferred % 2 == 0) {
            preferred
        } else {
            40000 + kotlin.random.Random.nextInt(0, 800) * 2
        }
        var lastError: Exception? = null
        for (p in start until start + 400 step 2) {
            try {
                ds.bind(InetSocketAddress(p))
                return ds.localPort
            } catch (e: Exception) {
                lastError = e
            }
        }
        ds.bind(InetSocketAddress(0))
        Log.w(TAG, "RTP fell back to ephemeral port ${ds.localPort} (${lastError?.message})")
        return ds.localPort
    }

    fun playGrantTone() {
        scope.launch {
            playToneSequence(intArrayOf(880, 1200), intArrayOf(80, 100))
        }
    }

    fun playReleaseTone() {
        scope.launch {
            playToneSequence(intArrayOf(1000, 600), intArrayOf(60, 80))
        }
    }

    fun playEmergencyTone() {
        scope.launch {
            playToneSequence(intArrayOf(1500, 1800, 1500, 1800), intArrayOf(100, 100, 100, 100))
        }
    }

    private fun playToneSequence(frequencies: IntArray, durationsMs: IntArray) {
        try {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfigOut,
                audioFormat
            )
            val track = AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelConfigOut,
                audioFormat,
                minBufSize * 2,
                AudioTrack.MODE_STREAM
            )
            track.play()

            for (i in frequencies.indices) {
                val freq = frequencies[i]
                val durMs = durationsMs[i]
                val numSamples = (sampleRate * durMs / 1000)
                val buffer = ShortArray(numSamples)

                for (j in 0 until numSamples) {
                    val angle = 2.0 * Math.PI * j / (sampleRate / freq)
                    buffer[j] = (sin(angle) * 20000).toInt().toShort()
                }
                track.write(buffer, 0, numSamples)
            }

            track.stop()
            track.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing tone: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun startMicrophoneTransmission(destHost: String, destPort: Int) {
        if (isTransmitting) return
        isTransmitting = true
        markerNext = true

        transmitJob = scope.launch {
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                channelConfigIn,
                audioFormat
            )

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfigIn,
                    audioFormat,
                    minBufSize * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize")
                    isTransmitting = false
                    return@launch
                }

                audioRecord?.startRecording()
                val buffer = ShortArray(samplesPerPacket)
                val targetAddr = InetAddress.getByName(destHost)
                var sequenceNum = 0
                var timestamp = 0L
                Log.i(TAG, "Mic TX started -> $destHost:$destPort PCMU/8000")

                while (isTransmitting && isActive) {
                    val readSamples = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSamples > 0) {
                        var sum = 0.0
                        for (i in 0 until readSamples) {
                            sum += buffer[i] * buffer[i]
                        }
                        val amplitude = Math.sqrt(sum / readSamples)
                        _micAudioLevel.value = (amplitude / 32768.0).toFloat().coerceIn(0f, 1f)

                        sendPcmuFrame(buffer, readSamples, targetAddr, destPort, sequenceNum, timestamp)
                        sequenceNum = (sequenceNum + 1) and 0xFFFF
                        timestamp += readSamples
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transmission loop error: ${e.message}")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                } catch (e: Exception) {
                    Log.e(TAG, "Clean up mic error: ${e.message}")
                }
                _micAudioLevel.value = 0f
                isTransmitting = false
            }
        }
    }

    fun stopMicrophoneTransmission() {
        isTransmitting = false
        transmitJob?.cancel()
        transmitJob = null
        _micAudioLevel.value = 0f
        applyPlaybackRouting()
        rebuildTrackRequested = true
    }

    fun startNatKeepalive(destHost: String, destPort: Int) {
        if (keepaliveJob?.isActive == true) return
        keepaliveJob = scope.launch {
            try {
                val targetAddr = InetAddress.getByName(destHost)
                val silence = ShortArray(samplesPerPacket)
                var sequenceNum = 40000
                var timestamp = 0L
                Log.i(TAG, "RTP NAT keepalive -> $destHost:$destPort")
                while (isActive && isReceiving) {
                    if (!isTransmitting) {
                        sendPcmuFrame(silence, samplesPerPacket, targetAddr, destPort, sequenceNum, timestamp)
                        sequenceNum = (sequenceNum + 1) and 0xFFFF
                        timestamp += samplesPerPacket
                    }
                    delay(500)
                }
            } catch (e: Exception) {
                Log.w(TAG, "NAT keepalive ended: ${e.message}")
            }
        }
    }

    fun stopNatKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    fun startAudioPlayback() {
        if (isReceiving) return
        isReceiving = true

        receiveJob = scope.launch {
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelConfigOut,
                audioFormat
            )

            try {
                applyPlaybackRouting()
                audioTrack = buildPlaybackTrack(minBufSize)
                audioTrack?.play()

                val recvBuf = ByteArray(2048)
                val packet = DatagramPacket(recvBuf, recvBuf.size)
                Log.i(TAG, "RTP playback started")

                while (isReceiving && isActive) {
                    if (rebuildTrackRequested) {
                        rebuildTrackRequested = false
                        synchronized(trackLock) {
                            try {
                                audioTrack?.stop()
                                audioTrack?.release()
                            } catch (_: Exception) {
                            }
                            audioTrack = buildPlaybackTrack(minBufSize)
                            applyPlaybackRouting()
                            audioTrack?.play()
                            Log.i(TAG, "RTP AudioTrack rebuilt after PTT (speaker routing)")
                        }
                    }
                    try {
                        rtpSocket?.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (isTransmitting) continue
                    val len = packet.length
                    if (len <= 12) continue
                    _rtpRxCount.value = _rtpRxCount.value + 1
                    if (_rtpRxCount.value == 1L) {
                        Log.i(TAG, "RTP RX first packet from ${packet.address.hostAddress}:${packet.port} len=$len")
                    }
                    val payloadLen = len - 12
                    val pcmShorts = when {
                        payloadLen == samplesPerPacket -> {
                            ShortArray(payloadLen) { i -> ulawToLinear(recvBuf[12 + i].toInt() and 0xFF) }
                        }
                        payloadLen == samplesPerPacket * 2 -> {
                            ShortArray(samplesPerPacket) { i ->
                                val low = recvBuf[12 + i * 2].toInt() and 0xFF
                                val high = recvBuf[12 + i * 2 + 1].toInt()
                                ((high shl 8) or low).toShort()
                            }
                        }
                        else -> {
                            ShortArray(payloadLen) { i -> ulawToLinear(recvBuf[12 + i].toInt() and 0xFF) }
                        }
                    }
                    synchronized(trackLock) {
                        audioTrack?.write(pcmShorts, 0, pcmShorts.size)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "RTP receive playback error: ${e.message}")
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                } catch (e: Exception) {
                    Log.e(TAG, "Clean up speaker error: ${e.message}")
                }
                isReceiving = false
            }
        }
    }

    fun stopAudioPlayback() {
        isReceiving = false
        receiveJob?.cancel()
        receiveJob = null
    }

    fun applyPlaybackRouting() {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            val stream = AudioManager.STREAM_VOICE_CALL
            val max = am.getStreamMaxVolume(stream)
            if (max > 0) {
                am.setStreamVolume(stream, max, 0)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .build()
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, stream, AudioManager.AUDIOFOCUS_GAIN)
            }
            synchronized(trackLock) {
                audioTrack?.setVolume(1f)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback routing: ${e.message}")
        }
    }

    private fun buildPlaybackTrack(minBufSize: Int): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfigOut)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.setVolume(1f)
        return track
    }

    fun close() {
        stopMicrophoneTransmission()
        stopNatKeepalive()
        stopAudioPlayback()
        rtpSocket?.close()
        rtpSocket = null
    }

    private fun sendPcmuFrame(
        samples: ShortArray,
        count: Int,
        dest: InetAddress,
        destPort: Int,
        sequenceNum: Int,
        timestamp: Long
    ) {
        val payloadLen = count.coerceAtMost(samples.size)
        val rtp = ByteArray(12 + payloadLen)
        rtp[0] = 0x80.toByte()
        rtp[1] = if (markerNext) 0x80.toByte() else 0x00
        markerNext = false
        rtp[2] = ((sequenceNum shr 8) and 0xFF).toByte()
        rtp[3] = (sequenceNum and 0xFF).toByte()
        rtp[4] = ((timestamp shr 24) and 0xFF).toByte()
        rtp[5] = ((timestamp shr 16) and 0xFF).toByte()
        rtp[6] = ((timestamp shr 8) and 0xFF).toByte()
        rtp[7] = (timestamp and 0xFF).toByte()
        rtp[8] = ((ssrc shr 24) and 0xFF).toByte()
        rtp[9] = ((ssrc shr 16) and 0xFF).toByte()
        rtp[10] = ((ssrc shr 8) and 0xFF).toByte()
        rtp[11] = (ssrc and 0xFF).toByte()
        for (i in 0 until payloadLen) {
            rtp[12 + i] = linearToUlaw(samples[i])
        }
        val packet = DatagramPacket(rtp, rtp.size, dest, destPort)
        rtpSocket?.send(packet)
        _rtpTxCount.value = _rtpTxCount.value + 1
    }

    companion object {
        private const val TAG = "RtpAudioEngine"
        private const val BIAS = 0x84
        private const val CLIP = 32635

        fun linearToUlaw(sample: Short): Byte {
            var pcm = sample.toInt()
            val mask = if (pcm < 0) {
                pcm = -pcm
                0x7F
            } else {
                0xFF
            }
            if (pcm > CLIP) pcm = CLIP
            pcm += BIAS
            var exponent = 7
            var expMask = 0x4000
            while (exponent > 0 && (pcm and expMask) == 0) {
                exponent--
                expMask = expMask shr 1
            }
            val mantissa = (pcm shr (exponent + 3)) and 0x0F
            return (((exponent shl 4) or mantissa).inv() and mask).toByte()
        }

        fun ulawToLinear(ulawByte: Int): Short {
            val u = (ulawByte.inv() and 0xFF)
            val t = (((u and 0x0F) shl 3) + BIAS) shl (u shr 4 and 0x07)
            return (if ((u and 0x80) != 0) (BIAS - t) else (t - BIAS)).toShort()
        }
    }
}
