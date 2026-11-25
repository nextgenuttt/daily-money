package com.dailymoney.core.data.sync

import com.dailymoney.core.domain.auth.BackendSession
import com.dailymoney.core.domain.sharing.SyncScheduler
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Receives the server's data-only `{type: SYNC}` poke; the content itself is always pulled over HTTPS. */
class SyncMessagingService : FirebaseMessagingService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun syncScheduler(): SyncScheduler
        fun backendSession(): BackendSession
        fun auth(): FirebaseAuth
    }

    private val deps by lazy { EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java) }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "SYNC" || deps.auth().currentUser == null) return
        deps.syncScheduler().requestSync()
    }

    /** Re-register so pokes keep reaching this phone after FCM rotates its token. */
    override fun onNewToken(token: String) {
        if (deps.auth().currentUser == null) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { deps.backendSession().bootstrap() }
    }
}
