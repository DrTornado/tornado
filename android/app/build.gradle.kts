import java.util.Properties

/*
 * كل ما يخصّ النشر في مجلد واحد: Desktop/TORNADO-PLAY
 *
 * الحزمة والأيقونة واللقطات ونصوص المتجر والمفتاح — كلها هناك، وأي ملف جديد
 * يُستبدل في مكانه داخله ولا يُنشأ مجلد ثانٍ.
 *
 * تفرّقُها سابقاً على ثلاثة مجلدات أنتج مفتاحين مختلفين وحزمتين، وكادت تُرفع
 * حزمةٌ موقّعة بمفتاح لا يملكه صاحبها — وذاك خطأ لا رجعة فيه، لأن المتجر
 * يربط التطبيق بمفتاح أول إصدار إلى الأبد.
 *
 * بيانات التوقيع تُقرأ من ملف خارج المستودع.
 *
 * مفتاح النشر وكلمة مروره هما هوية التطبيق عند جوجل: من يملكهما يستطيع أن
 * يصدر تحديثاً باسمك، ومن يفقدهما لا يستطيع تحديث تطبيقه أبداً — لا استرجاع
 * ولا استثناء. فلا يدخلان المستودع مهما كان خاصاً، ولا يُكتبان في ملف يُدفع.
 *
 * وغيابهما لا يكسر البناء: من ينسخ المشروع يحصل على نسخة موقّعة بمفتاح
 * التطوير تعمل عنده، ولا يُوقف عمله بخطأ عن ملف لا يملكه.
 */
val keystoreProps = Properties().apply {
    val f = File(System.getProperty("user.home"), "OneDrive/Desktop/TORNADO-PLAY/SIGNING-KEY/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/*
 * مخطّط Room يُصدَّر إلى ملف.
 *
 * الترحيل المكتوب بيدٍ يجب أن يُنتج بنيةً تطابق ما يتوقّعه Room حرفاً
 * بحرف، وإلا رمى عند الإقلاع «Migration didn't properly handle». وذلك
 * يقع على جهاز المستخدم لا عندنا، ومكتبته هي الثمن. فيُصدَّر المخطّط
 * ويُقارَن قبل الشحن.
 */
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.tornado.vocab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tornado.vocab"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "2.4"
    }

    /*
     * ملف لكل معمارية بدل ملف واحد يحمل الثلاث.
     *
     * مكتبات محرك الصوت العصبي وحدها ٨٥ ميغابايت موزّعة على ثلاث معماريات،
     * والجهاز لا يشغّل إلا واحدة منها. الملف الموحّد كان يجعل نقل التطبيق
     * إلى الجوال عبئاً حقيقياً — وهذا سبب كافٍ لتقسيمه.
     *
     * القائمة هنا تحلّ محلّ ndk.abiFilters ولا تجتمع معها: x86_64 تبقى مشمولة
     * لأن المحاكي يحتاجها، وإسقاطها يجعل محرك الصوت يفشل بصمت أثناء الاختبار.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    signingConfigs {
        // يُنشأ فقط إن وُجد الملف — وإلا فلا إعداد ولا خطأ
        if (keystoreProps.getProperty("storeFile") != null) {
            create("tornado") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            /*
             * مفتاح النشر إن وُجد، ومفتاح التطوير إن لم يوجد.
             *
             * جوجل بلاي ترفض ما يُوقَّع بمفتاح التطوير — وهو ما كان عليه
             * التطبيق. والسقوط إلى مفتاح التطوير عند غياب الملف مقصود: يبقى
             * البناء ممكناً على أي جهاز، ويستحيل أن يُرفع بالخطأ ما ليس
             * موقّعاً بالمفتاح الصحيح لأن المتجر يرفضه صراحةً.
             */
            signingConfig = signingConfigs.findByName("tornado")
                ?: signingConfigs.getByName("debug")
        }
        /*
         * نسخة التطوير تُثبَّت بجانب المنشورة لا فوقها.
         *
         * توقيعُهما مختلف، فالتثبيت فوق المنشورة يستلزم حذفها — وحذفُها
         * يمحو مكتبة المستخدم وتقدّمه وملاحظاته. ولاحقةُ المعرِّف تجعلهما
         * تطبيقين مستقلّين، فنجرّب على واحدة ولا نمسّ الأخرى.
         */
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        /*
         * نستبعد واجهتَي C وC++ من sherpa — أربعة ميغابايت ونصف لا نستدعيها.
         *
         * كوتلن يحمّل `sherpa-onnx-jni` وحدها، وهي مبنيّة مكتفية بذاتها.
         * الواجهتان الأخريان للمشاريع التي تربط بلغة C أو C++ مباشرة، ووجودهما
         * في الحزمة ثمنٌ يدفعه كل مستخدم مقابل شيفرة لا تعمل عنده أبداً.
         */
        jniLibs.excludes += setOf(
            "**/libsherpa-onnx-c-api.so",
            "**/libsherpa-onnx-cxx-api.so"
        )
    }
    // بيانات المرجع (أكسفورد/التردد) والكلمات تُقرأ مباشرة من الأصول — منع الضغط يجعل القراءة أسرع
    androidResources { noCompress += listOf("json", "txt") }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("androidx.media3:media3-exoplayer:1.5.0")
    implementation("androidx.media3:media3-session:1.5.0")

    /*
     * محرك كوكورو الصوتي — عبر sherpa-onnx.
     *
     * سبق أن حُذفت هذه المكتبات مع Piper لأنه كان أضعف من محرك الجهاز نفسه —
     * حملاً بلا مقابل. وعادت لسبب معاكس تماماً: كوكورو أفضل صوت مفتوح للإنجليزية
     * اليوم، والمستخدم سمعه بأذنه وطلبه.
     *
     * المكتبات هنا (~٣٠ ميغابايت) لأن تحميل مكتبات أصلية ديناميكياً هشّ وممنوع
     * متجرياً، والنموذج (~١٤٠ ميغابايت) تنزيل اختياري من الإعدادات — فمن يكفيه
     * محرك جهازه لا ينزّل شيئاً.
     */
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    // فكّ ضغط حزمة النموذج (tar.bz2) — لا دعم bzip2 في مكتبة جافا القياسية
    implementation("org.apache.commons:commons-compress:1.27.1")

    // تخزين مفتاح الخدمة مشفّراً بمفتاح عتادي — لا يُحفظ نصاً صريحاً أبداً
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // اختبارات تعمل على الحاسوب بلا جهاز — تغطّي منطق الصوت والبيانات
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric يحتاج موارد أندرويد الحقيقية لتشغيل الاختبارات على JVM
android.testOptions.unitTests.isIncludeAndroidResources = true
