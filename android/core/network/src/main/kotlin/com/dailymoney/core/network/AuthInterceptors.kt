package com.dailymoney.core.network

import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

private const val AUTHORIZATION = "Authorization"

class FirebaseTokenInterceptor(private val tokens: AuthTokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokens.idToken(forceRefresh = false)
            ?: return chain.proceed(chain.request())
        return chain.proceed(chain.request().withBearer(token))
    }
}

/**
 * On a 401, forces one token refresh and retries once. Parallel 401s share a single refresh:
 * if another thread already got a newer token, it is reused instead of refreshing again.
 */
class TokenRefreshAuthenticator(private val tokens: AuthTokenProvider) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null
        val failedToken = response.request.header(AUTHORIZATION)?.removePrefix("Bearer ")
        synchronized(this) {
            val current = tokens.idToken(forceRefresh = false) ?: return null
            if (current != failedToken) return response.request.withBearer(current)
            val refreshed = tokens.idToken(forceRefresh = true) ?: return null
            if (refreshed == failedToken) return null
            return response.request.withBearer(refreshed)
        }
    }
}

private fun Request.withBearer(token: String): Request =
    newBuilder().header(AUTHORIZATION, "Bearer $token").build()
