import java.util.Properties

/*
 * ÙƒÙ„ Ù…Ø§ ÙŠØ®ØµÙ‘ Ø§Ù„Ù†Ø´Ø± ÙÙŠ Ù…Ø¬Ù„Ø¯ ÙˆØ§Ø­Ø¯: Desktop/TORNADO-PLAY
 *
 * Ø§Ù„Ø­Ø²Ù…Ø© ÙˆØ§Ù„Ø£ÙŠÙ‚ÙˆÙ†Ø© ÙˆØ§Ù„Ù„Ù‚Ø·Ø§Øª ÙˆÙ†ØµÙˆØµ Ø§Ù„Ù…ØªØ¬Ø± ÙˆØ§Ù„Ù…ÙØªØ§Ø­ â€” ÙƒÙ„Ù‡Ø§ Ù‡Ù†Ø§ÙƒØŒ ÙˆØ£ÙŠ Ù…Ù„Ù Ø¬Ø¯ÙŠØ¯
 * ÙŠÙØ³ØªØ¨Ø¯Ù„ ÙÙŠ Ù…ÙƒØ§Ù†Ù‡ Ø¯Ø§Ø®Ù„Ù‡ ÙˆÙ„Ø§ ÙŠÙÙ†Ø´Ø£ Ù…Ø¬Ù„Ø¯ Ø«Ø§Ù†Ù.
 *
 * ØªÙØ±Ù‘Ù‚ÙÙ‡Ø§ Ø³Ø§Ø¨Ù‚Ø§Ù‹ Ø¹Ù„Ù‰ Ø«Ù„Ø§Ø«Ø© Ù…Ø¬Ù„Ø¯Ø§Øª Ø£Ù†ØªØ¬ Ù…ÙØªØ§Ø­ÙŠÙ† Ù…Ø®ØªÙ„ÙÙŠÙ† ÙˆØ­Ø²Ù…ØªÙŠÙ†ØŒ ÙˆÙƒØ§Ø¯Øª ØªÙØ±ÙØ¹
 * Ø­Ø²Ù…Ø©ÙŒ Ù…ÙˆÙ‚Ù‘Ø¹Ø© Ø¨Ù…ÙØªØ§Ø­ Ù„Ø§ ÙŠÙ…Ù„ÙƒÙ‡ ØµØ§Ø­Ø¨Ù‡Ø§ â€” ÙˆØ°Ø§Ùƒ Ø®Ø·Ø£ Ù„Ø§ Ø±Ø¬Ø¹Ø© ÙÙŠÙ‡ØŒ Ù„Ø£Ù† Ø§Ù„Ù…ØªØ¬Ø±
 * ÙŠØ±Ø¨Ø· Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ Ø¨Ù…ÙØªØ§Ø­ Ø£ÙˆÙ„ Ø¥ØµØ¯Ø§Ø± Ø¥Ù„Ù‰ Ø§Ù„Ø£Ø¨Ø¯.
 *
 * Ø¨ÙŠØ§Ù†Ø§Øª Ø§Ù„ØªÙˆÙ‚ÙŠØ¹ ØªÙÙ‚Ø±Ø£ Ù…Ù† Ù…Ù„Ù Ø®Ø§Ø±Ø¬ Ø§Ù„Ù…Ø³ØªÙˆØ¯Ø¹.
 *
 * Ù…ÙØªØ§Ø­ Ø§Ù„Ù†Ø´Ø± ÙˆÙƒÙ„Ù…Ø© Ù…Ø±ÙˆØ±Ù‡ Ù‡Ù…Ø§ Ù‡ÙˆÙŠØ© Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ Ø¹Ù†Ø¯ Ø¬ÙˆØ¬Ù„: Ù…Ù† ÙŠÙ…Ù„ÙƒÙ‡Ù…Ø§ ÙŠØ³ØªØ·ÙŠØ¹ Ø£Ù†
 * ÙŠØµØ¯Ø± ØªØ­Ø¯ÙŠØ«Ø§Ù‹ Ø¨Ø§Ø³Ù…ÙƒØŒ ÙˆÙ…Ù† ÙŠÙÙ‚Ø¯Ù‡Ù…Ø§ Ù„Ø§ ÙŠØ³ØªØ·ÙŠØ¹ ØªØ­Ø¯ÙŠØ« ØªØ·Ø¨ÙŠÙ‚Ù‡ Ø£Ø¨Ø¯Ø§Ù‹ â€” Ù„Ø§ Ø§Ø³ØªØ±Ø¬Ø§Ø¹
 * ÙˆÙ„Ø§ Ø§Ø³ØªØ«Ù†Ø§Ø¡. ÙÙ„Ø§ ÙŠØ¯Ø®Ù„Ø§Ù† Ø§Ù„Ù…Ø³ØªÙˆØ¯Ø¹ Ù…Ù‡Ù…Ø§ ÙƒØ§Ù† Ø®Ø§ØµØ§Ù‹ØŒ ÙˆÙ„Ø§ ÙŠÙÙƒØªØ¨Ø§Ù† ÙÙŠ Ù…Ù„Ù ÙŠÙØ¯ÙØ¹.
 *
 * ÙˆØºÙŠØ§Ø¨Ù‡Ù…Ø§ Ù„Ø§ ÙŠÙƒØ³Ø± Ø§Ù„Ø¨Ù†Ø§Ø¡: Ù…Ù† ÙŠÙ†Ø³Ø® Ø§Ù„Ù…Ø´Ø±ÙˆØ¹ ÙŠØ­ØµÙ„ Ø¹Ù„Ù‰ Ù†Ø³Ø®Ø© Ù…ÙˆÙ‚Ù‘Ø¹Ø© Ø¨Ù…ÙØªØ§Ø­
 * Ø§Ù„ØªØ·ÙˆÙŠØ± ØªØ¹Ù…Ù„ Ø¹Ù†Ø¯Ù‡ØŒ ÙˆÙ„Ø§ ÙŠÙÙˆÙ‚Ù Ø¹Ù…Ù„Ù‡ Ø¨Ø®Ø·Ø£ Ø¹Ù† Ù…Ù„Ù Ù„Ø§ ÙŠÙ…Ù„ÙƒÙ‡.
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
 * Ù…Ø®Ø·Ù‘Ø· Room ÙŠÙØµØ¯ÙŽÙ‘Ø± Ø¥Ù„Ù‰ Ù…Ù„Ù.
 *
 * Ø§Ù„ØªØ±Ø­ÙŠÙ„ Ø§Ù„Ù…ÙƒØªÙˆØ¨ Ø¨ÙŠØ¯Ù ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙÙ†ØªØ¬ Ø¨Ù†ÙŠØ©Ù‹ ØªØ·Ø§Ø¨Ù‚ Ù…Ø§ ÙŠØªÙˆÙ‚Ù‘Ø¹Ù‡ Room Ø­Ø±ÙØ§Ù‹
 * Ø¨Ø­Ø±ÙØŒ ÙˆØ¥Ù„Ø§ Ø±Ù…Ù‰ Ø¹Ù†Ø¯ Ø§Ù„Ø¥Ù‚Ù„Ø§Ø¹ Â«Migration didn't properly handleÂ». ÙˆØ°Ù„Ùƒ
 * ÙŠÙ‚Ø¹ Ø¹Ù„Ù‰ Ø¬Ù‡Ø§Ø² Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… Ù„Ø§ Ø¹Ù†Ø¯Ù†Ø§ØŒ ÙˆÙ…ÙƒØªØ¨ØªÙ‡ Ù‡ÙŠ Ø§Ù„Ø«Ù…Ù†. ÙÙŠÙØµØ¯ÙŽÙ‘Ø± Ø§Ù„Ù…Ø®Ø·Ù‘Ø·
 * ÙˆÙŠÙÙ‚Ø§Ø±ÙŽÙ† Ù‚Ø¨Ù„ Ø§Ù„Ø´Ø­Ù†.
 */
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    namespace = "com.tornado.vocab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tornado.vocab"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "2.5"
    }

    /*
     * Ù…Ù„Ù Ù„ÙƒÙ„ Ù…Ø¹Ù…Ø§Ø±ÙŠØ© Ø¨Ø¯Ù„ Ù…Ù„Ù ÙˆØ§Ø­Ø¯ ÙŠØ­Ù…Ù„ Ø§Ù„Ø«Ù„Ø§Ø«.
     *
     * Ù…ÙƒØªØ¨Ø§Øª Ù…Ø­Ø±Ùƒ Ø§Ù„ØµÙˆØª Ø§Ù„Ø¹ØµØ¨ÙŠ ÙˆØ­Ø¯Ù‡Ø§ Ù¨Ù¥ Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª Ù…ÙˆØ²Ù‘Ø¹Ø© Ø¹Ù„Ù‰ Ø«Ù„Ø§Ø« Ù…Ø¹Ù…Ø§Ø±ÙŠØ§ØªØŒ
     * ÙˆØ§Ù„Ø¬Ù‡Ø§Ø² Ù„Ø§ ÙŠØ´ØºÙ‘Ù„ Ø¥Ù„Ø§ ÙˆØ§Ø­Ø¯Ø© Ù…Ù†Ù‡Ø§. Ø§Ù„Ù…Ù„Ù Ø§Ù„Ù…ÙˆØ­Ù‘Ø¯ ÙƒØ§Ù† ÙŠØ¬Ø¹Ù„ Ù†Ù‚Ù„ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚
     * Ø¥Ù„Ù‰ Ø§Ù„Ø¬ÙˆØ§Ù„ Ø¹Ø¨Ø¦Ø§Ù‹ Ø­Ù‚ÙŠÙ‚ÙŠØ§Ù‹ â€” ÙˆÙ‡Ø°Ø§ Ø³Ø¨Ø¨ ÙƒØ§ÙÙ Ù„ØªÙ‚Ø³ÙŠÙ…Ù‡.
     *
     * Ø§Ù„Ù‚Ø§Ø¦Ù…Ø© Ù‡Ù†Ø§ ØªØ­Ù„Ù‘ Ù…Ø­Ù„Ù‘ ndk.abiFilters ÙˆÙ„Ø§ ØªØ¬ØªÙ…Ø¹ Ù…Ø¹Ù‡Ø§: x86_64 ØªØ¨Ù‚Ù‰ Ù…Ø´Ù…ÙˆÙ„Ø©
     * Ù„Ø£Ù† Ø§Ù„Ù…Ø­Ø§ÙƒÙŠ ÙŠØ­ØªØ§Ø¬Ù‡Ø§ØŒ ÙˆØ¥Ø³Ù‚Ø§Ø·Ù‡Ø§ ÙŠØ¬Ø¹Ù„ Ù…Ø­Ø±Ùƒ Ø§Ù„ØµÙˆØª ÙŠÙØ´Ù„ Ø¨ØµÙ…Øª Ø£Ø«Ù†Ø§Ø¡ Ø§Ù„Ø§Ø®ØªØ¨Ø§Ø±.
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
        // ÙŠÙÙ†Ø´Ø£ ÙÙ‚Ø· Ø¥Ù† ÙˆÙØ¬Ø¯ Ø§Ù„Ù…Ù„Ù â€” ÙˆØ¥Ù„Ø§ ÙÙ„Ø§ Ø¥Ø¹Ø¯Ø§Ø¯ ÙˆÙ„Ø§ Ø®Ø·Ø£
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
             * Ù…ÙØªØ§Ø­ Ø§Ù„Ù†Ø´Ø± Ø¥Ù† ÙˆÙØ¬Ø¯ØŒ ÙˆÙ…ÙØªØ§Ø­ Ø§Ù„ØªØ·ÙˆÙŠØ± Ø¥Ù† Ù„Ù… ÙŠÙˆØ¬Ø¯.
             *
             * Ø¬ÙˆØ¬Ù„ Ø¨Ù„Ø§ÙŠ ØªØ±ÙØ¶ Ù…Ø§ ÙŠÙÙˆÙ‚ÙŽÙ‘Ø¹ Ø¨Ù…ÙØªØ§Ø­ Ø§Ù„ØªØ·ÙˆÙŠØ± â€” ÙˆÙ‡Ùˆ Ù…Ø§ ÙƒØ§Ù† Ø¹Ù„ÙŠÙ‡
             * Ø§Ù„ØªØ·Ø¨ÙŠÙ‚. ÙˆØ§Ù„Ø³Ù‚ÙˆØ· Ø¥Ù„Ù‰ Ù…ÙØªØ§Ø­ Ø§Ù„ØªØ·ÙˆÙŠØ± Ø¹Ù†Ø¯ ØºÙŠØ§Ø¨ Ø§Ù„Ù…Ù„Ù Ù…Ù‚ØµÙˆØ¯: ÙŠØ¨Ù‚Ù‰
             * Ø§Ù„Ø¨Ù†Ø§Ø¡ Ù…Ù…ÙƒÙ†Ø§Ù‹ Ø¹Ù„Ù‰ Ø£ÙŠ Ø¬Ù‡Ø§Ø²ØŒ ÙˆÙŠØ³ØªØ­ÙŠÙ„ Ø£Ù† ÙŠÙØ±ÙØ¹ Ø¨Ø§Ù„Ø®Ø·Ø£ Ù…Ø§ Ù„ÙŠØ³
             * Ù…ÙˆÙ‚Ù‘Ø¹Ø§Ù‹ Ø¨Ø§Ù„Ù…ÙØªØ§Ø­ Ø§Ù„ØµØ­ÙŠØ­ Ù„Ø£Ù† Ø§Ù„Ù…ØªØ¬Ø± ÙŠØ±ÙØ¶Ù‡ ØµØ±Ø§Ø­Ø©Ù‹.
             */
            signingConfig = signingConfigs.findByName("tornado")
                ?: signingConfigs.getByName("debug")
        }
        /*
         * Ù†Ø³Ø®Ø© Ø§Ù„ØªØ·ÙˆÙŠØ± ØªÙØ«Ø¨ÙŽÙ‘Øª Ø¨Ø¬Ø§Ù†Ø¨ Ø§Ù„Ù…Ù†Ø´ÙˆØ±Ø© Ù„Ø§ ÙÙˆÙ‚Ù‡Ø§.
         *
         * ØªÙˆÙ‚ÙŠØ¹ÙÙ‡Ù…Ø§ Ù…Ø®ØªÙ„ÙØŒ ÙØ§Ù„ØªØ«Ø¨ÙŠØª ÙÙˆÙ‚ Ø§Ù„Ù…Ù†Ø´ÙˆØ±Ø© ÙŠØ³ØªÙ„Ø²Ù… Ø­Ø°ÙÙ‡Ø§ â€” ÙˆØ­Ø°ÙÙÙ‡Ø§
         * ÙŠÙ…Ø­Ùˆ Ù…ÙƒØªØ¨Ø© Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù… ÙˆØªÙ‚Ø¯Ù‘Ù…Ù‡ ÙˆÙ…Ù„Ø§Ø­Ø¸Ø§ØªÙ‡. ÙˆÙ„Ø§Ø­Ù‚Ø©Ù Ø§Ù„Ù…Ø¹Ø±ÙÙ‘Ù ØªØ¬Ø¹Ù„Ù‡Ù…Ø§
         * ØªØ·Ø¨ÙŠÙ‚ÙŠÙ† Ù…Ø³ØªÙ‚Ù„Ù‘ÙŠÙ†ØŒ ÙÙ†Ø¬Ø±Ù‘Ø¨ Ø¹Ù„Ù‰ ÙˆØ§Ø­Ø¯Ø© ÙˆÙ„Ø§ Ù†Ù…Ø³Ù‘ Ø§Ù„Ø£Ø®Ø±Ù‰.
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
         * Ù†Ø³ØªØ¨Ø¹Ø¯ ÙˆØ§Ø¬Ù‡ØªÙŽÙŠ C ÙˆC++ Ù…Ù† sherpa â€” Ø£Ø±Ø¨Ø¹Ø© Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª ÙˆÙ†ØµÙ Ù„Ø§ Ù†Ø³ØªØ¯Ø¹ÙŠÙ‡Ø§.
         *
         * ÙƒÙˆØªÙ„Ù† ÙŠØ­Ù…Ù‘Ù„ `sherpa-onnx-jni` ÙˆØ­Ø¯Ù‡Ø§ØŒ ÙˆÙ‡ÙŠ Ù…Ø¨Ù†ÙŠÙ‘Ø© Ù…ÙƒØªÙÙŠØ© Ø¨Ø°Ø§ØªÙ‡Ø§.
         * Ø§Ù„ÙˆØ§Ø¬Ù‡ØªØ§Ù† Ø§Ù„Ø£Ø®Ø±ÙŠØ§Ù† Ù„Ù„Ù…Ø´Ø§Ø±ÙŠØ¹ Ø§Ù„ØªÙŠ ØªØ±Ø¨Ø· Ø¨Ù„ØºØ© C Ø£Ùˆ C++ Ù…Ø¨Ø§Ø´Ø±Ø©ØŒ ÙˆÙˆØ¬ÙˆØ¯Ù‡Ù…Ø§
         * ÙÙŠ Ø§Ù„Ø­Ø²Ù…Ø© Ø«Ù…Ù†ÙŒ ÙŠØ¯ÙØ¹Ù‡ ÙƒÙ„ Ù…Ø³ØªØ®Ø¯Ù… Ù…Ù‚Ø§Ø¨Ù„ Ø´ÙŠÙØ±Ø© Ù„Ø§ ØªØ¹Ù…Ù„ Ø¹Ù†Ø¯Ù‡ Ø£Ø¨Ø¯Ø§Ù‹.
         */
        jniLibs.excludes += setOf(
            "**/libsherpa-onnx-c-api.so",
            "**/libsherpa-onnx-cxx-api.so"
        )
    }
    // Ø¨ÙŠØ§Ù†Ø§Øª Ø§Ù„Ù…Ø±Ø¬Ø¹ (Ø£ÙƒØ³ÙÙˆØ±Ø¯/Ø§Ù„ØªØ±Ø¯Ø¯) ÙˆØ§Ù„ÙƒÙ„Ù…Ø§Øª ØªÙÙ‚Ø±Ø£ Ù…Ø¨Ø§Ø´Ø±Ø© Ù…Ù† Ø§Ù„Ø£ØµÙˆÙ„ â€” Ù…Ù†Ø¹ Ø§Ù„Ø¶ØºØ· ÙŠØ¬Ø¹Ù„ Ø§Ù„Ù‚Ø±Ø§Ø¡Ø© Ø£Ø³Ø±Ø¹
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
     * Ù…Ø­Ø±Ùƒ ÙƒÙˆÙƒÙˆØ±Ùˆ Ø§Ù„ØµÙˆØªÙŠ â€” Ø¹Ø¨Ø± sherpa-onnx.
     *
     * Ø³Ø¨Ù‚ Ø£Ù† Ø­ÙØ°ÙØª Ù‡Ø°Ù‡ Ø§Ù„Ù…ÙƒØªØ¨Ø§Øª Ù…Ø¹ Piper Ù„Ø£Ù†Ù‡ ÙƒØ§Ù† Ø£Ø¶Ø¹Ù Ù…Ù† Ù…Ø­Ø±Ùƒ Ø§Ù„Ø¬Ù‡Ø§Ø² Ù†ÙØ³Ù‡ â€”
     * Ø­Ù…Ù„Ø§Ù‹ Ø¨Ù„Ø§ Ù…Ù‚Ø§Ø¨Ù„. ÙˆØ¹Ø§Ø¯Øª Ù„Ø³Ø¨Ø¨ Ù…Ø¹Ø§ÙƒØ³ ØªÙ…Ø§Ù…Ø§Ù‹: ÙƒÙˆÙƒÙˆØ±Ùˆ Ø£ÙØ¶Ù„ ØµÙˆØª Ù…ÙØªÙˆØ­ Ù„Ù„Ø¥Ù†Ø¬Ù„ÙŠØ²ÙŠØ©
     * Ø§Ù„ÙŠÙˆÙ…ØŒ ÙˆØ§Ù„Ù…Ø³ØªØ®Ø¯Ù… Ø³Ù…Ø¹Ù‡ Ø¨Ø£Ø°Ù†Ù‡ ÙˆØ·Ù„Ø¨Ù‡.
     *
     * Ø§Ù„Ù…ÙƒØªØ¨Ø§Øª Ù‡Ù†Ø§ (~Ù£Ù  Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª) Ù„Ø£Ù† ØªØ­Ù…ÙŠÙ„ Ù…ÙƒØªØ¨Ø§Øª Ø£ØµÙ„ÙŠØ© Ø¯ÙŠÙ†Ø§Ù…ÙŠÙƒÙŠØ§Ù‹ Ù‡Ø´Ù‘ ÙˆÙ…Ù…Ù†ÙˆØ¹
     * Ù…ØªØ¬Ø±ÙŠØ§Ù‹ØŒ ÙˆØ§Ù„Ù†Ù…ÙˆØ°Ø¬ (~Ù¡Ù¤Ù  Ù…ÙŠØºØ§Ø¨Ø§ÙŠØª) ØªÙ†Ø²ÙŠÙ„ Ø§Ø®ØªÙŠØ§Ø±ÙŠ Ù…Ù† Ø§Ù„Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª â€” ÙÙ…Ù† ÙŠÙƒÙÙŠÙ‡
     * Ù…Ø­Ø±Ùƒ Ø¬Ù‡Ø§Ø²Ù‡ Ù„Ø§ ÙŠÙ†Ø²Ù‘Ù„ Ø´ÙŠØ¦Ø§Ù‹.
     */
    implementation(files("libs/sherpa-onnx-1.13.4.aar"))
    // ÙÙƒÙ‘ Ø¶ØºØ· Ø­Ø²Ù…Ø© Ø§Ù„Ù†Ù…ÙˆØ°Ø¬ (tar.bz2) â€” Ù„Ø§ Ø¯Ø¹Ù… bzip2 ÙÙŠ Ù…ÙƒØªØ¨Ø© Ø¬Ø§ÙØ§ Ø§Ù„Ù‚ÙŠØ§Ø³ÙŠØ©
    implementation("org.apache.commons:commons-compress:1.27.1")

    // ØªØ®Ø²ÙŠÙ† Ù…ÙØªØ§Ø­ Ø§Ù„Ø®Ø¯Ù…Ø© Ù…Ø´ÙÙ‘Ø±Ø§Ù‹ Ø¨Ù…ÙØªØ§Ø­ Ø¹ØªØ§Ø¯ÙŠ â€” Ù„Ø§ ÙŠÙØ­ÙØ¸ Ù†ØµØ§Ù‹ ØµØ±ÙŠØ­Ø§Ù‹ Ø£Ø¨Ø¯Ø§Ù‹
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Ø§Ø®ØªØ¨Ø§Ø±Ø§Øª ØªØ¹Ù…Ù„ Ø¹Ù„Ù‰ Ø§Ù„Ø­Ø§Ø³ÙˆØ¨ Ø¨Ù„Ø§ Ø¬Ù‡Ø§Ø² â€” ØªØºØ·Ù‘ÙŠ Ù…Ù†Ø·Ù‚ Ø§Ù„ØµÙˆØª ÙˆØ§Ù„Ø¨ÙŠØ§Ù†Ø§Øª
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric ÙŠØ­ØªØ§Ø¬ Ù…ÙˆØ§Ø±Ø¯ Ø£Ù†Ø¯Ø±ÙˆÙŠØ¯ Ø§Ù„Ø­Ù‚ÙŠÙ‚ÙŠØ© Ù„ØªØ´ØºÙŠÙ„ Ø§Ù„Ø§Ø®ØªØ¨Ø§Ø±Ø§Øª Ø¹Ù„Ù‰ JVM
android.testOptions.unitTests.isIncludeAndroidResources = true

