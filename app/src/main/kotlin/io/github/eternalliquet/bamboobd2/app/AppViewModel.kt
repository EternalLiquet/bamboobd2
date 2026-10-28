package io.github.eternalliquet.bamboobd2.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.eternalliquet.bamboobd2.core.reader.FakeReader
import io.github.eternalliquet.bamboobd2.core.reader.FakeScenario
import io.github.eternalliquet.bamboobd2.core.model.SessionRecord
import io.github.eternalliquet.bamboobd2.core.session.SessionController
import io.github.eternalliquet.bamboobd2.core.session.SessionState
import io.github.eternalliquet.bamboobd2.core.storage.FileSessionStore
import io.github.eternalliquet.bamboobd2.core.storage.SaveResult
import io.github.eternalliquet.bamboobd2.core.storage.SessionStore
import io.github.eternalliquet.bamboobd2.core.storage.StoredSession
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Wiring only: connects the core controller and store to the screens. */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store: SessionStore = FileSessionStore(File(application.filesDir, "sessions"))
    private val controller = SessionController(viewModelScope)

    val session: StateFlow<SessionState> = controller.state

    private val _saved = MutableStateFlow<List<StoredSession>>(emptyList())
    val saved: StateFlow<List<StoredSession>> = _saved.asStateFlow()

    private val _lastSave = MutableStateFlow<SaveResult?>(null)
    val lastSave: StateFlow<SaveResult?> = _lastSave.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    fun start(scenario: FakeScenario) {
        _lastSave.value = null
        controller.start(FakeReader(scenario))
    }

    fun cancel() = controller.cancel()

    fun save() {
        val finished = session.value as? SessionState.Finished ?: return
        if (_saving.value || _lastSave.value is SaveResult.Saved) return
        _saving.value = true
        viewModelScope.launch {
            try {
                _lastSave.value = withContext(Dispatchers.IO) { store.save(finished.record) }
            } finally {
                _saving.value = false
            }
            refreshSaved()
        }
    }

    fun finishSession() {
        _lastSave.value = null
        controller.reset()
    }

    fun refreshSaved() {
        viewModelScope.launch { _saved.value = withContext(Dispatchers.IO) { store.list() } }
    }

    suspend fun load(id: String): SessionRecord? = withContext(Dispatchers.IO) { store.load(id) }
}
