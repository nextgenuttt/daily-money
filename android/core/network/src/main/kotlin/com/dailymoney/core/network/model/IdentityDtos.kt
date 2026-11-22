package com.dailymoney.core.network.model

import kotlinx.serialization.Serializable

@Serializable
data class BootstrapRequestDto(
    val deviceId: String,
    val deviceName: String? = null,
    val appVersion: String? = null,
    val fcmToken: String? = null,
    val displayName: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class ProfileDto(
    val id: String,
    val email: String? = null,
    val emailVerified: Boolean = false,
    val displayName: String,
    val authProviders: List<String> = emptyList(),
    val defaultCurrency: String,
    val timeZone: String,
)
