package com.tornado.vocab.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY word COLLATE NOCASE")
    fun all(): Flow<List<Word>>

    @Query("SELECT * FROM words WHERE favorite = 1 ORDER BY word COLLATE NOCASE")
    fun favorites(): Flow<List<Word>>

    @Query("SELECT * FROM words WHERE searchBlob LIKE '%' || :q || '%' ORDER BY word COLLATE NOCASE LIMIT 300")
    fun search(q: String): Flow<List<Word>>

    @Query("SELECT * FROM words WHERE id = :id")
    fun byId(id: Long): Flow<Word?>

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Word>)

    @Query("UPDATE words SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)
}
