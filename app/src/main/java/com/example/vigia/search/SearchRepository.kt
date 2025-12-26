package com.example.vigia.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.vigia.BuildConfig
// Pass Context once in the constructor to use for connectivity checks
class SearchRepository(private val context: Context, private val placeDao: PlaceDao) {

    private val azureApi: AzureSearchApi
    // Your Azure Maps Key (Verified from your previous files)
    private val azureKey = BuildConfig.AZURE_MAPS_KEY

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://atlas.microsoft.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        azureApi = retrofit.create(AzureSearchApi::class.java)
    }

    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // The HYBRID Function: Combines Offline Safety Data with Online Azure Maps Results
    suspend fun search(query: String, currentLat: Double, currentLon: Double): List<PlaceEntity> {
        val results = mutableListOf<PlaceEntity>()

        // 1. ALWAYS query Local DB first (The "Fast Cache")
        try {
            val localResults = placeDao.searchPlaces(query)
            results.addAll(localResults)
            Log.d("VigiaSearch", "Found ${localResults.size} local offline results")
        } catch (e: Exception) {
            Log.e("VigiaSearch", "Local DB search error: ${e.message}")
        }

        // 2. If Online, query Azure Maps and CACHE the results
        if (isOnline()) {
            try {
                val response = azureApi.search(
                    key = azureKey,
                    query = query,
                    lat = currentLat,
                    lon = currentLon
                )

                val azureResults = response.results.map {
                    // --- IMPROVEMENT: POI NAME PRIORITY ---
                    // If 'poi.name' exists (e.g., "Starbucks"), use it.
                    // Otherwise, fall back to the address (e.g., "123 Main St").
                    val displayName = it.poi?.name ?: it.address.freeformAddress.substringBefore(",")

                    PlaceEntity(
                        name = displayName,
                        address = it.address.freeformAddress,
                        lat = it.position.lat,
                        lon = it.position.lon,
                        type = "online_cached",
                        isCritical = false
                    )
                }

                // 3. Cache to Local DB immediately
                if (azureResults.isNotEmpty()) {
                    try {
                        placeDao.insertAll(azureResults)
                        Log.d("VigiaSearch", "Cached ${azureResults.size} places to DB")
                    } catch (e: Exception) {
                        Log.e("VigiaSearch", "Cache failed: ${e.message}")
                    }
                }

                results.addAll(azureResults)

            } catch (e: Exception) {
                Log.e("VigiaSearch", "Online search failed: ${e.message}")
            }
        } else {
            Log.d("VigiaSearch", "Offline Mode: Showing local results only")
        }

        // --- IMPROVEMENT: DEDUPLICATION ---
        // distinctBy ensures we don't show the same place twice (once from Local, once from Online)
        return results.distinctBy { it.name + it.address }
    }
}