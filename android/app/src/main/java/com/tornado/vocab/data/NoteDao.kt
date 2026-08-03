package com.tornado.vocab.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    /**
     * القائمة تُقرأ بلا النصّ.
     *
     * ملاحظة واحدة قد تبلغ عشرات الآلاف من الحروف، وتحميل عشرين منها لعرض
     * قائمة يعني ميغابايتات في الذاكرة مقابل سطرين على الشاشة. المعاينة تُقصّ
     * في الاستعلام نفسه، والنصّ الكامل يُقرأ عند التشغيل وحده.
     */
    @Query(
        """SELECT id, title, substr(text, 1, 120) AS preview,
                  updatedAt, lastChunk, favorite, length(text) AS chars
           FROM notes ORDER BY updatedAt DESC"""
    )
    fun rows(): Flow<List<NoteRowRaw>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun byId(id: Long): Note?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun allOnce(): List<Note>

    @Query("SELECT COUNT(*) FROM notes")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: Note)

    @Update
    suspend fun update(note: Note)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(t: NoteTombstone)

    @Query("SELECT id FROM note_tombstones")
    suspend fun tombstoneIds(): List<Long>

    @Query("SELECT deletedAt FROM note_tombstones WHERE id = :id")
    suspend fun deletedAt(id: Long): Long?

    /**
     * الحذف والشاهدة في معاملة واحدة.
     *
     * فصلهما يترك نافذةً بينهما: يُحذف الصفّ ثم تنقطع الكهرباء قبل الشاهدة،
     * فتعيد المزامنة التالية ما حذفه المستخدم عمداً.
     */
    @Transaction
    suspend fun deleteWithTombstone(id: Long) {
        deleteById(id)
        addTombstone(NoteTombstone(id, System.currentTimeMillis()))
    }

    /** يحفظ موضع الاستماع — نصّ طويل يُستأنف لا يُعاد من أوّله */
    @Query("UPDATE notes SET lastChunk = :chunk WHERE id = :id")
    suspend fun setLastChunk(id: Long, chunk: Int)

    @Query("UPDATE notes SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)
}

/** ناتج الاستعلام الخفيف — يُحوَّل إلى [NoteRow] بعد حساب المقاطع */
data class NoteRowRaw(
    val id: Long,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val lastChunk: Int,
    val favorite: Boolean,
    val chars: Int
)
