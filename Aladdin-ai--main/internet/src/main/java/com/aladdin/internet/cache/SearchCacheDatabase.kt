package com.aladdin.internet.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [SearchCacheEntity::class], version = 1, exportSchema = false)
@TypeConverters(SearchCacheConverters::class)
abstract class SearchCacheDatabase : RoomDatabase() {

    abstract fun searchCacheDao(): SearchCacheDao

    companion object {
        @Volatile private var INSTANCE: SearchCacheDatabase? = null

        fun getInstance(context: Context): SearchCacheDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SearchCacheDatabase::class.java,
                    "aladdin_search_cache.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
