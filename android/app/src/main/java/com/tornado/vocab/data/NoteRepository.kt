package com.tornado.vocab.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * مخزن الملاحظات الصوتية.
 *
 * نفس دور مخزن الكلمات وبنفس صيغة التبادل، فما يُكتب هنا يقرأه الكمبيوتر بلا
 * ترجمة. والفارق الوحيد أن الملاحظة نصّ يُقرأ من أوّله لا بطاقة تُراجَع.
 */
class NoteRepository(context: Context) {

    private val dao = AppDatabase.get(context).noteDao()

    val rows: Flow<List<NoteRow>> = dao.rows().map { raw ->
        raw.map {
            NoteRow(
                id = it.id,
                title = it.title,
                preview = it.preview.replace(Regex("\\s+"), " ").trim(),
                // الكلمات والمقاطع تُقدَّر من طول النص بدل تحميله كاملاً
                wordCount = (it.chars / 5.5).toInt(),
                chunkCount = ((it.chars / 1_200) + 1).coerceAtLeast(1),
                lastChunk = it.lastChunk,
                favorite = it.favorite
            )
        }
    }

    suspend fun byId(id: Long): Note? = withContext(Dispatchers.IO) { dao.byId(id) }
    suspend fun all(): List<Note> = withContext(Dispatchers.IO) { dao.allOnce() }
    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.count() }

    /**
     * يضيف ملاحظة من نصّ خام.
     * العنوان يُشتقّ من أول سطر حين لا يُعطى — إجبار المستخدم على تسمية كل
     * لصقة يجعله يتوقّف عن اللصق.
     */
    suspend fun add(text: String, title: String? = null): Note? = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.length < 20) return@withContext null
        val now = System.currentTimeMillis()
        val note = Note(
            id = now,
            title = title?.trim()?.takeIf { it.isNotBlank() } ?: NoteChunker.titleFrom(clean),
            text = clean,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(note)
        note
    }

    suspend fun save(note: Note) = withContext(Dispatchers.IO) {
        dao.update(note.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) { dao.deleteWithTombstone(id) }
    suspend fun setLastChunk(id: Long, chunk: Int) =
        withContext(Dispatchers.IO) { dao.setLastChunk(id, chunk) }
    suspend fun setFavorite(id: Long, fav: Boolean) =
        withContext(Dispatchers.IO) { dao.setFavorite(id, fav) }

    /** يستورد ملاحظة قادمة من المزامنة بلا أن يدوس على أحدث منها محلياً */
    suspend fun mergeRemote(note: Note): Boolean = withContext(Dispatchers.IO) {
        /*
         * الشاهدة تسبق كل شيء.
         *
         * الملاحظة المحذوفة هنا تبقى في المستودع حتى ترفعها المزامنة التالية،
         * وأي سحب بينهما كان يعيدها — فيرى المستخدم نوتةً تُمسح وترجع في
         * اللحظة نفسها ولا يفهم لماذا. وقد رآها فعلاً.
         *
         * والاستثناء مقصود: نسخة بعيدة أحدث من الحذف تعني تعديلاً على جهاز
         * آخر بعده، فإحياؤها هو الصواب لا الخطأ.
         */
        val killedAt = dao.deletedAt(note.id)
        if (killedAt != null && note.updatedAt <= killedAt) return@withContext false
        val local = dao.byId(note.id)
        if (local != null && local.updatedAt >= note.updatedAt) return@withContext false
        dao.insert(note)
        true
    }

    /** يقبل حذفاً جاء من الطرف الآخر — ويسجّل شاهدة لئلا يعود من هنا */
    suspend fun acceptRemoteDelete(id: Long) = withContext(Dispatchers.IO) {
        if (dao.deletedAt(id) == null) dao.deleteWithTombstone(id)
    }

    /** معرّفات ما حُذف — تُرفع مع الملاحظات ليعرف الطرف الآخر أنه حذف متعمَّد */
    suspend fun tombstoneIds(): List<Long> = withContext(Dispatchers.IO) { dao.tombstoneIds() }
}
