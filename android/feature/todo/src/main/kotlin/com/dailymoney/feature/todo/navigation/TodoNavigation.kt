package com.dailymoney.feature.todo.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dailymoney.feature.todo.detail.TodoDetailScreen
import com.dailymoney.feature.todo.edit.AddEditTodoScreen
import com.dailymoney.feature.todo.list.TodoListScreen
import kotlinx.serialization.Serializable

@Serializable data object TodoRoute
@Serializable data class AddEditTodoRoute(val todoId: String? = null, val parentId: String? = null, val epochDay: Long? = null)
@Serializable data class TodoDetailRoute(val todoId: String)

/** [onReminders] switches the Planner tab back to the reminders list (wired by the app). */
fun NavGraphBuilder.todoGraph(navController: NavController, onReminders: () -> Unit) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<TodoRoute> {
        TodoListScreen(
            onReminders = onReminders,
            onAdd = { navController.navigate(AddEditTodoRoute()) },
            onOpen = { navController.navigate(TodoDetailRoute(it)) },
            onEdit = { navController.navigate(AddEditTodoRoute(todoId = it)) },
        )
    }
    composable<AddEditTodoRoute> { AddEditTodoScreen(onClose = back) }
    composable<TodoDetailRoute> {
        TodoDetailScreen(
            onBack = back,
            onEdit = { navController.navigate(AddEditTodoRoute(todoId = it)) },
            onOpen = { navController.navigate(TodoDetailRoute(it)) },
        )
    }
}
