package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.data.LookupResult
import com.tornado.vocab.data.Meaning
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.derive
import com.tornado.vocab.tornado
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BatchProgress(val done: Int, val total: Int, val added: Int, val failed: List<String>)

data class AddState(
    val input: String = "",
    val busy: Boolean = false,
    val statusText: String = "",
    val lastAdded: Word? = null,
    val manualPrompt: String? = null,
    val batchInput: String = "",
    val batch: BatchProgress? = null,
    val error: String? = null
)

class AddWordViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.tornado.repository
    private val dict = app.tornado.dictionary
    private val container = app.tornado
    private val settings = app.tornado.settings

    /**
     * يدفع الإضافة إلى نسخة الكمبيوتر فور حدوثها.
     *
     * مزامنة تحتاج ضغطة زر تُنسى، فتتفرّع المكتبة بين الجهازين بلا أن يلاحظ
     * أحد. والدفع هنا صامت: نجاحه لا يستحق إشعاراً، وفشله لا يستحق مقاطعة —
     * الكلمة محفوظة محلياً على كل حال، والمحاولة تتكرر عند فتح التطبيق.
     */
    private fun pushToComputer() = viewModelScope.launch {
        runCatching {
            container.sync.repo = settings.syncRepo()
            if (container.sync.canPush) container.sync.sync(push = true)
        }
        // الكلمة المضافة للتوّ قد تكون وصلت ناقصة — تدخل طابور الإثراء فوراً
        container.appScope.launch { runCatching { container.enricher.runUntilComplete() } }
    }

    private val _state = MutableStateFlow(AddState())
    val state: StateFlow<AddState> = _state.asStateFlow()

    private var job: Job? = null

    fun setInput(v: String) = update { it.copy(input = v, error = null) }
    fun setBatchInput(v: String) = update { it.copy(batchInput = v) }

    fun add() {
        val w = _state.value.input.trim().replace(Regex("\\s+"), " ")
        if (w.isBlank() || _state.value.busy) return
        job?.cancel()
        job = viewModelScope.launch {
            if (repo.wordByName(w) != null) {
                update { it.copy(error = "\"$w\" is already in your words") }
                return@launch
            }
            update { it.copy(busy = true, statusText = "Looking up \"$w\"…", error = null, manualPrompt = null) }
            when (val result = dict.lookup(w)) {
                is LookupResult.Success -> {
                    repo.addWord(result.word)
                    pushToComputer()
                    update {
                        it.copy(
                            busy = false, input = "", statusText = "",
                            lastAdded = result.word, manualPrompt = null
                        )
                    }
                }
                is LookupResult.NotFound -> update {
                    it.copy(
                        busy = false, statusText = "",
                        error = "Could not recognise \"${result.query}\" — check the spelling",
                        manualPrompt = result.query
                    )
                }
                is LookupResult.Failed -> update {
                    it.copy(
                        busy = false, statusText = "",
                        error = "Connection problem — retry, or type the meaning yourself",
                        manualPrompt = result.query
                    )
                }
            }
        }
    }

    /** حفظ يدوي حين يعجز البحث الآلي — البطاقة تبقى قابلة للترقية لاحقاً */
    fun saveManual(meaning: String) {
        val w = _state.value.manualPrompt ?: return
        if (meaning.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val card = Word(
                id = now,
                word = w,
                meanings = listOf(Meaning(null, "", meaning.trim())),
                createdAt = now
            ).derive()
            repo.addWord(card)
            pushToComputer()
            update { it.copy(input = "", manualPrompt = null, lastAdded = card, error = null) }
        }
    }

    /**
     * إضافة دفعة. الكلمات تُعالَج واحدة تلو الأخرى عمداً: المصادر المجانية
     * تحدّ من الطلبات المتوازية، والتسلسل هنا أسرع فعلياً من التوازي المرفوض.
     * الكلمات الفاشلة تبقى في الصندوق لإعادة المحاولة عليها وحدها.
     */
    fun runBatch() {
        val raw = _state.value.batchInput
        val words = raw.split(Regex("[,\\n\\r]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.isEmpty() || _state.value.busy) return
        job?.cancel()
        job = viewModelScope.launch {
            update { it.copy(busy = true, batch = BatchProgress(0, words.size, 0, emptyList())) }
            var added = 0
            val failed = mutableListOf<String>()
            words.forEachIndexed { i, w ->
                if (repo.wordByName(w) == null) {
                    when (val r = dict.lookup(w)) {
                        is LookupResult.Success -> { repo.addWord(r.word); added++ }
                        else -> failed += w
                    }
                }
                update { it.copy(batch = BatchProgress(i + 1, words.size, added, failed.toList())) }
            }
            // دفعة واحدة بعد الدفعة كلها لا بعد كل كلمة — رفع لكل كلمة يعني
            // عشرات الطلبات على GitHub لعمل واحد
            if (added > 0) pushToComputer()
            update {
                it.copy(
                    busy = false,
                    batchInput = failed.joinToString("\n"),
                    statusText = "Added $added of ${words.size}" +
                        if (failed.isEmpty()) "" else " — ${failed.size} left to retry"
                )
            }
        }
    }

    fun cancel() {
        job?.cancel()
        update { it.copy(busy = false, statusText = "") }
    }

    fun clearError() = update { it.copy(error = null) }
    fun clearLastAdded() = update { it.copy(lastAdded = null) }

    private fun update(block: (AddState) -> AddState) { _state.value = block(_state.value) }
}
