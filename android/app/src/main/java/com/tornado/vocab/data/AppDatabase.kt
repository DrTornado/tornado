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
    entities = [
        Word::class, WordFts::class, Tombstone::class, Note::class,
        NoteTombstone::class, EnrichCard::class, EnrichShard::class
    ],
    version = 5,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wordDao(): WordDao
    abstract fun noteDao(): NoteDao
    abstract fun enrichDao(): EnrichDao

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

        /**
         * جدولا الإثراء.
         *
         * ترحيلٌ مكتوب كسابقيه: البطاقات تصل من المستودع ويمكن إعادة
         * تنزيلها، لكن مكتبة المستخدم وتقدّمه وملاحظاته لا يمكن. وقد كانت
         * القاعدة على `fallbackToDestructiveMigration` يوماً — فأي إغفالٍ
         * هنا يمسح كل شيء بلا إنذار.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enrich_cards` (
                        `word` TEXT NOT NULL,
                        `json` TEXT NOT NULL,
                        PRIMARY KEY(`word`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enrich_shards` (
                        `key` TEXT NOT NULL,
                        `hash` TEXT NOT NULL,
                        PRIMARY KEY(`key`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * خانة `curated` في بطاقات الإثراء.
         *
         * جدولٌ يُعاد إنشاؤه لا عمودٌ يُضاف. و`ALTER TABLE ADD COLUMN` مع
         * `NOT NULL` يوجب SQLite معه قيمةً افتراضية، بينما المخطّط الذي
         * تولّده Room للتثبيت الجديد بلا افتراضية — فيختلف الجدولان في نصّ
         * إنشائهما، ويبقى قبولُ Room لذلك رهنَ تفصيلٍ في تنفيذها لا ضمانةً
         * مكتوبة. والإنشاء من جديد يجعل النصّ مطابقاً حرفاً بحرف لما تولّده
         * هي، فيسقط السؤال كلّه.
         *
         * وحذف البطاقات هنا لا يفقد شيئاً: هي نسخةٌ من المستودع لا مصدرٌ،
         * ومسحُ بصمات الشرائح يعيدها كلّها في أوّل مزامنة — وبها تُملأ الخانة
         * الجديدة من مصدرها لا باستخراجٍ نصّيّ من JSON مخزَّن.
         *
         * الكلفة ستّمائة كيلوبايت مرّةً واحدة. ومكتبة المستخدم وتقدّمه
         * وملاحظاته لا يمسّها هذا الترحيل بحرف.
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `enrich_cards`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `enrich_cards` (
                        `word` TEXT NOT NULL,
                        `json` TEXT NOT NULL,
                        `curated` INTEGER NOT NULL,
                        PRIMARY KEY(`word`)
                    )
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM `enrich_shards`")
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                .also { INSTANCE = it }
        }
    }
}
