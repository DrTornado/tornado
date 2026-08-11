package com.tornado.vocab.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tornado.vocab.tornado
import java.util.concurrent.TimeUnit

/**
 * يجلب البطاقات الجاهزة والتطبيق مغلق.
 *
 * حلقةُ الاستطلاع داخل التطبيق تعمل ما دام مفتوحاً، وأندرويد يوقفها حين
 * تُطفأ الشاشة أو يُغلق التطبيق — فتبقى البطاقة المكتوبة في المستودع حتى
 * يفتحه صاحبها. وهذا العامل يوقظه النظام دورياً فيجلبها بلا فتح.
 *
 * وهو ضيّق العمل عمداً:
 *   • سحبٌ فقط — لا يكتب في مكتبة المستخدم ولا يرفع شيئاً
 *   • لا يعمل إلا بشبكة، ولا يعمل إن لم تكن كلمةٌ تنتظر بطاقتها
 *   • لا ينزّل إلا الشريحة التي تغيّرت بصمتها — بضعة كيلوبايتات
 *
 * وأقلّ دورةٍ يسمح بها أندرويد ربع ساعة، وهو يزيدها بحسب حالة البطارية.
 * فليست بديلاً عن الاستطلاع داخل التطبيق بل شبكةُ أمانٍ تحته.
 */
class CardFetchWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /*
     * يقول ماذا فعل في كل مسار.
     *
     * كان يخرج صامتاً في كلّ حالاته، فلم أستطع أن أُثبت أنه اشتغل أصلاً حين
     * أوقظه النظام في الفحص — والعملُ الذي لا يُثبَت لا يُوثق به. والسطر
     * الواحد في السجلّ ثمنٌ زهيد مقابل أن يُعرف ما جرى.
     */
    override suspend fun doWork(): Result = runCatching {
        val app = applicationContext.tornado
        val enrich = app.enrichSync
        if (!enrich.canPull) {
            Log.i(TAG, "لا مزامنة — المستودع أو الرمز غير مُعدّ")
            return Result.success()
        }

        // لا نسأل الشبكة إن لم يكن ثمّة ما ينتظر
        val written = enrich.curatedWordSet()
        val waiting = app.repository.allWords()
            .count { it.word.trim().lowercase() !in written }
        if (waiting == 0) {
            Log.i(TAG, "لا شيء ينتظر بطاقة — لا طلب")
            return Result.success()
        }

        enrich.repo = app.settings.syncRepo()
        val fresh = enrich.sync()
        Log.i(TAG, "ينتظر $waiting كلمة · وصل $fresh شريحة")
        Result.success()
    }.getOrElse {
        // انقطاعٌ عابر — يعيد النظام المحاولة بمهلةٍ متصاعدة
        Log.w(TAG, "تعثّر: ${it.message}")
        Result.retry()
    }

    companion object {
        private const val NAME = "tornado-card-fetch"
        private const val TAG = "TornadoFetch"

        /** يُجدول مرّةً واحدة، ويبقى بعد إغلاق التطبيق وبعد إعادة التشغيل */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CardFetchWorker>(
                15, TimeUnit.MINUTES
            ).setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP لا UPDATE: لا نعيد ضبط جدولته في كل فتحة، فيؤجّلها النظام
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
