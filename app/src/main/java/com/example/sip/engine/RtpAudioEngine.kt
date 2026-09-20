package com.example.sip.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.sin

/**
 * High-performance RTP Audio Engine for MCPTT.
 * Encodes/decodes G.711u (PCMU, 8000 Hz, 20ms frames = 160 samples = 160 bytes).
 * Bound to the MCPTT APN network interface via McpttApnNetworkManager.
 */
class RtpAudioEngine {
    private val scope = CoroutineScope(Dispatchers.IO)

    private var rtpSocket: DatagramSocket? = null
    private var localPort: Int = 6000
    private var isTransmitting = false
    private var isReceiving = false

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var transmitJob: Job? = null
    private var receiveJob: Job? = null

    private val _micAudioLevel = MutableStateFlow(0f)
    val micAudioLevel: StateFlow<Float> = _micAudioLevel.asStateFlow()

    private var remoteMediaIp: String? = null
    private var remoteMediaPort: Int? = null

    private val sampleRate = 8000
    private val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
    private val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun init(localRtpPort: Int, apnManager: McpttApnNetworkManager? = null) {
        try {
            this.localPort = localRtpPort
            rtpSocket?.close()
            val socket = DatagramSocket(localRtpPort)
            apnManager?.bindSocket(socket)
            rtpSocket = socket
            Log.i(TAG, "RTP Socket bound to port $localRtpPort on MCPTT network")
            startAudioPlayback()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind RTP Socket: ${e.message}", e)
        }
    }

    fun setRemoteMediaTarget(host: String, port: Int) {
        this.remoteMediaIp = host
        this.remoteMediaPort = port
        Log.i(TAG, "Configured remote RTP media target: $host:$port")
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
    fun startMicrophoneTransmission(destHost: String? = null, destPort: Int? = null) {
        if (isTransmitting) return
        val targetHost = destHost ?: remoteMediaIp
        val targetPort = destPort ?: remoteMediaPort

        if (targetHost.isNullOrBlank() || targetPort == null || targetPort <= 0) {
            Log.w(TAG, "Cannot start RTP transmission: target ($targetHost:$targetPort) is invalid")
            return
        }

        isTransmitting = true
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
                val pcmBuffer = ShortArray(160) // 20ms frames at 8kHz = 160 samples
                val targetAddr = InetAddress.getByName(targetHost)
                var sequenceNum = 0
                var timestamp = 0L
                val ssrc = 0x12345678

                Log.i(TAG, "Microphone RTP streaming started to $targetHost:$targetPort (PCMU/8000)")

                while (isTransmitting && isActive) {
                    val readSamples = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: 0
                    if (readSamples > 0) {
                        // Calculate audio amplitude for UI visualizer
                        var sum = 0.0
                        for (i in 0 until readSamples) {
                            sum += pcmBuffer[i] * pcmBuffer[i]
                        }
                        val amplitude = Math.sqrt(sum / readSamples)
                        _micAudioLevel.value = (amplitude / 32768.0).toFloat().coerceIn(0f, 1f)

                        // Format RTP packet: 12-byte header + PCMU payload (1 byte per sample)
                        val rtpPacketData = ByteArray(12 + readSamples)
                        // V=2, P=0, X=0, CC=0 -> 0x80
                        rtpPacketData[0] = 0x80.toByte()
                        // M=0, PT=0 (PCMU) -> 0x00
                        rtpPacketData[1] = 0x00.toByte()
                        // Sequence Number
                        rtpPacketData[2] = ((sequenceNum shr 8) and 0xFF).toByte()
                        rtpPacketData[3] = (sequenceNum and 0xFF).toByte()
                        // Timestamp
                        rtpPacketData[4] = ((timestamp shr 24) and 0xFF).toByte()
                        rtpPacketData[5] = ((timestamp shr 16) and 0xFF).toByte()
                        rtpPacketData[6] = ((timestamp shr 8) and 0xFF).toByte()
                        rtpPacketData[7] = (timestamp and 0xFF).toByte()
                        // SSRC
                        rtpPacketData[8] = ((ssrc shr 24) and 0xFF).toByte()
                        rtpPacketData[9] = ((ssrc shr 16) and 0xFF).toByte()
                        rtpPacketData[10] = ((ssrc shr 8) and 0xFF).toByte()
                        rtpPacketData[11] = (ssrc and 0xFF).toByte()

                        // Encode PCM to G.711u
                        for (i in 0 until readSamples) {
                            rtpPacketData[12 + i] = linear16ToUlaw(pcmBuffer[i])
                        }

                        val packet = DatagramPacket(
                            rtpPacketData,
                            rtpPacketData.size,
                            targetAddr,
                            targetPort
                        )
                        rtpSocket?.send(packet)

                        sequenceNum = (sequenceNum + 1) and 0xFFFF
                        timestamp += readSamples
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Transmission loop error: ${e.message}", e)
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
                audioTrack = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    channelConfigOut,
                    audioFormat,
                    minBufSize * 2,
                    AudioTrack.MODE_STREAM
                )
                audioTrack?.play()

                val recvBuf = ByteArray(1500)
                val packet = DatagramPacket(recvBuf, recvBuf.size)

                while (isReceiving && isActive) {
                    val socket = rtpSocket ?: break
                    socket.receive(packet)
                    val len = packet.length
                    if (len > 12) {
                        val payloadLen = len - 12
                        val pcmShorts = ShortArray(payloadLen)

                        // Decode G.711u to linear PCM 16-bit
                        for (i in 0 until payloadLen) {
                            pcmShorts[i] = ulawToLinear16(recvBuf[12 + i])
                        }

                        audioTrack?.write(pcmShorts, 0, pcmShorts.size)
                    }
                }
            } catch (e: Exception) {
                if (isReceiving) {
                    Log.e(TAG, "RTP receive playback error: ${e.message}")
                }
            } finally {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                    audioTrack = null
                } catch (e: Exception) {
                    Log.e(TAG, "Clean up speaker error: ${e.message}")
                }
            }
        }
    }

    fun stopAudioPlayback() {
        isReceiving = false
        receiveJob?.cancel()
        receiveJob = null
    }

    fun close() {
        stopMicrophoneTransmission()
        stopAudioPlayback()
        rtpSocket?.close()
        rtpSocket = null
    }

    companion object {
        private const val TAG = "RtpAudioEngine"

        /**
         * Converts 16-bit linear PCM sample to 8-bit G.711 mu-law byte.
         */
        fun linear16ToUlaw(sample: Short): Byte {
            var pcm = sample.toInt()
            val sign = if (pcm < 0) {
                pcm = -pcm
                0x80
            } else {
                0x00
            }

            pcm += 132 // 0x84
            if (pcm > 32767) pcm = 32767

            var exponent = 7
            var expMask = 0x4000
            while ((pcm and expMask) == 0 && exponent > 0) {
                exponent--
                expMask = expMask shr 1
            }

            val mantissa = (pcm shr (exponent + 3)) and 0x0F
            val ulaw = (sign or (exponent shl 4) or mantissa) xor 0xFF
            return ulaw.toByte()
        }

        /**
         * Converts 8-bit G.711 mu-law byte to 16-bit linear PCM sample.
         */
        fun ulawToLinear16(ulawByte: Byte): Short {
            val ulaw = (ulawByte.toInt() xor 0xFF) and 0xFF
            val sign = ulaw and 0x80
            val exponent = (ulaw shr 4) and 0x07
            val mantissa = ulaw and 0x0F

            var sample = ((mantissa shl 3) + 132) shl exponent
            sample -= 132

            return if (sign != 0) (-sample).toShort() else sample.toShort()
        }
    }
}
