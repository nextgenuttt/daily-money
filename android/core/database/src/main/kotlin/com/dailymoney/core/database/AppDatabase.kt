package com.dailymoney.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dailymoney.core.database.dao.PendingOpDao
import com.dailymoney.core.database.entity.PendingOpEntity

@Database(
    entities = [PendingOpEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingOpDao(): PendingOpDao
}
