package com.lagradost.cloudstream3.ui.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.mvvm.debugAssert
import com.lagradost.cloudstream3.mvvm.debugWarning
import com.lagradost.cloudstream3.mvvm.launchSafe
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.DataStoreHelper.currentAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class ExpandableSearchList(
    var list: List<SearchResponse>, var currentPage: Int, var hasNext: Boolean,
)

const val SEARCH_HISTORY_KEY = "search_history"

class SearchViewModel : ViewModel() {
    /**
     * true  = hanya tampilkan hasil yang judulnya cocok dengan kata kunci
     * false = tampilkan semua hasil, hanya diurutkan berdasarkan kecocokan
     */
    private val STRICT_NAME_MATCH = true

    private fun normalizeTitle(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Skor kecocokan judul terhadap kata kunci (makin kecil makin cocok).
     * null = tidak cocok. Setiap kata kunci harus menjadi awalan salah satu kata di judul
     * (aksara CJK dicocokkan sebagai potongan teks karena tidak memakai spasi).
     */
    private fun matchScore(query: String, title: String): Int? {
        val q = normalizeTitle(query)
        val n = normalizeTitle(title)
        if (q.isEmpty()) return 0
        val words = n.split(" ")
        val tokens = q.split(" ")
        val allMatch = tokens.all { token ->
            words.any { it.startsWith(token) } ||
                (token.any { it.code > 0x2E80 } && n.contains(token))
        }
        if (!allMatch) return null
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            " $n".contains(" $q") -> 2
            else -> 3
        }
    }

    private fun filterAndRank(query: String, items: List<SearchResponse>): List<SearchResponse> {
        val scored = items.map { it to matchScore(query, it.name) }
        val kept = if (STRICT_NAME_MATCH) scored.filter { it.second != null } else scored
        // sortedBy stabil: urutan asli dipertahankan untuk skor yang sama
        return kept.sortedBy { it.second ?: Int.MAX_VALUE }.map { it.first }
    }

    /** Harus sama persis dengan MainAPI.name milik plugin MyHomepage. */
    private val MAIN_SEARCH_PROVIDER = "🏠 My Homepage"

    private val _searchResponse: MutableLiveData<Resource<ExpandableSearchList>> =
        MutableLiveData()
    val searchResponse: LiveData<Resource<ExpandableSearchList>> get() = _searchResponse

    private val _currentSearch: MutableLiveData<Map<String, ExpandableSearchList>> =
        MutableLiveData()
    val currentSearch: LiveData<Map<String, ExpandableSearchList>> get() = _currentSearch

    private val _currentHistory: MutableLiveData<List<SearchHistoryItem>> = MutableLiveData()
    val currentHistory: LiveData<List<SearchHistoryItem>> get() = _currentHistory

    private val _searchSuggestions: MutableLiveData<List<String>> = MutableLiveData()
    val searchSuggestions: LiveData<List<String>> get() = _searchSuggestions

    private var suggestionJob: Job? = null

    private var repos = apis.withLock { apis.map { APIRepository(it) } }

    fun clearSearch() {
        _searchResponse.postValue(Resource.Success(ExpandableSearchList(emptyList(), 0, false)))
        _currentSearch.postValue(emptyMap())
        expandableSearches.clear()
    }

    var lastQuery: String? = null

    /** Save which providers can searched again and which search result page they are on.
     * Maps provider name to search list.
     * @see [HomeViewModel.expandable] */
    private val expandableSearches: MutableMap<String, ExpandableSearchList> = mutableMapOf()

    private var currentSearchIndex = 0
    private var onGoingSearch: Job? = null

    fun reloadRepos() {
        repos = apis.withLock { apis.map { APIRepository(it) } }
    }

    fun searchAndCancel(
        query: String,
        providersActive: Set<String> = setOf(),
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) {
        currentSearchIndex++
        onGoingSearch?.cancel()
        onGoingSearch = search(query, providersActive, ignoreSettings, isQuickSearch)
    }

    fun updateHistory() = ioSafe {
        val items = getKeys("$currentAccount/$SEARCH_HISTORY_KEY")?.mapNotNull {
            getKey<SearchHistoryItem>(it)
        }?.sortedByDescending { it.searchedAt } ?: emptyList()
        _currentHistory.postValue(items)
    }

    /**
     * Fetches search suggestions with debouncing.
     * Waits 300ms before making the API call to avoid too many requests.
     * 
     * @param query The search query to get suggestions for
     */
    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        
        if (query.isBlank() || query.length < 2) {
            _searchSuggestions.postValue(emptyList())
            return
        }
        
        suggestionJob = ioSafe {
            delay(300) // Debounce
            val suggestions = SearchSuggestionApi.getSuggestions(query)
            _searchSuggestions.postValue(suggestions)
        }
    }

    /**
     * Clears the current search suggestions.
     */
    fun clearSuggestions() {
        suggestionJob?.cancel()
        _searchSuggestions.postValue(emptyList())
    }

    private val lock: MutableSet<String> = mutableSetOf()

    // ExpandableHomepageList because the home adapter is reused in the search fragment
    suspend fun expandAndReturn(name: String): HomeViewModel.ExpandableHomepageList? {
        if (lock.contains(name)) return null
        val query = lastQuery ?: return null
        val repo = repos.find { it.name == name } ?: return null

        lock += name

        expandableSearches[name]?.let { current ->
            debugAssert({ !current.hasNext }) {
                "Expand called when not needed"
            }

            val nextPage = current.currentPage + 1
            val next = repo.search(query, nextPage)
            if (next is Resource.Success) {
                val nextValue = next.value
                expandableSearches[name]?.apply {
                    this.hasNext = nextValue.hasNext
                    this.currentPage = nextPage

                    debugWarning({ nextValue.items.any { outer -> this.list.any { it.url == outer.url } } }) {
                        "Expanded search contained an item that was previously already in the list.\nQuery = $query, ${nextValue.items} = ${this.list}"
                    }

                    // just to be sure we are not adding the same shit for some reason
                    // Avoids weird behavior in the recyclerview by recreating the list
                    this.list = (this.list + filterAndRank(query, nextValue.items)).distinctBy { it.url }
                } ?: debugWarning {
                    "Expanded an item not in search load named $name, current list is ${expandableSearches.keys}"
                }
            } else {
                current.hasNext = false
            }

            _searchResponse.postValue(Resource.Success(bundleSearch(expandableSearches)))
            _currentSearch.postValue(expandableSearches)
        }

        lock -= name

        val item = expandableSearches[name] ?: return null
        return HomeViewModel.ExpandableHomepageList(
            HomePageList(name, item.list),
            item.currentPage,
            item.hasNext
        )
    }

    private fun bundleSearch(lists: MutableMap<String, ExpandableSearchList>): ExpandableSearchList {
        if (lists.size == 1) {
            return lists.values.first()
        }

        val list = ArrayList<SearchResponse>()
        val nestedList =
            lists.map { it.value.list }

        // I do it this way to move the relevant search results to the top
        var index = 0
        while (true) {
            var added = 0
            for (sublist in nestedList) {
                if (sublist.size > index) {
                    list.add(sublist[index])
                    added++
                }
            }
            if (added == 0) break
            index++
        }

        // Hapus hasil ganda antar sumber (judul + tipe yang sama)
        // Urutkan ulang gabungan berdasarkan kecocokan dengan kata kunci
        val ranked = lastQuery?.let { filterAndRank(it, list) } ?: list
        val unique = ranked.distinctBy { it.name.trim().lowercase() to it.type }

        return ExpandableSearchList(unique, 1, false)
    }

    private fun search(
        query: String,
        providersActive: Set<String>,
        ignoreSettings: Boolean = false,
        isQuickSearch: Boolean = false,
    ) =
        viewModelScope.launchSafe {
            val currentIndex = currentSearchIndex
            if (query.length <= 1) {
                clearSearch()
                return@launchSafe
            }

            if (!isQuickSearch) {
                val key = query.hashCode().toString()
                setKey(
                    "$currentAccount/$SEARCH_HISTORY_KEY",
                    key,
                    SearchHistoryItem(
                        searchedAt = System.currentTimeMillis(),
                        searchText = query,
                        type = emptyList(), // TODO implement tv type
                        key = key,
                    )
                )
            }

            _searchResponse.postValue(Resource.Loading())
            _currentSearch.postValue(emptyMap())
            expandableSearches.clear()

            lastQuery = query

            withContext(Dispatchers.IO) { // This interrupts UI otherwise
                // Pencarian hanya lewat provider utama (MyHomepage) kalau tersedia,
                // karena ia sudah menggabungkan semua sumber. Jika belum terpasang, pakai semua provider.
                val mainRepos = repos.filter { a ->
                    a.name == MAIN_SEARCH_PROVIDER && (!isQuickSearch || a.hasQuickSearch)
                }
                val activeRepos = mainRepos.ifEmpty {
                    repos.filter { a ->
                        (ignoreSettings || (providersActive.isEmpty() || providersActive.contains(a.name))) && (!isQuickSearch || a.hasQuickSearch)
                    }
                }
                activeRepos.amap { a -> // Parallel
                    val search = if (isQuickSearch) a.quickSearch(query) else a.search(query, 1)
                    if (currentSearchIndex != currentIndex) return@amap
                    if (search is Resource.Success) {
                        val searchValue = search.value
                        expandableSearches[a.name] =
                            ExpandableSearchList(filterAndRank(query, searchValue.items), 1, searchValue.hasNext)
                    }

                    _currentSearch.postValue(expandableSearches)
                }

                if (currentSearchIndex != currentIndex) return@withContext // this should prevent rewrite of existing data bug

                _currentSearch.postValue(expandableSearches)
                val list = bundleSearch(expandableSearches)

                _searchResponse.postValue(Resource.Success(list))
            }
        }
}
