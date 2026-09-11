package com.livdub.personal

import android.content.Context

/**
 * تخزين بسيط محلي على الجهاز فقط (SharedPreferences).
 * مفتاح الـ API بيضل عندك على تلفونك ومابينبعت لأي مكان غير Google (Gemini) مباشرة.
 */
object Prefs {
    private const val FILE = "livdub_prefs"
    private const val KEY_API = "api_key"
    private const val KEY_LANG = "target_lang"

    fun saveApiKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_API, key).apply()
    }

    fun getApiKey(ctx: Context): String {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_API, "") ?: ""
    }

    fun saveTargetLang(ctx: Context, code: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, code).apply()
    }

    fun getTargetLang(ctx: Context): String {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LANG, "ar") ?: "ar"
    }
}
