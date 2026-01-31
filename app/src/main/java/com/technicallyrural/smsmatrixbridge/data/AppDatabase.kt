package com.technicallyrural.smsmatrixbridge.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao

    companion object {
        private const val DATABASE_NAME = "sms_matrix_bridge.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}
