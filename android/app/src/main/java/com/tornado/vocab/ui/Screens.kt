package com.tornado.vocab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tornado.vocab.audio.PlayerController
import com.tornado.vocab.data.Word

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordListScreen(vm: WordViewModel, onOpen: (Long) -> Unit) {
    val words by vm.words.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val favOnly by vm.favOnly.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TORNADO", fontWeight = FontWeight.ExtraBold) },
                actions = {
                    IconButton(onClick = { vm.toggleFavOnly() }) {
                        Icon(
                            if (favOnly) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = "Favorites"
                        )
                    }
                    IconButton(onClick = { PlayerController.playAll(words) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play all")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search words or meanings…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true
            )
            Text(
                "${words.size} words",
                Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(words, key = { it.id }) { w ->
                    WordRow(w, onOpen = { onOpen(w.id) }, onFav = { vm.toggleFavorite(w) },
                        onPlay = { PlayerController.playOne(w) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun WordRow(w: Word, onOpen: () -> Unit, onFav: () -> Unit, onPlay: () -> Unit) {
    ListItem(
        headlineContent = { Text(w.word, fontWeight = FontWeight.SemiBold, fontSize = 17.sp) },
        supportingContent = {
            val bits = listOfNotNull(
                w.ipa.takeIf { it.isNotBlank() },
                w.cefr.takeIf { it.isNotBlank() },
                w.posCsv.takeIf { it.isNotBlank() }
            )
            if (bits.isNotEmpty()) Text(bits.joinToString(" · "))
        },
        leadingContent = {
            IconButton(onClick = onPlay) { Icon(Icons.Filled.VolumeUp, "Play") }
        },
        trailingContent = {
            IconButton(onClick = onFav) {
                Icon(if (w.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite")
            }
        },
        modifier = Modifier.clickable(onClick = onOpen)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordDetailScreen(vm: WordViewModel, id: Long, onBack: () -> Unit) {
    val word by vm.word(id).collectAsStateWithLifecycle(initialValue = null)
    val w = word ?: return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(w.word) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.toggleFavorite(w) }) {
                        Icon(if (w.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, "Favorite")
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(w.word, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                if (w.ipa.isNotBlank() || w.arabicPron.isNotBlank())
                    Text(listOf(w.ipa, w.arabicPron).filter { it.isNotBlank() }.joinToString("  "),
                        style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (w.audioUS.isNotBlank())
                        AssistChip(onClick = { PlayerController.playOne(w) },
                            label = { Text("US") }, leadingIcon = { Icon(Icons.Filled.VolumeUp, null) })
                    if (w.oxford.isNotBlank()) AssistChip(onClick = {}, label = { Text("Oxford ${w.oxford}") })
                    if (w.cefr.isNotBlank()) AssistChip(onClick = {}, label = { Text("CEFR ${w.cefr}") })
                }
            }
            val meanings = vm.repo.meanings(w.meaningsJson)
            if (meanings.isNotEmpty()) item { SectionTitle("Meanings") }
            items(meanings) { m ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(buildString {
                        if (!m.pos.isNullOrBlank()) append("(${m.pos}) ")
                        append(m.en)
                    }, style = MaterialTheme.typography.bodyLarge)
                    if (m.ar.isNotBlank())
                        Text(m.ar, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                }
            }
            if (w.inflectionsCsv.isNotBlank()) item {
                SectionTitle("Forms"); Text(w.inflectionsCsv)
            }
            val syn = vm.repo.pairs(w.synonymsJson)
            if (syn.isNotEmpty()) { item { SectionTitle("Synonyms") }
                items(syn) { p -> PairRow(p.en, p.ar) } }
            val coll = vm.repo.pairs(w.collocationsJson)
            if (coll.isNotEmpty()) { item { SectionTitle("Collocations") }
                items(coll) { p -> PairRow(p.en, p.ar) } }
            val ex = vm.repo.pairs(w.examplesJson)
            if (ex.isNotEmpty()) { item { SectionTitle("Examples") }
                items(ex) { p -> PairRow(p.en, p.ar) } }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, Modifier.padding(top = 18.dp, bottom = 6.dp),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
}

@Composable
private fun PairRow(en: String, ar: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(en)
        if (ar.isNotBlank()) Text(ar, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
