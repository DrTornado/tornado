package com.tornado.vocab.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

private const val ROW_COLS =
    "id, word, primaryEn, primaryAr, lastResult, due, cefr, estCefr, oxford, favorite, right, wrong"

@Dao
interface WordDao {

    // ===== قوائم خفيفة (بلا حقول JSON) =====

    @Query("SELECT $ROW_COLS FROM words ORDER BY word COLLATE NOCASE")
    fun rowsAll(): Flow<List<WordRow>>

    @Query("SELECT $ROW_COLS FROM words ORDER BY createdAt DESC, word COLLATE NOCASE")
    fun rowsNewest(): Flow<List<WordRow>>

    @Query(
        """SELECT $ROW_COLS FROM words
           WHERE (:status IS NULL
                  OR (:status = 'new'   AND (lastResult IS NULL OR lastResult = ''))
                  OR (:status = 'right' AND lastResult = 'right')
                  OR (:status = 'wrong' AND lastResult IS NOT NULL AND lastResult <> '' AND lastResult <> 'right'))
             AND (:favOnly = 0 OR favorite = 1)
           ORDER BY
             CASE WHEN :sort = 'alpha'  THEN word END COLLATE NOCASE ASC,
             CASE WHEN :sort = 'newest' THEN createdAt END DESC,
             CASE WHEN :sort = 'due'    THEN due END ASC,
             CASE WHEN :sort = 'hardest' THEN (wrong * 100 / (right + wrong + 1)) END DESC,
             word COLLATE NOCASE ASC"""
    )
    fun rowsFiltered(status: String?, favOnly: Int, sort: String): Flow<List<WordRow>>

    /**
     * بحث فوري عبر الفهرس النصي الكامل — يعمل بالإنجليزية والعربية معاً.
     * الترتيب يقدّم التطابق الحرفي في بداية الكلمة، فما يكتبه المستخدم يظهر أولاً دائماً.
     */
    @Query(
        """SELECT w.id, w.word, w.primaryEn, w.primaryAr, w.lastResult, w.due, w.cefr,
                  w.estCefr, w.oxford, w.favorite, w.right, w.wrong
           FROM words AS w
           JOIN words_fts AS f ON f.docid = w.rowid
           WHERE words_fts MATCH :ftsQuery
             AND (:status IS NULL
                  OR (:status = 'new'   AND (w.lastResult IS NULL OR w.lastResult = ''))
                  OR (:status = 'right' AND w.lastResult = 'right')
                  OR (:status = 'wrong' AND w.lastResult IS NOT NULL AND w.lastResult <> '' AND w.lastResult <> 'right'))
             AND (:favOnly = 0 OR w.favorite = 1)
           ORDER BY
             CASE WHEN w.word = :raw THEN 0
                  WHEN w.word LIKE :prefix THEN 1
                  ELSE 2 END,
             w.word COLLATE NOCASE
           LIMIT 400"""
    )
    fun searchRows(
        ftsQuery: String,
        raw: String,
        prefix: String,
        status: String?,
        favOnly: Int
    ): Flow<List<WordRow>>

    /** بحث احتياطي لا يعتمد على الفهرس — يغطي الرموز التي يتجاهلها المُجزّئ النصي */
    @Query(
        """SELECT $ROW_COLS FROM words
           WHERE searchBlob LIKE '%' || :q || '%'
           ORDER BY CASE WHEN word = :q THEN 0 WHEN word LIKE :q || '%' THEN 1 ELSE 2 END,
                    word COLLATE NOCASE
           LIMIT 400"""
    )
    fun searchRowsLike(q: String): Flow<List<WordRow>>

    // ===== كلمات كاملة =====

    @Query("SELECT * FROM words WHERE id = :id")
    fun observeById(id: Long): Flow<Word?>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun byId(id: Long): Word?

    @Query("SELECT * FROM words WHERE word = :word COLLATE NOCASE LIMIT 1")
    suspend fun byWord(word: String): Word?

    @Query("SELECT * FROM words WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<Word>

    @Query("SELECT * FROM words ORDER BY word COLLATE NOCASE")
    suspend fun allOnce(): List<Word>

    /**
     * طابور المراجعة: المستحق اليوم أولاً، ثم الأخطاء قبل الجديد قبل المعروف.
     * الترتيب داخل كل مجموعة عشوائي حتى لا تحفظ ترتيب البطاقات بدل الكلمات نفسها.
     */
    @Query(
        """SELECT * FROM words
           WHERE (:dueOnly = 0 OR due <= :now)
             AND (:status IS NULL
                  OR (:status = 'new'   AND (lastResult IS NULL OR lastResult = ''))
                  OR (:status = 'right' AND lastResult = 'right')
                  OR (:status = 'wrong' AND lastResult IS NOT NULL AND lastResult <> '' AND lastResult <> 'right'))
             AND (:favOnly = 0 OR favorite = 1)
           ORDER BY
             CASE WHEN lastResult IS NOT NULL AND lastResult <> '' AND lastResult <> 'right' THEN 0
                  WHEN lastResult IS NULL OR lastResult = '' THEN 1
                  ELSE 2 END,
             RANDOM()
           LIMIT :limit"""
    )
    suspend fun reviewQueue(
        now: Long,
        dueOnly: Int,
        status: String?,
        favOnly: Int,
        limit: Int
    ): List<Word>

    /**
     * قائمة تشغيل الاستماع كصفوف خفيفة.
     *
     * تحميل الكلمات كاملة هنا كان يعني فكّ ترميز JSON لكل كلمة في المكتبة
     * دفعة واحدة — مقبول عند مئة كلمة، وكارثي عند خمسة آلاف. الجلسة تحمل
     * المعرّفات والعناوين فقط، وتُقرأ البطاقة الكاملة عند بناء صوتها وحده.
     */
    @Query(
        """SELECT $ROW_COLS FROM words
           WHERE (:status IS NULL
                  OR (:status = 'new'   AND (lastResult IS NULL OR lastResult = ''))
                  OR (:status = 'right' AND lastResult = 'right')
                  OR (:status = 'wrong' AND lastResult IS NOT NULL AND lastResult <> '' AND lastResult <> 'right'))
             AND (:favOnly = 0 OR favorite = 1)
           ORDER BY word COLLATE NOCASE"""
    )
    suspend fun playlistRows(status: String?, favOnly: Int): List<WordRow>

    // ===== إحصاءات =====

    /**
     * كل مجموع مغلَّف بـ COALESCE: SUM فوق جدول فارغ يعيد NULL في SQLite،
     * وإسقاطه على حقل Int غير قابل للعدم يُسقط التطبيق عند أول تشغيل قبل إضافة أي كلمة.
     */
    @Query(
        """SELECT
             COUNT(*) AS total,
             COALESCE(SUM(CASE WHEN lastResult IS NULL OR lastResult = '' THEN 1 ELSE 0 END), 0) AS newCount,
             COALESCE(SUM(CASE WHEN lastResult IS NOT NULL AND lastResult <> '' AND lastResult <> 'right' THEN 1 ELSE 0 END), 0) AS missed,
             COALESCE(SUM(CASE WHEN lastResult = 'right' THEN 1 ELSE 0 END), 0) AS known,
             COALESCE(SUM(CASE WHEN due <= :now THEN 1 ELSE 0 END), 0) AS dueNow,
             COALESCE(SUM(CASE WHEN favorite = 1 THEN 1 ELSE 0 END), 0) AS favorites,
             COALESCE(SUM(right), 0) AS totalRight,
             COALESCE(SUM(wrong), 0) AS totalWrong
           FROM words"""
    )
    fun stats(now: Long): Flow<LibraryStats>

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    // ===== كتابة =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Word>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: Word)

    @Update
    suspend fun update(item: Word)

    @Query("UPDATE words SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    /**
     * تصنيف يدوي صريح من المستخدم.
     * "معروفة" تُجدول بعد ثلاثة أيام، و"أخطأت" تعود اليوم، و"جديدة" تُصفّر
     * الجدولة كلها — فالتصنيف قرار تعليمي حقيقي لا مجرد لون.
     */
    @Query("UPDATE words SET lastResult = :lastResult, interval = :interval, due = :due WHERE id = :id")
    suspend fun setStatus(id: Long, lastResult: String?, interval: Int, due: Long)

    @Query(
        """UPDATE words
           SET right = :right, wrong = :wrong, lastResult = :lastResult,
               interval = :interval, due = :due
           WHERE id = :id"""
    )
    suspend fun updateProgress(
        id: Long, right: Int, wrong: Int, lastResult: String?, interval: Int, due: Long
    )

    @Query("DELETE FROM words WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM words")
    suspend fun deleteAll()

    @Query("UPDATE words SET right = 0, wrong = 0, lastResult = NULL, interval = 0, due = 0")
    suspend fun resetAllProgress()

    // ===== شواهد الحذف =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(t: Tombstone)

    @Query("SELECT * FROM tombstones")
    suspend fun tombstones(): List<Tombstone>

    @Query("DELETE FROM tombstones WHERE deletedAt < :before")
    suspend fun pruneTombstones(before: Long)

    @Transaction
    suspend fun deleteWithTombstone(id: Long, word: String) {
        deleteById(id)
        addTombstone(Tombstone(id, word, System.currentTimeMillis()))
    }
}
