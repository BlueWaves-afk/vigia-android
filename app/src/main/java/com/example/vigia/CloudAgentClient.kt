package com.example.vigia

import android.util.Log
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit
import com.example.vigia.BuildConfig
// ---- 1. Request/Response models ----

data class CloudRequest(
    @SerializedName("query") val query: String,
    @SerializedName("context") val context: Map<String, Any?>
)

data class CloudResponse(
    @SerializedName("reply") val reply: String?
)

// NEW: Model for Regional Sync (Offline Trip Prefetcher)
data class RegionalSyncRequest(
    @SerializedName("n") val north: Double,
    @SerializedName("s") val south: Double,
    @SerializedName("e") val east: Double,
    @SerializedName("w") val west: Double
)

// ---- 2. Retrofit API interface ----

interface VigiaCloudApi {
    @POST("api/ask_cloud_agent")
    fun askCloudAgent(@Body body: CloudRequest): Call<CloudResponse>

    // NEW: Sync route for the "Offline Map + Data" technique
    @POST("api/get-regional-hazards")
    fun getRegionalHazards(@Body body: RegionalSyncRequest): Call<List<Map<String, Any>>>
}

// ---- 3. Singleton client wrapper ----

object CloudAgentClient {

    private const val BASE_URL = BuildConfig.AZURE_BASE_URL

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val api: VigiaCloudApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VigiaCloudApi::class.java)
    }

    /**
     * Human-Led Investigator: Send a query to the cloud agent.
     */
    fun sendQuery(
        query: String,
        context: Map<String, Any?>,
        onResult: (String) -> Unit
    ) {
        val req = CloudRequest(query = query, context = context)

        api.askCloudAgent(req).enqueue(object : Callback<CloudResponse> {
            override fun onResponse(call: Call<CloudResponse>, response: Response<CloudResponse>) {
                if (response.isSuccessful) {
                    val reply = response.body()?.reply ?: "Cloud: [Empty Reply]"
                    onResult(reply)
                } else {
                    val errorDetail = response.errorBody()?.string() ?: "Unknown error"
                    Log.e("CloudAgentClient", "HTTP ${response.code()}: $errorDetail")
                    onResult("Cloud Error: ${response.code()}")
                }
            }
            override fun onFailure(call: Call<CloudResponse>, t: Throwable) {
                Log.e("CloudAgentClient", "Network failure", t)
                onResult("Network Error: ${t.localizedMessage}")
            }
        })
    }

    /**
     * Offline Prefetcher: Fetch all hazards for a specific trip region.
     */
    fun fetchRegionalHazards(
        north: Double, south: Double, east: Double, west: Double,
        onResult: (List<Map<String, Any>>?) -> Unit
    ) {
        val req = RegionalSyncRequest(north, south, east, west)
        api.getRegionalHazards(req).enqueue(object : Callback<List<Map<String, Any>>> {
            override fun onResponse(call: Call<List<Map<String, Any>>>, response: Response<List<Map<String, Any>>>) {
                if (response.isSuccessful) onResult(response.body())
                else onResult(null)
            }
            override fun onFailure(call: Call<List<Map<String, Any>>>, t: Throwable) {
                Log.e("CloudAgentClient", "Sync Failure", t)
                onResult(null)
            }
        })
    }
}