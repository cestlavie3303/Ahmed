package com.livdub.personal

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * عميل بسيط لـ Gemini Live API (BidiGenerateContent) بوضع الترجمة الفورية
 * صوت-إلى-صوت (gemini-3.5-live-translate-preview).
 *
 * الصوت المُرسل: PCM 16-bit, 16kHz, mono.
 * الصوت المستلم: PCM 16-bit, 24kHz, mono.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val targetLanguageCode: String,
    private val onAudioChunk: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        // ملاحظة: "gemini-3.5-live-translate-preview" (Live Translate) هو preview مقيّد بـ allowlist
        // من Google وما بيكون متاح تلقائياً لكل مشروع. بدلاً منه منستخدم موديل صوت-حي متاح فعلياً
        // (bidiGenerateContent) ومنوجهه بتعليمات نظام ليشتغل كمترجم فوري صوت-لصوت.
        private const val MODEL = "gemini-2.5-flash-native-audio-preview-09-2025"

        private val LANG_NAMES = mapOf(
            "ar" to "العربية", "en" to "الإنجليزية", "fr" to "الفرنسية",
            "es" to "الإسبانية", "de" to "الألمانية", "tr" to "التركية",
            "ru" to "الروسية", "fa" to "الفارسية", "hi" to "الهندية",
            "zh-Hans" to "الصينية المبسطة", "ja" to "اليابانية", "ko" to "الكورية",
            "it" to "الإيطالية", "pt-BR" to "البرتغالية", "ur" to "الأردية"
        )
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // اتصال مستمر (streaming)
        .build()

    @Volatile
    var isReady: Boolean = false
        private set

    fun connect() {
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                sendSetupMessage(ws)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                isReady = false
                onStatus("خطأ اتصال: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                isReady = false
                onStatus("تم إغلاق الاتصال")
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val langName = LANG_NAMES[targetLanguageCode] ?: targetLanguageCode
        val instruction = "أنت مترجم فوري صوت-إلى-صوت فقط. بيوصلك كلام منطوق بأي لغة، ومهمتك الوحيدة " +
            "إنك تنطق نفس الكلام مترجم إلى $langName (كود اللغة: $targetLanguageCode) فوراً وبنفس المعنى والنبرة. " +
            "ممنوع تضيف أي كلمة، رأي، تعليق، أو تكرار للكلام الأصلي — بس الترجمة المنطوقة وبس. " +
            "لا تتوقف أو تصمت، ترجم كل جملة توصلك أولاً بأول."

        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", "models/$MODEL")
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", listOf("AUDIO"))
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", org.json.JSONArray().apply {
                        put(JSONObject().apply { put("text", instruction) })
                    })
                })
            })
        }
        ws.send(setup.toString())
    }

    private fun handleServerMessage(text: String) {
        try {
            val json = JSONObject(text)

            if (json.has("setupComplete")) {
                isReady = true
                onStatus("متصل — جاهز للدبلجة")
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return
            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    val inlineData = part.optJSONObject("inlineData")
                    val data = inlineData?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        val bytes = Base64.decode(data, Base64.NO_WRAP)
                        onAudioChunk(bytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing server message", e)
        }
    }

    /** بعث حزمة صوت خام (PCM16, 16kHz, mono) للترجمة. */
    fun sendAudioChunk(chunk: ByteArray) {
        val ws = webSocket ?: return
        if (!isReady) return
        val b64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", b64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }
        ws.send(msg.toString())
    }

    fun close() {
        isReady = false
        webSocket?.close(1000, "done")
        webSocket = null
    }
}
