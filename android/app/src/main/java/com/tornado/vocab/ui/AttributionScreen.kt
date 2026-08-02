package com.tornado.vocab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** مصدر بيانات ورخصته — ما يجب أن يُنسب */
private data class Credit(
    val name: String,
    val what: String,
    val licence: String,
    val url: String
)

/*
 * كل ما في التطبيق من بيانات غير مؤلَّفة محلياً، ورخصة كل مصدر.
 *
 * القائمة ليست تجميلاً: رخص المشاع الإبداعي تشترط الإسناد شرطاً لصحة
 * الاستعمال، فغيابها يجعل الاستعمال نفسه مخالفاً مهما كانت الرخصة مفتوحة.
 *
 * وترتيبها بحسب ما يراه المستخدم أكثر: المعاني أولاً، ثم الأمثلة، ثم الصوت.
 */
private val CREDITS = listOf(
    Credit(
        "Wiktionary",
        "Word meanings, pronunciations and usage examples, retrieved through freedictionaryapi.com",
        "CC BY-SA 4.0",
        "https://en.wiktionary.org/wiki/Wiktionary:Copyrights"
    ),
    Credit(
        "Tatoeba",
        "Example sentences written by volunteers",
        "CC BY 2.0 FR",
        "https://tatoeba.org/en/downloads"
    ),
    Credit(
        "Datamuse",
        "Synonyms, antonyms, collocations and spelling suggestions",
        "Free API — no licence claimed on results",
        "https://www.datamuse.com/api/"
    ),
    Credit(
        "MyMemory",
        "Arabic translations",
        "Translated.net translation memory",
        "https://mymemory.translated.net/"
    ),
    Credit(
        "Wikimedia Commons",
        "Human pronunciation recordings",
        "CC BY-SA / CC BY, per file",
        "https://commons.wikimedia.org/wiki/Commons:Licensing"
    ),
    Credit(
        "Kokoro (hexgrad) via sherpa-onnx",
        "The premium on-device voice",
        "Apache 2.0",
        "https://huggingface.co/hexgrad/Kokoro-82M"
    ),
    Credit(
        "Oxford 3000/5000 word list",
        "CEFR levels only — no dictionary text or audio is used",
        "Published word list, used as factual reference",
        "https://www.oxfordlearnersdictionaries.com/wordlists/"
    )
)

/**
 * شاشة الإسناد.
 *
 * مطلوبة قانوناً لرخص المشاع الإبداعي، ومطلوبة أخلاقياً لمن كتب هذه الجمل
 * وسجّل هذه الأصوات تطوّعاً. وموضعها في الإعدادات لا مدفونة في «حول»: من
 * يبحث عن مصدر معنى يجب أن يجده.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttributionScreen(onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources & licences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            VSpace(8)
            Text(
                "Tornado is built on open data. Every meaning, example and recording " +
                    "below comes from people who chose to share their work — these are " +
                    "their terms.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpace(20)

            CREDITS.forEach { c ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { runCatching { uri.openUri(c.url) } }
                        .padding(vertical = 12.dp)
                ) {
                    Text(
                        c.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    VSpace(3)
                    Text(
                        c.what,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VSpace(4)
                    Text(
                        c.licence,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }

            VSpace(20)
            Text(
                "Content taken from Wiktionary stays under CC BY-SA 4.0. Where Tornado " +
                    "shortens or cleans a definition, that edited text remains under the " +
                    "same licence and may be reused on the same terms.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            VSpace(40)
        }
    }
}
