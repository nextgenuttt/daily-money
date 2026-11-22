package com.dailymoney.core.data.auth

import com.dailymoney.core.network.AuthTokenProvider
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseTokenProvider @Inject constructor(private val auth: FirebaseAuth) : AuthTokenProvider {

    override fun idToken(forceRefresh: Boolean): String? {
        val user = auth.currentUser ?: return null
        return runCatching {
            Tasks.await(user.getIdToken(forceRefresh), 20, TimeUnit.SECONDS).token
        }.getOrNull()
    }
}
