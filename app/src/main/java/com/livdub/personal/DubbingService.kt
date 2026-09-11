package com.livdub.personal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class DubbingService : Service() {

    companion object {
        const val CHANNEL_ID = "livdub_channel"
        const val NOTIF_ID = 1001

        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_TARGET_LANG = "target_lang"
        const val EXTRA_MODE = "mode" // "mic" or "system"
        const val EXTRA_PROJECTION_RESULT_CODE = "proj_result_code"
        const val EXTRA_PROJECTION_DATA = "proj_data"

        const val ACTION_STOP = "com.livdub.personal.STOP"

        private const val SAMPLE_RATE_IN = 16000
        private const val SAMPLE_RATE_OUT = 24000
        // 100ms chunk at 16kHz, 16-bit mono = 3200 bytes, كما توصي وثائق Gemini Live Translate
        private const val CHUNK_BYTES = 3200
    }

    private var geminiClient: GeminiLiveClient? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var mediaProjection: MediaProjection? = null
    private val running = AtomicBoolean(false)
    private var captureThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال…"))

        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        val targetLang = intent?.getStringExtra(EXTRA_TARGET_LANG) ?: "ar"
        val mode = intent?.getStringExtra(EXTRA_MODE) ?: "mic"

        if (apiKey.isBlank()) {
            updateNotification("مفتاح Gemini API غير موجود")
            stopSelf()
            return START_NOT_STICKY
        }

        if (mode == "system") {
            val resultCode = intent?.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, -1) ?: -1
            val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (data == null || resultCode == -1) {
                updateNotification("تصريح التقاط الصوت مفقود")
                stopSelf()
                return START_NOT_STICKY
            }
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, data)
        }

        startPlayback()
        startGemini(apiKey, targetLang)
        startCapture(mode)

        return START_STICKY
    }

    private fun startGemini(apiKey: String, targetLang: String) {
        geminiClient = GeminiLiveClient(
            apiKey = apiKey,
            targetLanguageCode = targetLang,
            onAudioChunk = { bytes -> playChunk(bytes) },
            onStatus = { status -> updateNotification(status) }
        )
        geminiClient?.connect()
    }

    @Suppress("MissingPermission")
    private fun startCapture(mode: String) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf, CHUNK_BYTES * 4)

        audioRecord = if (mode == "system" && mediaProjection != null) {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_IN)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
        } else {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE_IN,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }

        audioRecord?.startRecording()
        running.set(true)

        captureThread = Thread {
            val buffer = ByteArray(CHUNK_BYTES)
            while (running.get()) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    val toSend = if (read == buffer.size) buffer else buffer.copyOf(read)
                    geminiClient?.sendAudioChunk(toSend)
                }
            }
        }.apply { start() }
    }

    private fun startPlayback() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
    }

    private fun playChunk(bytes: ByteArray) {
        audioTrack?.write(bytes, 0, bytes.size)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, DubbingService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, getString(R.string.btn_stop), stopPending)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running.set(false)
        captureThread?.join(500)
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        geminiClient?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
