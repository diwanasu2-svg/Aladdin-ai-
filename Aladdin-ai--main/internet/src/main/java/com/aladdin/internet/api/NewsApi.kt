package com.aladdin.internet.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface NewsApi {
    /**
     * NewsAPI.org — requires free API key from newsapi.org
     * https://newsapi.org/docs
     */
    @GET("everything")
    suspend fun search(
        @Query("q")        query:    String,
        @Query("pageSize") pageSize: Int    = 10,
        @Query("sortBy")   sortBy:   String = "relevancy",
        @Query("language") language: String = "en"
    ): NewsResponse

    @GET("top-headlines")
    suspend fun topHeadlines(
        @Query("q")        query:    String? = null,
        @Query("category") category: String? = null,
        @Query("country")  country:  String  = "us",
        @Query("pageSize") pageSize: Int     = 10
    ): NewsResponse
}

data class NewsResponse(
    @SerializedName("status")       val status:       String,
    @SerializedName("totalResults") val totalResults: Int,
    @SerializedName("articles")     val articles:     List<NewsArticle>
)

data class NewsArticle(
    @SerializedName("source")      val source:      NewsSource?,
    @SerializedName("author")      val author:      String?,
    @SerializedName("title")       val title:       String,
    @SerializedName("description") val description: String?,
    @SerializedName("url")         val url:         String,
    @SerializedName("urlToImage")  val urlToImage:  String?,
    @SerializedName("publishedAt") val publishedAt: String?,
    @SerializedName("content")     val content:     String?
)

data class NewsSource(
    @SerializedName("id")   val id:   String?,
    @SerializedName("name") val name: String
)
