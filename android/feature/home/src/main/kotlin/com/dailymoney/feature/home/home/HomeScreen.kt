package com.dailymoney.feature.home.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.dailymoney.core.designsystem.component.EmptyState
import com.dailymoney.core.designsystem.theme.Spacing
import com.dailymoney.core.domain.auth.AuthUser
import com.dailymoney.feature.home.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(user: AuthUser, onOpenSettings: () -> Unit) {
    val firstName = user.displayName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        firstName?.let { stringResource(R.string.home_greeting, it) }
                            ?: stringResource(R.string.home_greeting_no_name),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.screenGutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                EmptyState(
                    icon = Icons.Filled.Insights,
                    title = stringResource(R.string.home_empty_title),
                    message = stringResource(R.string.home_empty_body),
                )
            }
        }
    }
}
