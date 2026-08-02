package com.tornado.vocab.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * كل ما يمنع النظام من قتل التشغيل بالخلفية.
 *
 * خدمة أمامية صحيحة وحدها لا تكفي على أجهزة حقيقية: سامسونج تُنيم التطبيقات
 * بعمق، ومحسّن البطارية يوقف العمل الخلفي، ورفض إذن الإشعارات يمنع الخدمة
 * من العمل في المقدمة أصلاً. هذه الأدوات تعالج الطبقات الثلاث.
 */
object PlaybackReliability {

    const val CHANNEL_PREPARE = "tornado_preparing"
    const val NOTIFICATION_PREPARE = 4101

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_PREPARE) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PREPARE,
                "Preparing audio",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while Tornado builds the audio for your listening session"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    /** هل التطبيق معفى من تحسين البطارية؟ بدون الإعفاء يقتل النظام الجلسات الطويلة */
    fun isBatteryOptimised(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * يفتح شاشة إعفاء البطارية.
     * نستخدم الشاشة العامة لا الطلب المباشر: الطلب المباشر يخالف سياسة المتجر
     * لتطبيق لا يندرج تحت الفئات المستثناة، والشاشة العامة تصل للنتيجة نفسها.
     */
    fun openBatterySettings(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    }.getOrElse {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
    }

    fun openNotificationSettings(context: Context): Boolean = runCatching {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    fun notificationsEnabled(context: Context): Boolean = runCatching {
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }.getOrDefault(true)
}
