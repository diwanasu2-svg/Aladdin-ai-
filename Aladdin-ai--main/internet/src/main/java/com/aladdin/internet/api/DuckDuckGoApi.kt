package com.aladdin.internet.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface DuckDuckGoApi {
    /**
     * DuckDuckGo Instant Answer API — free, no key required.
     * https://duckduckgo.com/api
     */
    @GET(".")
    suspend fun instantAnswer(
        @Query("q") query: String,
        @Query("format") format: String = "json",
        @Query("no_html") noHtml: Int = 1,
        @Query("skip_disambig") skipDisambig: Int = 1
    ): DuckDuckGoResponse
}

data class DuckDuckGoResponse(
    @SerializedName("AbstractText") val abstractText: String?,
    @SerializedName("AbstractURL")  val abstractUrl: String?,
    @SerializedName("AbstractSource") val abstractSource: String?,
    @SerializedName("Heading")       val heading: String?,
    @SerializedName("Answer")        val answer: String?,
    @SerializedName("AnswerType")    val answerType: String?,
    @SerializedName("Definition")    val definition: String?,
    @SerializedName("DefinitionURL") val definitionUrl: String?,
    @SerializedName("Image")         val image: String?,
    @SerializedName("RelatedTopics") val relatedTopics: List<DuckDuckGoTopic>?,
    @SerializedName("Results")       val results: List<DuckDuckGoResult>?
)

data class DuckDuckGoTopic(
    @SerializedName("Text") val text: String?,
    @SerializedName("FirstURL") val firstUrl: String?,
    @SerializedName("Icon") val icon: DuckDuckGoIcon?
)

data class DuckDuckGoResult(
    @SerializedName("Text") val text: String?,
    @SerializedName("FirstURL") val firstUrl: String?
)

data class DuckDuckGoIcon(
    @SerializedName("URL") val url: String?
)
