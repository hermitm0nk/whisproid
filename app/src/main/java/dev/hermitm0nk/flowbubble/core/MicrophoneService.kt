package dev.hermitm0nk.flowbubble.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import dev.hermitm0nk.flowbubble.data.SettingsStore
import dev.hermitm0nk.flowbubble.data.HistoryStore
import dev.hermitm0nk.flowbubble.ui.MainActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Started by a visible Activity so Android 11–16 grants microphone while-in-use access. */
class MicrophoneService : Service() {
    companion object {
        const val ACTION_START = "dev.hermitm0nk.flowbubble.START"
        const val ACTION_STOP = "dev.hermitm0nk.flowbubble.STOP"
        @Volatile var instance: MicrophoneService? = null
            private set
    }
    interface Listener {
        fun onState(state: String)
        fun onTranscript(text: String)
        fun onFailure(message: String)
    }
    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private var session: Session? = null

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val channel = NotificationChannel("ready", "Dictation ready", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "ready").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Whisproid ready").setContentText("Tap a text field to dictate")
            .setContentIntent(open).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(17, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(17, notification)
        // Restarting a microphone FGS from background can violate Android 14+ while-in-use rules.
        return START_NOT_STICKY
    }
    fun begin(): Boolean {
        if (session != null) return false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener?.onFailure("Grant microphone permission in the app first"); return false
        }
        val apiKey = SettingsStore(this).apiKey
        if (apiKey.isBlank()) { listener?.onFailure("Add an API key in Whisproid settings"); return false }
        val next = Session(apiKey)
        session = next
        listener?.onState("recording")
        next.start()
        return true
    }
    fun finish() { session?.finish() }
    fun cancel() { session?.cancel() }
    private fun completed(item: Session, text: String?, error: String?) {
        main.post {
            if (session !== item) return@post
            session = null
            when {
                error != null -> {
                    if (!text.isNullOrBlank()) HistoryStore(this).use { it.add(text) }
                    listener?.onFailure(error + if (!text.isNullOrBlank()) "; partial transcript saved in History" else "")
                }
                text.isNullOrBlank() -> listener?.onFailure("No speech was transcribed")
                else -> listener?.onTranscript(text)
            }
            listener?.onState("ready")
        }
    }
    override fun onDestroy() {
        session?.cancel(); session = null; listener = null; instance = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private inner class Session(private val apiKey: String) {
        private val lock = Any()
        private val pending = ArrayDeque<String>()
        private val completion = TranscriptionCompletion()
        private var recorder: AudioRecord? = null
        private var socket: WebSocket? = null
        private var setup = false
        private var ended = false
        private var stopped = false
        private var done = false
        private var waitingForLast = false
        private val maxLength = Runnable { finish() }
        private val setupTimeout = Runnable { fail("Live API connection timed out before setup") }
        private val timeout = Runnable { finishResult() }

        fun start() {
            var captureStarted = false
            try {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    throw SecurityException("Microphone permission was revoked")
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("16 kHz microphone unavailable")
                val audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                recorder = audio
                if (audio.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Microphone unavailable")
                audio.startRecording()
                val request = Request.Builder().url(liveEndpoint(apiKey)).build()
                val connection = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Manual VAD marks button press and release. SMART yields polished dictation.
                    val config = JSONObject().put("setup", JSONObject()
                        .put("model", "models/gemini-3.5-transcribe-live")
                        .put("generationConfig", JSONObject().put("responseModalities", org.json.JSONArray().put("TEXT")))
                        .put("realtimeInputConfig", JSONObject().put("automaticActivityDetection", JSONObject().put("disabled", true)))
                        .put("inputAudioTranscription", JSONObject().put("mode", "SMART")))
                    synchronized(lock) {
                        if (done) { webSocket.cancel(); return }
                        webSocket.send(config.toString())
                    }
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val message = JSONObject(text)
                        synchronized(lock) {
                            if (done) return
                            if (message.has("setupComplete")) {
                                setup = true
                                main.removeCallbacks(setupTimeout)
                                webSocket.send("{\"realtimeInput\":{\"activityStart\":{}}}")
                                pending.forEach { sendAudio(webSocket, it) }
                                pending.clear()
                                if (ended) sendEnd(webSocket)
                            }
                            val content = message.optJSONObject("serverContent")
                            val transcript = content?.optJSONObject("inputTranscription")?.optString("text")?.trim().orEmpty()
                            if (completion.addFinal(transcript)) scheduleResult(2000)
                            if (content?.optBoolean("turnComplete") == true) {
                                if (completion.markTurnComplete()) scheduleResult(2000)
                            }
                        }
                    } catch (_: Exception) { /* Malformed server frames cannot become text. */ }
                }
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    onMessage(webSocket, bytes.utf8())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail("Live API connection failed${response?.code?.let { " (HTTP $it)" } ?: ""}: ${t.message ?: "network error"}")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    synchronized(lock) {
                        if (done) return
                        if (completion.canCommitOnClose(code)) scheduleResult(100)
                        else fail("Live API closed before completion (code $code): $reason")
                    }
                }
                })
                synchronized(lock) {
                    if (done) connection.cancel()
                    else socket = connection
                }
                if (synchronized(lock) { done }) {
                    audio.release(); recorder = null; return
                }
                Thread({ capture(audio) }, "flowbubble-capture").start()
                captureStarted = true
                if (!synchronized(lock) { done }) {
                    main.postDelayed(maxLength, 9 * 60_000L + 45_000L)
                    main.postDelayed(setupTimeout, 15_000)
                }
            } catch (e: Exception) {
                if (!captureStarted) { try { recorder?.release() } catch (_: Exception) {}; recorder = null }
                fail("Could not start microphone: ${e.message}")
            }
        }
        private fun capture(audio: AudioRecord) {
            val buffer = ByteArray(3200) // 100 ms of 16-bit PCM mono at 16 kHz.
            try {
                while (true) {
                    val shouldStop = synchronized(lock) { stopped || done }
                    if (shouldStop) break
                    val count = audio.read(buffer, 0, buffer.size)
                    if (count < 0) {
                        val expectedStop = synchronized(lock) { stopped || done }
                        if (!expectedStop) fail("Microphone read failed ($count)")
                        break
                    }
                    if (count > 0) synchronized(lock) {
                        if (!done && !stopped) {
                            val encoded = Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)
                            if (setup) socket?.let { sendAudio(it, encoded) }
                            else if (pending.size < 200) pending.addLast(encoded)
                            else fail("Live API setup too slow; recording stopped before audio could be lost")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!synchronized(lock) { stopped || done }) fail("Microphone stopped unexpectedly: ${e.message}")
            }
            finally { audio.release(); synchronized(lock) { if (ended && setup && !done) socket?.let { sendEnd(it) } } }
        }
        private fun sendAudio(ws: WebSocket, base64: String) {
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                .put("data", base64).put("mimeType", "audio/pcm;rate=16000"))).toString())
        }
        private fun sendEnd(ws: WebSocket) {
            if (waitingForLast) return
            waitingForLast = true
            completion.markEndSent()
            if (!ws.send("{\"realtimeInput\":{\"activityEnd\":{}}}")) {
                fail("Live API could not send the end of speech")
                return
            }
            scheduleResult(12_000)
        }
        fun finish() {
            synchronized(lock) {
                if (ended || done) return
                ended = true; stopped = true
                main.post { listener?.onState("transcribing") }
                try { recorder?.stop() } catch (_: Exception) {}
                // If socket setup takes too long, finish with a visible error rather than hanging.
                scheduleResult(15_000)
            }
        }
        fun cancel() {
            synchronized(lock) { if (done) return; done = true; stopped = true; try { recorder?.stop() } catch (_: Exception) {} }
            socket?.cancel(); main.removeCallbacks(maxLength); main.removeCallbacks(setupTimeout); main.removeCallbacks(timeout)
            main.post { if (session === this) { session = null; listener?.onState("ready") } }
        }
        private fun scheduleResult(delayMs: Long) { main.removeCallbacks(timeout); main.postDelayed(timeout, delayMs) }
        private fun finishResult() {
            val ready = synchronized(lock) { setup to completion.canCommit() }
            if (!ready.first || !ready.second) {
                fail(if (!ready.first) "Live API did not complete setup" else "Live API did not confirm final speech")
                return
            }
            val result: String
            synchronized(lock) {
                if (done) return
                done = true; stopped = true
                result = completion.text()
            }
            try { recorder?.stop() } catch (_: Exception) {}
            socket?.close(1000, "done"); main.removeCallbacks(maxLength); main.removeCallbacks(setupTimeout)
            completed(this, result, null)
        }
        private fun fail(message: String) {
            val partial = synchronized(lock) {
                if (done) return
                done = true; stopped = true
                try { recorder?.stop() } catch (_: Exception) {}
                completion.text()
            }
            socket?.cancel(); main.removeCallbacks(maxLength); main.removeCallbacks(setupTimeout); main.removeCallbacks(timeout)
            completed(this, partial, message)
        }
    }
}
