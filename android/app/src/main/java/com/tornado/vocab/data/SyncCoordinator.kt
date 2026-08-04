package com.tornado.vocab.data

import android.content.Context
import com.tornado.vocab.tornado
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * مزامنةٌ واحدة يستدعيها الجميع.
 *
 * كانت مبعثرة: الإقلاع يزامن في مكان، وإضافة كلمة ترفع في مكان آخر، وزرّ
 * المزامنة له نسخته الثالثة. فاختلفت المسارات في ما تفعله — بعضها يرفع ولا
 * يسحب، وبعضها ينسى الملاحظات، وبعضها لا يُثري. والمستخدم يرى النتيجة
 * متقلّبة بلا سبب مفهوم.
 *
 * وأخطر ما في التبعثر أن حالة «جارٍ الآن» كانت محلّية في زرّ المزامنة وحده،
 * فتجري المزامنة التلقائية بصمت تامّ ويسأل المستخدم «هل زامن؟» ولا شيء على
 * الشاشة يجيبه.
 *
 * فصارت واحدة: مسارٌ واحد، وحالةٌ واحدة يراقبها الزرّ فيدور في كل مرّة —
 * سواء ضغطها المستخدم أو جرت من نفسها.
 */
object SyncCoordinator {

    private val _busy = MutableStateFlow(false)

    /** يراقبها زرّ المزامنة فيدور في كل مزامنة، لا في اليدوية وحدها */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    fun consumeResult() { _lastResult.value = null }

    /** يمنع مزامنتين متوازيتين تتسابقان على نفس الملف فتتعارضان */
    private val gate = Mutex()

    /**
     * سحبٌ ورفعٌ للكلمات والملاحظات والصوت، ثم إثراء المعاني.
     *
     * @param announce يعرض النتيجة للمستخدم — للضغطة اليدوية وحدها. المزامنة
     *   التلقائية تدور بالزرّ ولا تُطلق رسائل تقاطع القراءة.
     */
    suspend fun syncNow(context: Context, announce: Boolean = false) {
        if (gate.isLocked) return          // جارية بالفعل — لا نصطفّ خلفها
        gate.withLock {
            val c = context.applicationContext.tornado
            _busy.value = true
            try {
                val repo = runCatching { c.settings.syncRepo() }.getOrDefault("")
                val r = runCatching {
                    c.sync.repo = repo
                    if (c.sync.canPull) c.sync.sync(push = c.sync.canPush) else null
                }.getOrNull()

                runCatching {
                    c.noteSync.repo = repo
                    if (c.noteSync.canPull) c.noteSync.sync(push = c.noteSync.canPush)
                }
                runCatching {
                    c.audioSync.repo = repo
                    c.audioSync.sync()
                }

                // الإثراء يتبع كل مزامنة — المعاني الناقصة تُملأ بلا ضغطة
                c.appScope.launch { runCatching { c.enricher.runUntilComplete() } }

                if (announce) _lastResult.value = when (r) {
                    is SyncResult.Success ->
                        if (r.pulled == 0 && r.pushed == 0 && r.deleted == 0) "Up to date"
                        else "+${r.pulled} words"
                    SyncResult.NotConfigured -> "Add your repository in Settings"
                    is SyncResult.Failed -> r.message
                    null -> "No connection"
                }
            } finally {
                _busy.value = false
            }
        }
    }
}
