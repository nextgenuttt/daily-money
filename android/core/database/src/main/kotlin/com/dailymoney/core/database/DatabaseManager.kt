package com.dailymoney.core.database

import android.content.Context
import androidx.room.Room
import com.dailymoney.core.security.DeviceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Owns the SQLCipher-encrypted Room database. It is opened lazily (first DAO use, off the main
 * thread) and can be closed and deleted at sign-out, then re-created for the next user.
 */
@Singleton
class DatabaseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdentity: DeviceIdentity,
) {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(): AppDatabase = instance ?: synchronized(this) {
        instance ?: open().also { instance = it }
    }

    @Synchronized
    fun closeAndDelete() {
        instance?.close()
        instance = null
        context.deleteDatabase(DB_NAME)
    }

    private fun open(): AppDatabase {
        System.loadLibrary("sqlcipher")
        val factory = SupportOpenHelperFactory(deviceIdentity.databasePassphrase())
        return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .openHelperFactory(factory)
            .build()
    }

    private companion object {
        const val DB_NAME = "dailymoney.db"
    }
}
