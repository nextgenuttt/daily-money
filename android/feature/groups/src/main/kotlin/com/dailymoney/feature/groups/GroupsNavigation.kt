package com.dailymoney.feature.groups

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

@Serializable data object GroupsRoute
@Serializable data class GroupDetailRoute(val groupId: String)
@Serializable data class GroupExpensesRoute(val groupId: String)
@Serializable data class ApprovalPolicyRoute(val groupId: String)
@Serializable data class ExpenseMessagesRoute(val expenseId: String, val title: String = "")

/** [openId]: opened from a system notification; jump to what it's about. */
@Serializable data class NotificationsRoute(val openId: String? = null)

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
            onExpenses = { id -> navController.navigate(GroupExpensesRoute(id)) },
            onMemberPermissions = { id, userId, name -> navController.navigate(MemberPermissionsRoute(id, userId, name)) },
        )
    }
    composable<GroupExpensesRoute> {
        GroupExpensesScreen(
            onBack = { navController.popBackStack() },
            onApprovalRules = { navController.navigate(ApprovalPolicyRoute(it)) },
            onMessages = { id, title -> navController.navigate(ExpenseMessagesRoute(id, title)) },
        )
    }
    composable<ExpenseMessagesRoute> { entry ->
        ExpenseMessagesScreen(onBack = { navController.popBackStack() }, title = entry.toRoute<ExpenseMessagesRoute>().title)
    }
    composable<NotificationsRoute> {
        NotificationsScreen(
            onBack = { navController.popBackStack() },
            onOpen = { target ->
                when (target) {
                    is NotificationTarget.Group -> navController.navigate(GroupExpensesRoute(target.groupId))
                    is NotificationTarget.Messages -> navController.navigate(ExpenseMessagesRoute(target.expenseId, target.title))
                }
            },
        )
    }
    composable<ApprovalPolicyRoute> { ApprovalPolicyScreen(onBack = { navController.popBackStack() }) }
    composable<RolePermissionsRoute> { RolePermissionsScreen(onBack = { navController.popBackStack() }) }
    composable<MemberPermissionsRoute> { MemberPermissionsScreen(onBack = { navController.popBackStack() }) }
    composable<JoinGroupRoute> {
        JoinGroupScreen(
            onBack = { navController.popBackStack() },
            onJoined = { id -> navController.navigate(GroupDetailRoute(id)) { popUpTo(GroupsRoute) } },
        )
    }
}
