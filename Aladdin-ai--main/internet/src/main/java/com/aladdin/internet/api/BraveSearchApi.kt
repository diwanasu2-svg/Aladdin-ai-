package com.aladdin.internet.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface BraveSearchApi {
    /**
     * Brave Search API — requires API key from api.search.brave.com
     * https://api.search.brave.com/app/documentation/web-search/get-started
     */
    @GET("web/search")
    suspend fun webSearch(
        @Query("q")      query:  String,
        @Query("count")  count:  Int    = 10,
        @Query("offset") offset: Int    = 0,
        @Query("safesearch") safe: String = "moderate"
    ): BraveSearchResponse

    @GET("images/search")
    suspend fun imageSearch(
        @Query("q")     query: String,
        @Query("count") count: Int    = 10
    ): BraveImageResponse
}

data class BraveSearchResponse(
    @SerializedName("type")  val type:  String?,
    @SerializedName("web")   val web:   BraveWebResults?,
    @SerializedName("news")  val news:  BraveNewsResults?
)

data class BraveWebResults(
    @SerializedName("results") val results: List<BraveWebResult>
)

data class BraveWebResult(
    @SerializedName("title")       val title:       String,
    @SerializedName("url")         val url:         String,
    @SerializedName("description") val description: String?,
    @SerializedName("age")         val age:         String?,
    @SerializedName("page_age")    val pageAge:     String?,
    @SerializedName("language")    val language:    String?
)

data class BraveNewsResults(
    @SerializedName("results") val results: List<BraveNewsResult>
)

data class BraveNewsResult(
    @SerializedName("title")       val title:       String,
    @SerializedName("url")         val url:         String,
    @SerializedName("description") val description: String?,
    @SerializedName("age")         val age:         String?,
    @SerializedName("thumbnail")   val thumbnail:   BraveThumbnail?
)

data class BraveThumbnail(
    @SerializedName("src") val src: String?
)

data class BraveImageResponse(
    @SerializedName("results") val results: List<BraveImageResult>?
)

data class BraveImageResult(
    @SerializedName("title")        val title:       String,
    @SerializedName("url")          val url:         String,
    @SerializedName("thumbnail")    val thumbnail:   BraveThumbnail?,
    @SerializedName("source")       val source:      String?
)
