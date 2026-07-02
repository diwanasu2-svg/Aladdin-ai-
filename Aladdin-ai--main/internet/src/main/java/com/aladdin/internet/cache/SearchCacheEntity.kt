package com.aladdin.internet.cache

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.aladdin.internet.model.SearchResponse
import com.google.gson.Gson

@Entity(tableName = "search_cache")
@TypeConverters(SearchCacheConverters::class)
data class SearchCacheEntity(
    @PrimaryKey
    val queryKey: String,
    val query: String,
    val searchType: String,
    val responseJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + DEFAULT_TTL_MS
) {
    companion object {
        const val DEFAULT_TTL_MS: Long = 6 * 60 * 60 * 1000L // 6 hours
    }
}

class SearchCacheConverters {
    private val gson = Gson()

    @TypeConverter
    fun fromSearchResponse(response: SearchResponse): String = gson.toJson(response)

    @TypeConverter
    fun toSearchResponse(json: String): SearchResponse = gson.fromJson(json, SearchResponse::class.java)
}
