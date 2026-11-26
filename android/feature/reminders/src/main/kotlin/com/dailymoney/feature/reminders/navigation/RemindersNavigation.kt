package com.dailymoney.feature.reminders.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.dailymoney.feature.reminders.calendar.CalendarScreen
import com.dailymoney.feature.reminders.detail.ReminderDetailScreen
import com.dailymoney.feature.reminders.edit.AddEditReminderScreen
import com.dailymoney.feature.reminders.list.RemindersScreen
import kotlinx.serialization.Serializable

@Serializable data object RemindersRoute
@Serializable data object CalendarRoute
@Serializable data class AddEditReminderRoute(val reminderId: String? = null, val epochDay: Long? = null)

/** [epochDay] picks one occurrence (e.g. from a notification); [complete] opens the "Mark paid" sheet. */
@Serializable data class ReminderDetailRoute(val reminderId: String, val epochDay: Long? = null, val complete: Boolean = false)

/** [onTodo] / [onOpenTask] lead into the To-Do feature; the app wires them. */
fun NavGraphBuilder.remindersGraph(navController: NavController, onTodo: () -> Unit, onOpenTask: (String) -> Unit) {
    val back: () -> Unit = { navController.popBackStack() }
    composable<RemindersRoute> {
        RemindersScreen(
            onAdd = { navController.navigate(AddEditReminderRoute()) },
            onCalendar = { navController.navigate(CalendarRoute) },
            onOpen = { o, complete -> navController.navigate(ReminderDetailRoute(o.reminder.id, o.originalDate.toEpochDay(), complete)) },
            onTodo = onTodo,
            onEdit = { id -> navController.navigate(AddEditReminderRoute(reminderId = id)) },
        )
    }
    composable<CalendarRoute> {
        CalendarScreen(
            onBack = back,
            onAdd = { date -> navController.navigate(AddEditReminderRoute(epochDay = date.toEpochDay())) },
            onOpen = { o -> navController.navigate(ReminderDetailRoute(o.reminder.id, o.originalDate.toEpochDay())) },
            onOpenTask = onOpenTask,
        )
    }
    composable<AddEditReminderRoute> { AddEditReminderScreen(onClose = back) }
    composable<ReminderDetailRoute> {
        ReminderDetailScreen(onBack = back, onEdit = { id -> navController.navigate(AddEditReminderRoute(reminderId = id)) })
    }
}
