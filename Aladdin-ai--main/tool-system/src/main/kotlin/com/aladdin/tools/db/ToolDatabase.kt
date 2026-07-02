package com.aladdin.tools.db

import android.content.Context
import androidx.room.*
import com.aladdin.tools.db.dao.*
import com.aladdin.tools.db.entity.*

@Database(
    entities = [
        NoteEntity::class,
        TodoEntity::class,
        AlarmEntity::class,
        ClipboardEntry::class,
        TimerEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(ToolTypeConverters::class)
abstract class ToolDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao
    abstract fun todoDao(): TodoDao
    abstract fun alarmDao(): AlarmDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun timerDao(): TimerDao

    companion object {
        @Volatile private var INSTANCE: ToolDatabase? = null

        fun getInstance(context: Context): ToolDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ToolDatabase::class.java,
                    "aladdin_tools.db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
