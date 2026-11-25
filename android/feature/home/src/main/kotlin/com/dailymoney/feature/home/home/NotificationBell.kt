package com.dailymoney.feature.home.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dailymoney.core.domain.config.AppConfig
import com.dailymoney.core.domain.sharing.NotificationRepository
import com.dailymoney.feature.home.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class NotificationBellViewModel @Inject constructor(repository: NotificationRepository, config: AppConfig) : ViewModel() {
    val enabled = config.backendEnabled
    val unread = repository.observeUnreadCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

/** Groups need the server, so without one there is nothing to show. */
@Composable
internal fun NotificationBell(onOpen: () -> Unit, viewModel: NotificationBellViewModel = hiltViewModel()) {
    if (!viewModel.enabled) return
    val unread by viewModel.unread.collectAsStateWithLifecycle()
    IconButton(onClick = onOpen) {
        BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
            Icon(Icons.Filled.Notifications, contentDescription = stringResource(R.string.home_notifications))
        }
    }
}
