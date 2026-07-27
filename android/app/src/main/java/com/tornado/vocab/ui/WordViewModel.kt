package com.tornado.vocab.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tornado.vocab.data.Word
import com.tornado.vocab.data.WordRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WordViewModel(app: Application) : AndroidViewModel(app) {

    val repo = WordRepository(app)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _favOnly = MutableStateFlow(false)
    val favOnly: StateFlow<Boolean> = _favOnly.asStateFlow()

    /** بحث فوري: يتفاعل مع الكتابة مع تهدئة 200ms لتجنب استعلامات زائدة */
    val words: StateFlow<List<Word>> =
        combine(_query.debounce(200), _favOnly) { q, fav -> q to fav }
            .flatMapLatest { (q, fav) ->
                when {
                    q.isNotBlank() -> repo.search(q)
                    fav -> repo.favorites()
                    else -> repo.all()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init { viewModelScope.launch { repo.seedIfEmpty() } }

    fun setQuery(q: String) { _query.value = q }
    fun toggleFavOnly() { _favOnly.value = !_favOnly.value }
    fun toggleFavorite(w: Word) = viewModelScope.launch { repo.toggleFavorite(w) }
    fun word(id: Long) = repo.byId(id)
}
