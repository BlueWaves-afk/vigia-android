package com.example.vigia.search

import retrofit2.http.GET
import retrofit2.http.Query

interface AzureSearchApi {
    @GET("search/fuzzy/json")
    suspend fun search(
        @Query("api-version") apiVersion: String = "1.0",
        @Query("subscription-key") key: String,
        @Query("query") query: String,
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("limit") limit: Int = 5
    ): AzureSearchResponse
}