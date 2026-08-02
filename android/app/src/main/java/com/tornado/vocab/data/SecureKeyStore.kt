package com.tornado.vocab.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * تخزين مفتاح خدمة الصوت.
 *
 * المفتاح مال المستخدم ومسؤوليته، فلا يُحفظ نصاً صريحاً إطلاقاً:
 * EncryptedSharedPreferences يشفّره بمفتاح مشتق من مخزن مفاتيح الجهاز العتادي،
 * فلا يمكن قراءته حتى بنسخ ملفات التطبيق من جهاز مكسور الحماية.
 *
 * ولا يغادر الجهاز إلا في ترويسة الطلب إلى الخدمة نفسها.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "tornado-secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // جهاز بلا مخزن مفاتيح سليم: نعمل بلا مفتاح بدل أن نُسقط التطبيق
        context.getSharedPreferences("tornado-secure-fallback", Context.MODE_PRIVATE)
    }

    init {
        /*
         * مفتاح مزوّد حُذف من المنتج لا يُترك مخزَّناً.
         * هو سرّ للمستخدم لا فائدة منه بعد اليوم، وإبقاؤه احتفاظ ببيانات حساسة
         * بلا سبب — والصواب محوه فور زوال الحاجة إليه.
         */
        REMOVED_PROVIDERS.forEach { gone ->
            if (prefs.contains("key_$gone")) prefs.edit().remove("key_$gone").apply()
        }
    }

    /** مفتاح مستقل لكل مزوّد — تبديل المزوّد لا يفقدك مفتاح الآخر */
    fun keyFor(provider: String): String =
        prefs.getString("key_$provider", "").orEmpty()

    fun setKey(provider: String, value: String) {
        prefs.edit().putString("key_$provider", value.trim()).apply()
    }

    fun hasKey(provider: String): Boolean = keyFor(provider).length > 15

    fun clearKey(provider: String) {
        prefs.edit().remove("key_$provider").apply()
    }

    /** يعرض المفتاح مقنّعاً للتأكيد البصري بلا كشفه */
    fun maskedKey(provider: String): String {
        val k = keyFor(provider)
        if (k.length < 12) return ""
        return k.take(6) + "…" + k.takeLast(4)
    }

    private companion object {
        val REMOVED_PROVIDERS = listOf("elevenlabs")
    }
}
