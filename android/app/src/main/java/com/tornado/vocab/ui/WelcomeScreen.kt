package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Step(
    val emoji: String,
    val title: String,
    val body: String
)

/*
 * ثلاث خطوات لا أكثر.
 *
 * الترحيب الطويل يُتخطّى بلا قراءة، والقصير جداً لا يشرح شيئاً. وثلاث شاشات
 * تكفي للأسئلة التي يطرحها كل من يفتح التطبيق أول مرة: ما هذا؟ وماذا أفعل؟
 * ولماذا ينزّل شيئاً الآن؟
 *
 * والثالثة أهمّها: التنزيل الكبير يقع في الخلفية عند أول فتحة، ومن لا يعرف
 * سببه يظنّ التطبيق يتلصّص أو يستهلك باقته.
 */
private val STEPS = listOf(
    Step(
        "🎧",
        "Words you can listen to",
        "Tornado reads your vocabulary aloud — the word, its meaning, and a real " +
            "example sentence. It keeps playing with the screen off, like a podcast."
    ),
    Step(
        "📝",
        "Add a word, get everything",
        "Type any English word. Tornado fetches its meanings, pronunciation, level " +
            "and examples for you. Paste long texts into Notes and it reads those too."
    ),
    Step(
        "🗣️",
        "A voice worth hearing",
        "A premium British voice downloads once over Wi-Fi (about 330 MB) and then " +
            "works entirely offline. Until it arrives, your phone's own voice is used — " +
            "nothing waits on it."
    )
)

/**
 * شاشة الترحيب — تُعرض مرة واحدة عند أول تشغيل.
 *
 * غيابها كان أكبر ثغرة في المنتج: يفتح المستخدم تطبيقاً يجد فيه كلمات ليست
 * كلماته، وأزراراً بأسماء لا تعني له شيئاً (FULL · Say ×2)، وتنزيلاً بمئات
 * الميغابايتات يجري بلا تفسير. فيحذفه قبل أن يفهم أنه مشغّل صوت لا قاموس.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    val step = STEPS[index]
    val last = index == STEPS.lastIndex

    val gradient = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.background,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        )
    )

    Column(
        Modifier.fillMaxSize().background(gradient).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // التخطّي حاضر دائماً — إجبار أحد على قراءة ثلاث شاشات يبدأ العلاقة بضيق
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) {
                Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(step.emoji, fontSize = 64.sp)
            VSpace(28)
            Text(
                step.title,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            VSpace(16)
            Text(
                step.body,
                fontSize = 16.sp,
                lineHeight = 26.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            Modifier.padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            STEPS.indices.forEach { i ->
                Box(
                    Modifier
                        .size(if (i == index) 10.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (i == index) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                )
            }
        }

        Button(
            onClick = { if (last) onDone() else index++ },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                if (last) "Start learning" else "Next",
                Modifier.padding(vertical = 6.dp),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        VSpace(12)
    }
}
