package com.tornado.vocab.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tornado.vocab.data.WordStatus

/**
 * شريحة مرشِّح مدمجة تعيش داخل صف واحد بلا تمرير.
 *
 * التسمية فوق والعدد تحت، فيتّسع الصف لخمس شرائح على أضيق شاشة دون أن
 * يُقتطع نص أو يُدفع زر خارج الرؤية. كل الشرائح متساوية العرض بـweight،
 * وهو ما يعطي التوزيع المنتظم المطلوب.
 */
@Composable
fun CompactChip(
    label: String,
    count: Int?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "compactChipBg"
    )
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (dotColor != null && !selected) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (count != null) {
            Text(
                count.toString(),
                color = fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

/** شريحة اختيار — العنصر الأساسي للتصفية في كل الشاشات */
@Composable
fun FilterChipRow(
    label: String,
    count: Int?,
    selected: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        label = "chipBg"
    )
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (dotColor != null) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        }
        Text(
            if (count != null) "$label ($count)" else label,
            color = fg,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/** شارة معلومات صغيرة — المستوى، نوع الكلمة، التردد */
@Composable
fun InfoBadge(
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    dashed: Boolean = false
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (dashed) 1.dp else 0.dp,
                color = if (dashed) color.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * مصنّف الكلمة بالألوان.
 *
 * ثلاث دوائر ملوّنة تحلّ محل زر الحفظ الغامض: ضغطة واحدة تنقل الكلمة إلى
 * تصنيفها، والقوائم والمرشّحات تعكس التغيير فوراً. اللون هو نفسه المستخدم
 * في كل شاشات التطبيق، فالمعنى يُتعلَّم مرة ويُقرأ في كل مكان.
 */
@Composable
fun StatusPicker(
    current: WordStatus,
    onPick: (WordStatus) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        WordStatus.entries.forEach { status ->
            val selected = status == current
            val color = StatusColors.of(status)
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) color else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (selected) color else color.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(20.dp)
                    )
                    .clickable { onPick(status) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    StatusColors.label(status),
                    color = if (selected) Color.White else color,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun StatusDot(status: WordStatus, size: Int = 8) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(StatusColors.of(status)))
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        modifier = modifier.padding(top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

/** حالة فارغة موحّدة — تشرح ما الذي يفعله المستخدم بدل ترك شاشة بيضاء */
@Composable
fun EmptyState(
    icon: String,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (action != null) { Spacer(Modifier.height(18.dp)); action() }
    }
}

/** مقياس تقدّم أفقي مع تسمية — يُستخدم في لوحة الإحصاءات */
@Composable
fun StatBar(label: String, value: Int, total: Int, color: Color) {
    val fraction = if (total <= 0) 0f else value.toFloat() / total
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "$value",
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

/** بطاقة رقم كبير — تستخدمها لوحة التقدّم */
@Composable
fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 14.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = color)
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

@Composable
fun HSpace(dp: Int) = Spacer(Modifier.width(dp.dp))

@Composable
fun VSpace(dp: Int) = Spacer(Modifier.height(dp.dp))
