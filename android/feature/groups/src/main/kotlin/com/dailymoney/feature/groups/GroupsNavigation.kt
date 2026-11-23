package com.dailymoney.feature.groups

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.serialization.Serializable

@Serializable data object GroupsRoute
@Serializable data class GroupDetailRoute(val groupId: String)

/** [token] comes from an invite link (App Link); without it the user pastes the link. */
@Serializable data class JoinGroupRoute(val token: String? = null)
@Serializable data class RolePermissionsRoute(val groupId: String)
@Serializable data class MemberPermissionsRoute(val groupId: String, val userId: String, val name: String)

fun NavGraphBuilder.groupsGraph(navController: NavController) {
    composable<GroupsRoute> {
        GroupsScreen(
            onOpen = { navController.navigate(GroupDetailRoute(it)) },
            onJoin = { navController.navigate(JoinGroupRoute()) },
        )
    }
    composable<GroupDetailRoute> {
        GroupDetailScreen(
            onBack = { navController.popBackStack() },
            onRoles = { id -> navController.navigate(RolePermissionsRoute(id)) },
            onMemberPermissions = { id, userId, name -> navController.navigate(MemberPermissionsRoute(id, userId, name)) },
        )
    }
    composable<RolePermissionsRoute> { RolePermissionsScreen(onBack = { navController.popBackStack() }) }
    composable<MemberPermissionsRoute> { MemberPermissionsScreen(onBack = { navController.popBackStack() }) }
    composable<JoinGroupRoute> {
        JoinGroupScreen(
            onBack = { navController.popBackStack() },
            onJoined = { id -> navController.navigate(GroupDetailRoute(id)) { popUpTo(GroupsRoute) } },
        )
    }
}
