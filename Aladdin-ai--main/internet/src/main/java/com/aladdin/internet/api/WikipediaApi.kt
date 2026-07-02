package com.aladdin.internet.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface WikipediaApi {
    /**
     * Wikipedia REST API — no key required.
     * https://www.mediawiki.org/wiki/API:Main_page
     */
    @GET("w/api.php")
    suspend fun search(
        @Query("action")  action:  String = "query",
        @Query("list")    list:    String = "search",
        @Query("srsearch") query:  String,
        @Query("srlimit") limit:   Int    = 5,
        @Query("format")  format:  String = "json",
        @Query("origin")  origin:  String = "*"
    ): WikipediaSearchResponse

    @GET("w/api.php")
    suspend fun getArticle(
        @Query("action")      action:   String = "query",
        @Query("prop")        prop:     String = "extracts|info|pageimages",
        @Query("exintro")     exIntro:  Boolean = true,
        @Query("explaintext") plain:    Boolean = true,
        @Query("titles")      title:    String,
        @Query("format")      format:   String = "json",
        @Query("origin")      origin:   String = "*",
        @Query("inprop")      inProp:   String = "url",
        @Query("pithumbsize") thumbSize: Int   = 500
    ): WikipediaArticleResponse
}

data class WikipediaSearchResponse(
    @SerializedName("query") val query: WikipediaQueryResult?
)

data class WikipediaQueryResult(
    @SerializedName("search") val search: List<WikipediaSearchHit>?
)

data class WikipediaSearchHit(
    @SerializedName("pageid")  val pageId:  Int,
    @SerializedName("title")   val title:   String,
    @SerializedName("snippet") val snippet: String,
    @SerializedName("wordcount") val wordCount: Int = 0
)

data class WikipediaArticleResponse(
    @SerializedName("query") val query: WikipediaArticleQuery?
)

data class WikipediaArticleQuery(
    @SerializedName("pages") val pages: Map<String, WikipediaPage>?
)

data class WikipediaPage(
    @SerializedName("pageid")    val pageId:   Int,
    @SerializedName("title")     val title:    String,
    @SerializedName("extract")   val extract:  String?,
    @SerializedName("fullurl")   val fullUrl:  String?,
    @SerializedName("thumbnail") val thumbnail: WikipediaThumbnail?
)

data class WikipediaThumbnail(
    @SerializedName("source") val source: String?,
    @SerializedName("width")  val width:  Int = 0,
    @SerializedName("height") val height: Int = 0
)
