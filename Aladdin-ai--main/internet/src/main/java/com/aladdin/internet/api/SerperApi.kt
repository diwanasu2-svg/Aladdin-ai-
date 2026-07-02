package com.aladdin.internet.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

interface SerperApi {
    /**
     * Serper.dev — Google Search API wrapper, requires API key from serper.dev
     * https://serper.dev
     */
    @POST("search")
    suspend fun search(@Body request: SerperRequest): SerperResponse

    @POST("images")
    suspend fun imageSearch(@Body request: SerperRequest): SerperImageResponse

    @POST("news")
    suspend fun newsSearch(@Body request: SerperRequest): SerperNewsResponse
}

data class SerperRequest(
    @SerializedName("q")    val query:    String,
    @SerializedName("num")  val num:      Int = 10,
    @SerializedName("gl")   val country:  String = "us",
    @SerializedName("hl")   val language: String = "en"
)

data class SerperResponse(
    @SerializedName("organic")        val organic:        List<SerperResult>?,
    @SerializedName("answerBox")      val answerBox:      SerperAnswerBox?,
    @SerializedName("knowledgeGraph") val knowledgeGraph: SerperKnowledgeGraph?,
    @SerializedName("topStories")     val topStories:     List<SerperStory>?,
    @SerializedName("relatedSearches") val relatedSearches: List<SerperRelated>?
)

data class SerperResult(
    @SerializedName("title")    val title:    String,
    @SerializedName("link")     val link:     String,
    @SerializedName("snippet")  val snippet:  String?,
    @SerializedName("position") val position: Int = 0,
    @SerializedName("date")     val date:     String?
)

data class SerperAnswerBox(
    @SerializedName("title")   val title:   String?,
    @SerializedName("answer")  val answer:  String?,
    @SerializedName("snippet") val snippet: String?,
    @SerializedName("link")    val link:    String?
)

data class SerperKnowledgeGraph(
    @SerializedName("title")       val title:       String?,
    @SerializedName("type")        val type:        String?,
    @SerializedName("description") val description: String?,
    @SerializedName("website")     val website:     String?
)

data class SerperStory(
    @SerializedName("title") val title: String,
    @SerializedName("link")  val link:  String,
    @SerializedName("source") val source: String?,
    @SerializedName("date") val date: String?
)

data class SerperRelated(
    @SerializedName("query") val query: String
)

data class SerperImageResponse(
    @SerializedName("images") val images: List<SerperImage>?
)

data class SerperImage(
    @SerializedName("title")    val title:    String,
    @SerializedName("imageUrl") val imageUrl: String,
    @SerializedName("link")     val link:     String
)

data class SerperNewsResponse(
    @SerializedName("news") val news: List<SerperNewsItem>?
)

data class SerperNewsItem(
    @SerializedName("title")       val title:       String,
    @SerializedName("link")        val link:        String,
    @SerializedName("snippet")     val snippet:     String?,
    @SerializedName("source")      val source:      String?,
    @SerializedName("imageUrl")    val imageUrl:    String?,
    @SerializedName("date")        val date:        String?
)
