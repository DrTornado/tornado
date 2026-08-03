package com.tornado.vocab.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val converterJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

private val stringListSerializer = ListSerializer(String.serializer())
private val meaningListSerializer = ListSerializer(Meaning.serializer())
private val pairListSerializer = ListSerializer(LangPair.serializer())

/**
 * القوائم المتداخلة تُخزَّن نصاً بصيغة JSON.
 * البدائل (جداول علاقات كاملة) تعني عشرات الاستعلامات لعرض بطاقة واحدة،
 * بينما البطاقة تُقرأ وتُكتب دائماً ككتلة واحدة — فالتخزين النصي هنا أبسط وأسرع فعلياً.
 */
class Converters {
    @TypeConverter fun stringsToJson(v: List<String>): String =
        converterJson.encodeToString(stringListSerializer, v)

    @TypeConverter fun jsonToStrings(v: String): List<String> =
        runCatching { converterJson.decodeFromString(stringListSerializer, v) }.getOrDefault(emptyList())

    @TypeConverter fun meaningsToJson(v: List<Meaning>): String =
        converterJson.encodeToString(meaningListSerializer, v)

    @TypeConverter fun jsonToMeanings(v: String): List<Meaning> =
        runCatching { converterJson.decodeFromString(meaningListSerializer, v) }.getOrDefault(emptyList())

    @TypeConverter fun pairsToJson(v: List<LangPair>): String =
        converterJson.encodeToString(pairListSerializer, v)

    @TypeConverter fun jsonToPairs(v: String): List<LangPair> =
        runCatching { converterJson.decodeFromString(pairListSerializer, v) }.getOrDefault(emptyList())
}

@Database(
    entities = [Word::class, WordFts::class, Tombstone::class, Note::class, NoteTombstone::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * إضافة جدول الملاحظات.
         *
         * ترحيل مكتوب لا محو تلقائي: كانت القاعدة مضبوطة على
         * `fallbackToDestructiveMigration`، ومعناها أن **أي** تغيير في البنية
         * يمسح مكتبة المستخدم كاملة — تقدّمه في المراجعة ومفضّلاته وتواريخه.
         * وهو إعداد يمرّ بلا أن يُلاحَظ حتى يقع.
         */
        /**
         * جدول شواهد حذف الملاحظات.
         *
         * ترحيل لا حذف: مكتبة المستخدم وملاحظاته تبقى كما هي. وقد كانت
         * قاعدة البيانات على `fallbackToDestructiveMigration` يوماً، فكان أي
         * تغيير في المخطط يمسح كل شيء بلا إنذار.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `note_tombstones` (
                        `id` INTEGER NOT NULL,
                        `deletedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `notes` (
                        `id` INTEGER NOT NULL,
                        `title` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastChunk` INTEGER NOT NULL,
                        `favorite` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "tornado.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { INSTANCE = it }
        }
    }
}
