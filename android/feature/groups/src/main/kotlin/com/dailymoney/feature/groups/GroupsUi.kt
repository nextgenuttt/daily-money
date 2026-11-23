package com.dailymoney.feature.groups

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Diversity3
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.dailymoney.core.domain.groups.GroupError
import com.dailymoney.core.domain.groups.GroupKind
import com.dailymoney.core.domain.groups.GroupRole

fun GroupKind.icon(): ImageVector = when (this) {
    GroupKind.FAMILY -> Icons.Filled.FamilyRestroom
    GroupKind.FRIENDS -> Icons.Filled.Diversity3
    GroupKind.OFFICE -> Icons.Filled.BusinessCenter
    GroupKind.TRAVEL -> Icons.Filled.Flight
    GroupKind.OTHER -> Icons.Filled.Groups
}

@Composable
fun GroupKind.label(): String = stringResource(
    when (this) {
        GroupKind.FAMILY -> R.string.kind_family
        GroupKind.FRIENDS -> R.string.kind_friends
        GroupKind.OFFICE -> R.string.kind_office
        GroupKind.TRAVEL -> R.string.kind_travel
        GroupKind.OTHER -> R.string.kind_other
    },
)

@Composable
fun GroupRole.label(): String = stringResource(
    when (this) {
        GroupRole.OWNER -> R.string.role_owner
        GroupRole.ADMIN -> R.string.role_admin
        GroupRole.MEMBER -> R.string.role_member
        GroupRole.VIEWER -> R.string.role_viewer
    },
)

@Composable
fun GroupRole.description(): String = stringResource(
    when (this) {
        GroupRole.ADMIN -> R.string.role_admin_desc
        GroupRole.VIEWER -> R.string.role_viewer_desc
        else -> R.string.role_member_desc
    },
)

@Composable
fun GroupError.message(): String = stringResource(
    when (this) {
        GroupError.Offline -> R.string.groups_offline
        GroupError.NotFound -> R.string.err_not_found
        GroupError.PermissionDenied -> R.string.err_permission
        GroupError.SessionExpired -> R.string.err_session
        GroupError.BackendDisabled -> R.string.groups_backend_off_body
        is GroupError.Rule -> when (code) {
            "TRANSFER_OWNERSHIP_FIRST" -> R.string.err_transfer_first
            "OWNER_ONLY" -> R.string.err_owner_only
            "CANNOT_MODIFY_OWNER" -> R.string.err_modify_owner
            "INVITE_EMAIL_MISMATCH" -> R.string.err_email_mismatch
            "ALREADY_MEMBER" -> R.string.err_already_member
            "INVITE_EXPIRED" -> R.string.join_status_expired
            "INVITE_USED_UP" -> R.string.join_status_used
            "INVITE_REVOKED" -> R.string.join_status_revoked
            "LIMIT_REACHED" -> R.string.err_limit
            else -> R.string.err_generic
        }
        is GroupError.Unknown -> R.string.err_generic
    },
)

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.groups_back)) }
}
