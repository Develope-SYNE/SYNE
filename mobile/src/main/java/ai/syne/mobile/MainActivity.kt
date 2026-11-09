package ai.syne.mobile

import ai.syne.core.design.SyneDesign
import ai.syne.core.design.SyneDims
import ai.syne.core.design.SyneType
import ai.syne.core.share.SharedTrack
import ai.syne.mobile.data.LibraryStore
import ai.syne.mobile.data.Player
import ai.syne.mobile.ui.MobileRoot
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Tab { Home, Library, Records, Connect }

sealed interface Screen {
    data class AlbumDetail(val uid: String) : Screen
    data class NowPlaying(val uid: String) : Screen
    data class Record(val uid: String) : Screen
    data class Edit(val uid: String) : Screen
}

class MobileViewModel(app: Application) : AndroidViewModel(app) {
    val store = LibraryStore(app)
    val player = Player(store, viewModelScope)

    private val _tab = MutableStateFlow(Tab.Home)
    val tab: StateFlow<Tab> = _tab.asStateFlow()
    private val _stack = MutableStateFlow<List<Screen>>(emptyList())
    val screen: StateFlow<Screen?> = _stack.map { it.lastOrNull() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _filter = MutableStateFlow<String?>(null)
    val filter: StateFlow<String?> = _filter.asStateFlow()
    fun setFilter(g: String?) { _filter.value = g }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    fun clearToast() { _toast.value = null }

    fun selectTab(t: Tab) { _tab.value = t; _stack.value = emptyList() }
    fun open(s: Screen) { _stack.value = _stack.value + s }
    fun back(): Boolean {
        if (_stack.value.isEmpty()) return if (_tab.value != Tab.Home) { _tab.value = Tab.Home; true } else false
        _stack.value = _stack.value.dropLast(1)
        return true
    }

    fun track(uid: String): SharedTrack? = store.tracks.value.firstOrNull { it.uid == uid }

    fun toggleFavorite(uid: String) = viewModelScope.launch { store.toggleFavorite(uid) }

    /** 편집 저장 — 문제가 있으면 문구를 돌려준다(저장하지 않음) */
    fun saveEdit(uid: String, title: String, description: String, memo: String, tags: List<String>): String? {
        ai.syne.core.share.EditRules.problem(title, description, memo)?.let { return it }
        viewModelScope.launch {
            store.saveEdit(uid, title, description, memo, tags)
            _toast.value = "앨범 정보를 저장했어요"
        }
        back()
        return null
    }

    companion object { const val FAVORITES = "fav" }

    fun import(uri: Uri) = viewModelScope.launch {
        store.import(uri)
            .onSuccess { n -> _toast.value = if (n > 0) "새 앨범 ${n}곡을 가져왔어요" else "이미 있는 앨범을 최신으로 맞췄어요" }
            .onFailure { _toast.value = "가져오지 못했어요 · ${it.message}" }
    }

    init { viewModelScope.launch { store.load() } }

    override fun onCleared() { player.release() }
}

class MainActivity : ComponentActivity() {
    private val vm: MobileViewModel by viewModels()

    private val pick = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SyneDesign(SyneType.Mobile, SyneDims.Mobile) {
                MobileRoot(vm, onImport = { pick.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, onBackExit = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** 파일 앱에서 .syne를 열거나 다른 앱이 공유했을 때 바로 가져오기 */
    private fun handle(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        uri?.let(vm::import)
    }
}
