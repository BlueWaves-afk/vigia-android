package com.example.vigia.search

import com.google.gson.annotations.SerializedName

data class AzureSearchResponse(
    @SerializedName("results") val results: List<AzureSearchResult> = emptyList()
)

data class AzureSearchResult(
    @SerializedName("position") val position: AzureLatLon,
    @SerializedName("address") val address: AzureAddress,
    @SerializedName("poi") val poi: AzurePoi? = null
)

data class AzureLatLon(
    @SerializedName("lat") val lat: Double,
    @SerializedName("lon") val lon: Double
)

data class AzureAddress(
    @SerializedName("freeformAddress") val freeformAddress: String = ""
)

data class AzurePoi(
    @SerializedName("name") val name: String? = null
)