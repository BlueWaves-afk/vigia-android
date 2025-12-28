package com.example.vigia.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.vigia.BuildConfig
import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import kotlin.math.*

class SearchRepository(
    private val context: Context,
    private val placeDao: PlaceDao
) {
    private val azureKey = BuildConfig.AZURE_MAPS_KEY

    /**
     * Extended API:
     * 1) Autocomplete -> better suggestions/recall for partial queries
     * 2) Fuzzy search -> richer POI results + real coords + fallback
     */
    private interface AzureSearchApiEx {
        @GET("search/autocomplete/json")
        suspend fun autocomplete(
            @Query("api-version") apiVersion: String = "1.0",
            @Query("subscription-key") key: String,
            @Query("query") query: String,
            @Query("lat") lat: Double,
            @Query("lon") lon: Double,
            @Query("limit") limit: Int = 10
        ): AzureAutocompleteResponse

        @GET("search/fuzzy/json")
        suspend fun fuzzy(
            @Query("api-version") apiVersion: String = "1.0",
            @Query("subscription-key") key: String,
            @Query("query") query: String,
            @Query("lat") lat: Double,
            @Query("lon") lon: Double,
            @Query("limit") limit: Int,
            @Query("typeahead") typeahead: Boolean? = null,
            @Query("radius") radius: Int? = null
        ): AzureSearchResponse
    }

    private val azureApi: AzureSearchApiEx

    init {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://atlas.microsoft.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        azureApi = retrofit.create(AzureSearchApiEx::class.java)
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------- Tuning knobs ----------
    private val LOCAL_MAX = 15

    private val AUTO_LIMIT = 12
    private val FUZZY_LIMIT_NEAR = 25
    private val FUZZY_LIMIT_WIDE = 30

    private val MIN_ONLINE_RESULTS_TARGET = 12

    // Bias nearby first; fallback widens the net
    private val RADIUS_NEAR_M = 25_000     // ~25km
    private val RADIUS_WIDE_M = 200_000    // ~200km

    // Avoid DB bloat: cache only top N online results
    private val CACHE_MAX = 30

    // Autocomplete “feel”: only do autocomplete for short queries (typeahead)
    private val AUTOCOMPLETE_MAX_QUERY_LEN = 12

    // ---------- Public API ----------
    suspend fun search(query: String, currentLat: Double, currentLon: Double): List<PlaceEntity> {
        val q = normalizeQuery(query)
        if (q.length < 2) return emptyList()

        val results = mutableListOf<PlaceEntity>()

        // 1) Local DB first (fast cache)
        try {
            val local = placeDao.searchPlaces(q).take(LOCAL_MAX)
            results.addAll(local)
            Log.d("VigiaSearch", "Local results=${local.size}")
        } catch (e: Exception) {
            Log.e("VigiaSearch", "Local DB search error: ${e.message}")
        }

        // 2) Online improvements: Autocomplete + Fuzzy (fallback)
        if (isOnline()) {
            val online = mutableListOf<PlaceEntity>()

            try {
                // 2A) AUTOCOMPLETE (google-like suggestions)
                if (q.length in 2..AUTOCOMPLETE_MAX_QUERY_LEN) {
                    runCatching {
                        val autoResp = azureApi.autocomplete(
                            key = azureKey,
                            query = q,
                            lat = currentLat,
                            lon = currentLon,
                            limit = AUTO_LIMIT
                        )
                        online += mapAutocomplete(autoResp, q)
                    }.onFailure { e ->
                        // If this endpoint ever fails (quota/config), we still work via fuzzy
                        Log.w("VigiaSearch", "Autocomplete failed (fallback to fuzzy): ${e.message}")
                    }
                }

                // 2B) FUZZY SEARCH (richer POI results + coords)
                val typeahead = (q.length <= 6)
                val fuzzyNear = azureApi.fuzzy(
                    key = azureKey,
                    query = q,
                    lat = currentLat,
                    lon = currentLon,
                    limit = FUZZY_LIMIT_NEAR,
                    typeahead = typeahead,
                    radius = RADIUS_NEAR_M
                )
                online += mapFuzzy(fuzzyNear, q)

                // 2C) If recall still low, widen radius
                if (online.distinctBy { dedupeKey(it) }.size < MIN_ONLINE_RESULTS_TARGET && q.length >= 3) {
                    val fuzzyWide = azureApi.fuzzy(
                        key = azureKey,
                        query = q,
                        lat = currentLat,
                        lon = currentLon,
                        limit = FUZZY_LIMIT_WIDE,
                        typeahead = typeahead,
                        radius = RADIUS_WIDE_M
                    )
                    online += mapFuzzy(fuzzyWide, q)
                }

                // 3) Cache only top deduped online items (prevents DB spam)
                val onlineDeduped = online.distinctBy { dedupeKey(it) }
                val cacheBatch = rankResults(q, currentLat, currentLon, onlineDeduped)
                    .take(CACHE_MAX)

                if (cacheBatch.isNotEmpty()) {
                    try {
                        placeDao.insertAll(cacheBatch)
                        Log.d("VigiaSearch", "Cached=${cacheBatch.size}")
                    } catch (e: Exception) {
                        Log.e("VigiaSearch", "Cache failed: ${e.message}")
                    }
                }

                results.addAll(online)
            } catch (e: Exception) {
                Log.e("VigiaSearch", "Online search failed: ${e.message}")
            }
        } else {
            Log.d("VigiaSearch", "Offline Mode: local results only")
        }

        // 4) Strong dedupe + ranking
        val deduped = results.distinctBy { dedupeKey(it) }
        return rankResults(q, currentLat, currentLon, deduped)
    }

    // ---------- Mapping ----------
    private fun mapFuzzy(resp: AzureSearchResponse, query: String): List<PlaceEntity> {
        return resp.results.mapNotNull { r ->
            val lat = r.position.lat
            val lon = r.position.lon
            val freeform = r.address.freeformAddress ?: ""
            val poiName = r.poi?.name?.takeIf { it.isNotBlank() }

            val displayName = when {
                !poiName.isNullOrBlank() -> poiName
                freeform.isNotBlank() -> freeform.substringBefore(",").ifBlank { freeform }
                else -> query
            }

            PlaceEntity(
                name = displayName,
                address = freeform,
                lat = lat,
                lon = lon,
                type = "online_cached",
                isCritical = false
            )
        }
    }

    private fun mapAutocomplete(resp: AzureAutocompleteResponse, query: String): List<PlaceEntity> {
        return resp.results.mapNotNull { r ->
            // Some autocomplete results may not carry coords; skip those.
            val pos = r.position ?: return@mapNotNull null

            val freeform = r.address?.freeformAddress ?: ""
            val poiName = r.poi?.name?.takeIf { it.isNotBlank() }

            val displayName = when {
                !poiName.isNullOrBlank() -> poiName
                freeform.isNotBlank() -> freeform.substringBefore(",").ifBlank { freeform }
                else -> query
            }

            PlaceEntity(
                name = displayName,
                address = freeform,
                lat = pos.lat,
                lon = pos.lon,
                type = "online_cached",
                isCritical = false
            )
        }
    }

    // ---------- Ranking ----------
    private fun rankResults(
        query: String,
        currentLat: Double,
        currentLon: Double,
        items: List<PlaceEntity>
    ): List<PlaceEntity> {
        val q = query.lowercase()

        return items
            .map { item ->
                val name = (item.name ?: "").lowercase()
                val addr = (item.address ?: "").lowercase()
                val distM = haversineMeters(currentLat, currentLon, item.lat, item.lon)

                var score = 0.0

                // Name relevance (strong)
                when {
                    name == q -> score += 10.0
                    name.startsWith(q) -> score += 7.0
                    name.contains(q) -> score += 4.0
                }

                // Address relevance (medium)
                when {
                    addr.startsWith(q) -> score += 3.0
                    addr.contains(q) -> score += 1.5
                }

                // Small boost if it was likely local (best-effort)
                if (item.type != "online_cached") score += 0.75

                // Distance bias (Google-like nearby preference)
                val distKm = distM / 1000.0
                val distBoost = 3.0 * exp(-distKm / 7.0)
                score += distBoost

                Ranked(item, score, distM)
            }
            .sortedWith(compareByDescending<Ranked> { it.score }.thenBy { it.distM })
            .map { it.item }
    }

    private data class Ranked(val item: PlaceEntity, val score: Double, val distM: Double)

    // ---------- Dedupe ----------
    private fun dedupeKey(p: PlaceEntity): String {
        val name = normalizeText(p.name ?: "")
        val latR = round(p.lat * 10_000.0) / 10_000.0  // ~11m
        val lonR = round(p.lon * 10_000.0) / 10_000.0
        return "$name|$latR,$lonR"
    }

    // ---------- Utils ----------
    private fun normalizeQuery(s: String): String =
        s.trim().replace(Regex("\\s+"), " ")

    private fun normalizeText(s: String): String =
        s.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    // ---------- Autocomplete models (internal; no need to change your AzureSearchModels.kt) ----------
    private data class AzureAutocompleteResponse(
        @SerializedName("results") val results: List<AzureAutocompleteResult> = emptyList()
    )

    private data class AzureAutocompleteResult(
        @SerializedName("position") val position: AzureLatLonMaybe? = null,
        @SerializedName("address") val address: AzureAddressMaybe? = null,
        @SerializedName("poi") val poi: AzurePoiMaybe? = null
    )

    private data class AzureLatLonMaybe(
        @SerializedName("lat") val lat: Double,
        @SerializedName("lon") val lon: Double
    )

    private data class AzureAddressMaybe(
        @SerializedName("freeformAddress") val freeformAddress: String? = null
    )

    private data class AzurePoiMaybe(
        @SerializedName("name") val name: String? = null
    )
}