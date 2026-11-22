package com.dailymoney.core.domain.config

/** Build-time settings from `local.properties`, provided by the app module. */
data class AppConfig(
    val applicationId: String,
    val appVersion: String,
    val googleWebClientId: String,
    val authContinueUrl: String,
    val apiBaseUrl: String,
) {
    /** R1 runs without a backend: every server call is skipped when no API URL is configured. */
    val backendEnabled: Boolean get() = apiBaseUrl.isNotBlank()
    val googleSignInConfigured: Boolean get() = googleWebClientId.isNotBlank()
}
