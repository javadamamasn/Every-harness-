package com.harnesshub.app.ui.screens.sessions

import android.app.Application
import com.harnesshub.app.chat.ChatViewModel
import com.harnesshub.app.chat.SessionOpen
import com.harnesshub.app.chat.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Presentation state + actions for the Sessions directory. */
class SessionsViewModel(application: Application) {

    private val store = SessionStore(application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val records: Flow<List<SessionStore.SessionRecord>> = store.records()

    fun open(engine: String, sessionId: String, title: String) {
        SessionOpen.engine = when (engine) {
            SessionStore.ENGINE_CLAUDE -> ChatViewModel.Engine.CLAUDE
            SessionStore.ENGINE_OPENCODE -> ChatViewModel.Engine.OPENCODE
            else -> ChatViewModel.Engine.HERMES
        }
        SessionOpen.sessionId = sessionId
    }

    fun delete(engine: String, sessionId: String) {
        scope.launch {
            store.delete(engine, sessionId)
        }
    }
}
