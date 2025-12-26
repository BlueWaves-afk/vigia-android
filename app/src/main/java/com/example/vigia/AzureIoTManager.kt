package com.example.vigia

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AzureIoTManager (Event Hubs REST sender)
 *
 * Pipeline:
 * Android -> Azure Event Hubs -> Fabric Eventstream (Azure Event Hubs source) -> KQL RoadTelemetry
 *
 * Key ingestion fixes:
 * - Timestamp is ISO-8601 string (KQL datetime friendly)
 * - IsVerified is 0/1
 * - ReportId included for Activator Object ID + idempotency
 */
class AzureIoTManager(
    private val connectionString: String,
    private val eventHubName: String = "vigia-telemetry",
    private val deviceId: String = "android-edge-01",
) {

    companion object {
        private const val TAG = "AzureIoTManager"
        private const val SAS_TTL_SECONDS: Long = 60 * 60 // 1 hour
    }

    private var namespaceHost: String = ""  // e.g. yourns.servicebus.windows.net
    private var keyName: String = ""
    private var keyValue: String = ""

    // Actual hub name used (from EntityPath if present)
    private var activeEventHubName: String = eventHubName

    private val api: EventHubService

    init {
        parseConnectionString(connectionString)

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        val baseUrl = "https://$namespaceHost/"
        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttp)
            .build()

        api = retrofit.create(EventHubService::class.java)

        Log.i(TAG, "Initialized: namespace=$namespaceHost hub=$activeEventHubName device=$deviceId")
    }

    private fun parseConnectionString(cs: String) {
        try {
            val parts = cs.split(";").map { it.trim() }.filter { it.isNotEmpty() }
            for (p in parts) {
                when {
                    p.startsWith("Endpoint=", ignoreCase = true) -> {
                        // Endpoint=sb://<namespace>.servicebus.windows.net/
                        val raw = p.substringAfter("Endpoint=", "")
                        namespaceHost = raw
                            .removePrefix("sb://")
                            .removePrefix("SB://")
                            .removeSuffix("/")
                    }
                    p.startsWith("SharedAccessKeyName=", ignoreCase = true) -> {
                        keyName = p.substringAfter("SharedAccessKeyName=", "")
                    }
                    p.startsWith("SharedAccessKey=", ignoreCase = true) -> {
                        keyValue = p.substringAfter("SharedAccessKey=", "")
                    }
                    p.startsWith("EntityPath=", ignoreCase = true) -> {
                        activeEventHubName = p.substringAfter("EntityPath=", "")
                    }
                }
            }

            require(namespaceHost.isNotBlank()) { "Missing Endpoint in connection string" }
            require(keyName.isNotBlank()) { "Missing SharedAccessKeyName in connection string" }
            require(keyValue.isNotBlank()) { "Missing SharedAccessKey in connection string" }

        } catch (e: Exception) {
            Log.e(TAG, "Invalid connection string", e)
            throw IllegalArgumentException("Bad Event Hubs connection string format")
        }
    }

    /**
     * Sends a RoadTelemetry event (schema matches your KQL table fields).
     */
    suspend fun sendTelemetry(
        lat: Double,
        lon: Double,
        speed: Float,
        hazardType: String = "none",
        confidence: Float = 0.0f,
        gForceZ: Double = 0.0,
        gaussianSplatUrl: String = "pending",
        isVerified: Int = 0,
    ) {
        val payload = JSONObject().apply {
            // For Activator object ID + idempotency
            put("ReportId", UUID.randomUUID().toString())

            put("DeviceId", deviceId)

            // IMPORTANT: KQL datetime wants ISO-8601
            put("Timestamp", Instant.now().toString())

            put("Latitude", lat)
            put("Longitude", lon)
            put("Speed", speed)

            put("HazardType", hazardType)
            put("ConfidenceScore", confidence)

            put("GForceZ", gForceZ)
            put("GaussianSplatURL", gaussianSplatUrl)

            // Use 0/1 to match your 0/1 IsVerified column
            put("IsVerified", if (isVerified != 0) 1 else 0)

            // Optional helper for debugging / filtering
            put("messageType", "telemetry")
        }

        sendToEventHub(payload)
    }

    /**
     * Optional message channel (queries/commands). Keep consistent naming.
     */
    suspend fun sendCloudQuery(text: String, context: Map<String, Any?> = emptyMap()) {
        val payload = JSONObject().apply {
            put("ReportId", UUID.randomUUID().toString())
            put("DeviceId", deviceId)
            put("Timestamp", Instant.now().toString())
            put("messageType", "query")
            put("query", text)
            put("context", JSONObject(context))
        }
        sendToEventHub(payload)
    }

    private suspend fun sendToEventHub(json: JSONObject) = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body: RequestBody = json.toString().toRequestBody(mediaType)

        val sas = generateSasToken(
            namespaceHost = namespaceHost,
            hubName = activeEventHubName,
            keyName = keyName,
            key = keyValue,
            ttlSeconds = SAS_TTL_SECONDS
        )

        if (sas.isBlank()) {
            Log.e(TAG, "SAS token is empty; abort send")
            return@withContext
        }

        try {
            val resp = api.postMessage(
                hubName = activeEventHubName,
                authorization = sas,
                body = body
            )

            if (resp.isSuccessful) {
                Log.d(TAG, "Sent to EH ($activeEventHubName): ${json.optString("messageType")}")
            } else {
                val err = resp.errorBody()?.string() ?: "No error body"
                Log.e(TAG, "EH send failed: code=${resp.code()} err=$err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "EH network error", e)
        }
    }

    /**
     * Generates SAS for resource: https://<namespace>.servicebus.windows.net/<hubName>
     */
    private fun generateSasToken(
        namespaceHost: String,
        hubName: String,
        keyName: String,
        key: String,
        ttlSeconds: Long
    ): String {
        return try {
            val fullUri = "https://$namespaceHost/$hubName"
            val encodedUri = URLEncoder.encode(fullUri, "UTF-8")

            val expiry = (System.currentTimeMillis() / 1000L) + ttlSeconds
            val stringToSign = "$encodedUri\n$expiry"

            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)

            val signatureBytes = mac.doFinal(stringToSign.toByteArray(StandardCharsets.UTF_8))
            val signature = Base64.encodeToString(signatureBytes, Base64.NO_WRAP)
            val encodedSig = URLEncoder.encode(signature, "UTF-8")

            "SharedAccessSignature sr=$encodedUri&sig=$encodedSig&se=$expiry&skn=$keyName"
        } catch (e: Exception) {
            Log.e(TAG, "SAS token generation failed", e)
            ""
        }
    }

    interface EventHubService {
        @Headers(
            "Content-Type: application/json",
            "Accept: application/json"
        )
        @POST("{hubName}/messages")
        suspend fun postMessage(
            @Path("hubName") hubName: String,
            @Header("Authorization") authorization: String,
            @Body body: RequestBody
        ): retrofit2.Response<Void>
    }
}