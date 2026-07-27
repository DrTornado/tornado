package com.tornado.vocab.data

import androidx.room.*
import kotlinx.serialization.Serializable

@Serializable
data class Meaning(val pos: String? = null, val en: String = "", val ar: String = "")

@Serializable
data class Pair2(val en: String = "", val ar: String = "")

/** كيان الكلمة — مفهرس لبحث سريع مع آلاف الكلمات */
@Entity(tableName = "words", indices = [Index("word"), Index("favorite")])
data class Word(
    @PrimaryKey val id: Long,
    val word: String,
    val ipa: String = "",
    val arabicPron: String = "",
    val oxford: String = "",
    val cefr: String = "",
    val audioUS: String = "",
    val audioUK: String = "",
    val favorite: Boolean = false,
    val posCsv: String = "",
    val meaningsJson: String = "[]",
    val inflectionsCsv: String = "",
    val synonymsJson: String = "[]",
    val collocationsJson: String = "[]",
    val examplesJson: String = "[]",
    /** نص مسطّح للبحث الفوري بالعربي والإنجليزي */
    val searchBlob: String = ""
)
