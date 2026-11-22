package com.dailymoney.core.network

/** Supplies the Firebase ID token for API calls (implemented in :core:data). */
interface AuthTokenProvider {
    /** Blocking: called from OkHttp's background threads. */
    fun idToken(forceRefresh: Boolean): String?
}
