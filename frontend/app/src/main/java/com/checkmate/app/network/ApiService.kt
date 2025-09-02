package com.checkmate.app.network

import com.checkmate.app.data.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import timber.log.Timber
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * REST API service for backend communication.
 */
interface CheckmateApi {
    
    @GET("/")
    suspend fun healthCheck(): Response<Map<String, Any>>
    
    @POST("/sessions")
    suspend fun createSession(@Body settings: SessionSettings): Response<CreateSessionResponse>
    
    @GET("/sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): Response<SessionInfo>
    
    @DELETE("/sessions/{sessionId}")
    suspend fun deleteSession(@Path("sessionId") sessionId: String): Response<Map<String, String>>
    
    @GET("/sessions")
    suspend fun listSessions(): Response<Map<String, Any>>
}

class ApiService private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: ApiService? = null
        
        fun getInstance(): ApiService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiService().also { INSTANCE = it }
            }
        }
    }
    
    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .create()
    
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor { message ->
            Timber.tag("HTTP").d(message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    
    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(AppConfig.BACKEND_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    private val api: CheckmateApi = retrofit.create(CheckmateApi::class.java)
    
    suspend fun healthCheck(): Boolean {
        return try {
            val response = api.healthCheck()
            response.isSuccessful
        } catch (e: Exception) {
            Timber.e(e, "Health check failed")
            false
        }
    }
    
    suspend fun createSession(settings: SessionSettings): CreateSessionResponse? {
        return try {
            Timber.d("Creating session with settings: $settings")
            
            val response = api.createSession(settings)
            
            if (response.isSuccessful) {
                val result = response.body()
                Timber.d("Session created successfully: ${result?.sessionId}")
                result
            } else {
                Timber.e("Failed to create session: ${response.code()} - ${response.message()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error creating session")
            null
        }
    }
    
    suspend fun getSession(sessionId: String): SessionInfo? {
        return try {
            val response = api.getSession(sessionId)
            
            if (response.isSuccessful) {
                response.body()
            } else {
                Timber.e("Failed to get session: ${response.code()} - ${response.message()}")
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Error getting session")
            null
        }
    }
    
    suspend fun deleteSession(sessionId: String): Boolean {
        return try {
            Timber.d("Deleting session: $sessionId")
            
            val response = api.deleteSession(sessionId)
            
            if (response.isSuccessful) {
                Timber.d("Session deleted successfully")
                true
            } else {
                Timber.e("Failed to delete session: ${response.code()} - ${response.message()}")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Error deleting session")
            false
        }
    }
    
    suspend fun listSessions(): List<String> {
        return try {
            val response = api.listSessions()
            
            if (response.isSuccessful) {
                val body = response.body()
                @Suppress("UNCHECKED_CAST")
                (body?.get("sessions") as? List<String>) ?: emptyList()
            } else {
                Timber.e("Failed to list sessions: ${response.code()} - ${response.message()}")
                emptyList()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error listing sessions")
            emptyList()
        }
    }
}
