package com.aladdin.internet.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private fun buildClient(vararg headers: Pair<String, String>): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)

        if (headers.isNotEmpty()) {
            builder.addInterceptor { chain ->
                val request = chain.request().newBuilder().apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }.build()
                chain.proceed(request)
            }
        }
        return builder.build()
    }

    private inline fun <reified T> create(baseUrl: String, vararg headers: Pair<String, String>): T {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(buildClient(*headers))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(T::class.java)
    }

    fun duckDuckGoApi(): DuckDuckGoApi =
        create("https://api.duckduckgo.com/")

    fun wikipediaApi(): WikipediaApi =
        create("https://en.wikipedia.org/")

    fun newsApi(apiKey: String): NewsApi =
        create("https://newsapi.org/v2/", "X-Api-Key" to apiKey)

    fun braveSearchApi(apiKey: String): BraveSearchApi =
        create("https://api.search.brave.com/res/v1/", "X-Subscription-Token" to apiKey)

    fun serperApi(apiKey: String): SerperApi =
        create("https://google.serper.dev/", "X-API-KEY" to apiKey)
}
