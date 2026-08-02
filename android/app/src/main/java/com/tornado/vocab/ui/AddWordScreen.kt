package com.tornado.vocab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * إضافة كلمة.
 *
 * البطاقة تُبنى تلقائياً من قواميس مفتوحة: المعاني والنطق والمستوى والتصريفات
 * والمرادفات والمتلازمات والأمثلة، مع ترجمة عربية لكل قسم. المستخدم يكتب كلمة فقط.
 */
@Composable
fun AddWordScreen(vm: AddWordViewModel, onOpenWord: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var batchOpen by remember { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Type the word — we do the rest", style = MaterialTheme.typography.titleMedium)
        VSpace(12)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.input,
                onValueChange = vm::setInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("e.g. resilient") },
                singleLine = true,
                enabled = !state.busy,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.add() })
            )
            HSpace(8)
            Button(
                onClick = vm::add,
                enabled = !state.busy && state.input.isNotBlank(),
                modifier = Modifier.height(56.dp)
            ) {
                if (state.busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); HSpace(4); Text("Add")
                }
            }
        }

        if (state.statusText.isNotBlank()) {
            VSpace(8)
            Text(
                state.statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        state.error?.let { err ->
            VSpace(10)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(12.dp)
            ) {
                Text(err, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
            }
        }

        // إدخال يدوي حين يعجز البحث الآلي — أفضل من فقدان الكلمة تماماً
        if (state.manualPrompt != null) {
            VSpace(12)
            Text(
                "Save \"${state.manualPrompt}\" with your own meaning:",
                style = MaterialTheme.typography.bodyMedium
            )
            VSpace(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualText,
                    onValueChange = { manualText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب المعنى") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
                HSpace(8)
                OutlinedButton(
                    onClick = { vm.saveManual(manualText); manualText = "" },
                    modifier = Modifier.height(56.dp)
                ) { Text("Save") }
            }
            VSpace(6)
            TextButton(onClick = vm::add) { Text("🔄 Try automatic lookup again") }
        }

        VSpace(20)
        OutlinedButton(
            onClick = { batchOpen = !batchOpen },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.LibraryAdd, null, Modifier.size(18.dp))
            HSpace(8)
            Text(if (batchOpen) "Hide batch add" else "Batch add — paste a list of words")
        }

        if (batchOpen) {
            VSpace(10)
            OutlinedTextField(
                value = state.batchInput,
                onValueChange = vm::setBatchInput,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("One word per line, or comma separated\nrun, brand, convey\nresilient") },
                shape = RoundedCornerShape(14.dp),
                enabled = !state.busy
            )
            VSpace(8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = vm::runBatch,
                    enabled = !state.busy && state.batchInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("Start batch") }
                if (state.busy) OutlinedButton(onClick = vm::cancel) { Text("Stop") }
            }
            state.batch?.let { b ->
                VSpace(10)
                LinearProgressIndicator(
                    progress = { if (b.total == 0) 0f else b.done.toFloat() / b.total },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                VSpace(6)
                Text(
                    "${b.done} / ${b.total} processed  ·  ${b.added} added" +
                        if (b.failed.isNotEmpty()) "  ·  ${b.failed.size} failed" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        state.lastAdded?.let { w ->
            VSpace(20)
            AddedPreview(w, onOpen = { onOpenWord(w.id) })
        }

        VSpace(24)
        Text(
            "Powered by free open dictionaries — no account, no key. " +
                "An internet connection is needed only while adding new words; " +
                "reviewing and listening work fully offline.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        VSpace(32)
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AddedPreview(w: com.tornado.vocab.data.Word, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Text("✅ Added", style = MaterialTheme.typography.labelMedium, color = StatusColors.Known)
        VSpace(6)
        Text(w.word, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        if (w.arabicPron.isNotBlank()) {
            Text(w.arabicPron, color = MaterialTheme.colorScheme.primary)
        }
        VSpace(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (w.cefr.isNotBlank()) InfoBadge("CEFR ${w.cefr}", MaterialTheme.colorScheme.primary)
            if (w.oxford.isNotBlank()) InfoBadge("Oxford ${w.oxford}", MaterialTheme.colorScheme.primary)
            if (w.cefr.isBlank() && w.estCefr.isNotBlank()) {
                InfoBadge("≈ ${w.estCefr}", StatusColors.New, dashed = true)
            }
            w.pos.forEach { InfoBadge(it) }
        }
        w.meanings.firstOrNull()?.let { m ->
            VSpace(10)
            if (m.en.isNotBlank()) Text(m.en, style = MaterialTheme.typography.bodyMedium)
            if (m.ar.isNotBlank()) {
                Text(m.ar, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
        VSpace(10)
        TextButton(onClick = onOpen) { Text("Open full card →") }
    }
}
