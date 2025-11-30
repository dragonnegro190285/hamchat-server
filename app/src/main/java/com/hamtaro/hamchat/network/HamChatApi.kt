package com.hamtaro.hamchat.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

// DTOs

data class RegisterRequest(
    val username: String,
    val password: String,
    val phone_country_code: String,
    val phone_national: String
)

data class RegisterResponse(
    val id: Int,
    val username: String,
    val phone_country_code: String,
    val phone_national: String,
    val phone_e164: String
)

data class LoginRequest(
    val username: String? = null,
    val password: String? = null,
    val phone_country_code: String? = null,
    val phone_national: String? = null
)

data class LoginResponse(
    val user_id: Int,
    val token: String
)

data class MessageRequest(
    val recipient_id: Int,
    val content: String,
    val local_id: String? = null,    // ID local para evitar duplicados
    val sent_at: String? = null,     // Timestamp de envío del cliente
    val message_type: String = "text",  // "text" o "voice"
    val audio_data: String? = null,     // Base64 encoded audio
    val audio_duration: Int = 0         // Duración en segundos
)

data class MessageDto(
    val id: Int,
    val sender_id: Int,
    val recipient_id: Int,
    val content: String,
    val created_at: String,
    val sent_at: String? = null,
    val received_at: String? = null,
    val is_delivered: Boolean = false,
    val local_id: String? = null,
    val message_type: String = "text",
    val audio_data: String? = null,
    val audio_duration: Int = 0
)

data class MarkDeliveredRequest(
    val message_ids: List<Int>
)

data class UserLookupResponse(
    val id: Int,
    val username: String,
    val phone_e164: String
)

data class LadaDto(
    val id: Int,
    val code: String,
    val label: String?,
    val created_at: String
)

data class LadaCreateRequest(
    val code: String,
    val label: String?
)

data class HealthResponse(
    val status: String
)

data class InboxItemDto(
    val with_user_id: Int,
    val username: String,
    val phone_e164: String,
    val last_message: String,
    val last_message_at: String,
    val last_message_id: Int
)

// ---------- Modelos para Grupos ----------

data class CreateGroupRequest(
    val name: String,
    val description: String? = null,
    val member_ids: List<Int> = emptyList()
)

data class GroupDto(
    val id: Int,
    val name: String,
    val description: String?,
    val creator_id: Int,
    val created_at: String,
    val member_count: Int
)

data class GroupMemberDto(
    val user_id: Int,
    val username: String,
    val phone_e164: String,
    val role: String,
    val joined_at: String
)

data class GroupMessageRequest(
    val group_id: Int,
    val content: String,
    val local_id: String? = null,
    val sent_at: String? = null
)

data class GroupMessageDto(
    val id: Int,
    val group_id: Int,
    val sender_id: Int,
    val sender_name: String,
    val content: String,
    val created_at: String,
    val sent_at: String? = null,
    val local_id: String? = null
)

data class AddGroupMemberRequest(
    val user_id: Int
)

interface HamChatApi {

    @POST("register")
    fun register(
        @Body body: RegisterRequest
    ): Call<RegisterResponse>

    @POST("login")
    fun login(
        @Body body: LoginRequest
    ): Call<LoginResponse>

    @POST("messages")
    fun sendMessage(
        @Header("Authorization") authHeader: String,
        @Body body: MessageRequest
    ): Call<MessageDto>

    @GET("messages")
    fun getMessages(
        @Header("Authorization") authHeader: String,
        @Query("with_user_id") withUserId: Int,
        @Query("limit") limit: Int = 50
    ): Call<List<MessageDto>>

    @GET("messages/since")
    fun getMessagesSince(
        @Header("Authorization") authHeader: String,
        @Query("with_user_id") withUserId: Int,
        @Query("since_id") sinceId: Int
    ): Call<List<MessageDto>>

    @POST("messages/mark-delivered")
    fun markMessagesDelivered(
        @Header("Authorization") authHeader: String,
        @Body body: MarkDeliveredRequest
    ): Call<Map<String, Any>>

    @GET("users/by-username/{username}")
    fun getUserByUsername(
        @retrofit2.http.Path("username") username: String
    ): Call<UserLookupResponse>

    @GET("users/by-phone")
    fun getUserByPhone(
        @Query("phone_country_code") phoneCountryCode: String,
        @Query("phone_national") phoneNational: String
    ): Call<UserLookupResponse>

    @GET("ladas")
    fun getLadas(): Call<List<LadaDto>>

    @POST("ladas")
    fun addLada(
        @Body body: LadaCreateRequest
    ): Call<LadaDto>

    @GET("inbox")
    fun getInbox(
        @Header("Authorization") authHeader: String
    ): Call<List<InboxItemDto>>

    @GET("health")
    fun health(): Call<HealthResponse>

    // ---------- Endpoints de Grupos ----------

    @POST("groups")
    fun createGroup(
        @Header("Authorization") authHeader: String,
        @Body body: CreateGroupRequest
    ): Call<GroupDto>

    @GET("groups")
    fun getMyGroups(
        @Header("Authorization") authHeader: String
    ): Call<List<GroupDto>>

    @GET("groups/{group_id}")
    fun getGroup(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Path("group_id") groupId: Int
    ): Call<GroupDto>

    @GET("groups/{group_id}/members")
    fun getGroupMembers(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Path("group_id") groupId: Int
    ): Call<List<GroupMemberDto>>

    @POST("groups/{group_id}/members")
    fun addGroupMember(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Path("group_id") groupId: Int,
        @Body body: AddGroupMemberRequest
    ): Call<Map<String, Any>>

    @DELETE("groups/{group_id}/members/{user_id}")
    fun removeGroupMember(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Path("group_id") groupId: Int,
        @retrofit2.http.Path("user_id") userId: Int
    ): Call<Map<String, Any>>

    @POST("groups/messages")
    fun sendGroupMessage(
        @Header("Authorization") authHeader: String,
        @Body body: GroupMessageRequest
    ): Call<GroupMessageDto>

    @GET("groups/{group_id}/messages")
    fun getGroupMessages(
        @Header("Authorization") authHeader: String,
        @retrofit2.http.Path("group_id") groupId: Int,
        @Query("since_id") sinceId: Int = 0
    ): Call<List<GroupMessageDto>>
}

object HamChatApiClient {

    // URL base por defecto (servidor en la nube - Render)
    private const val DEFAULT_BASE_URL = "https://hamchat-server.onrender.com/api/"
    
    // URL del servidor local embebido
    private const val LOCAL_SERVER_URL = "http://localhost:8080/api/"

    private var currentBaseUrl: String = DEFAULT_BASE_URL
    private var cachedApi: HamChatApi? = null

    private val client: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val api: HamChatApi
        get() {
            if (cachedApi == null) {
                cachedApi = createApi(currentBaseUrl)
            }
            return cachedApi!!
        }

    /**
     * Cambia la URL del servidor
     * Útil para conectarse a otro dispositivo en la red local
     */
    fun setServerUrl(url: String) {
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"
        val apiUrl = if (normalizedUrl.contains("/api/")) normalizedUrl else "${normalizedUrl}api/"
        
        if (apiUrl != currentBaseUrl) {
            currentBaseUrl = apiUrl
            cachedApi = createApi(apiUrl)
        }
    }

    /**
     * Usa el servidor local embebido
     */
    fun useLocalServer() {
        setServerUrl(LOCAL_SERVER_URL)
    }

    /**
     * Usa el servidor en la nube
     */
    fun useCloudServer() {
        setServerUrl(DEFAULT_BASE_URL)
    }

    /**
     * Obtiene la URL actual del servidor
     */
    fun getCurrentServerUrl(): String = currentBaseUrl

    /**
     * Crea un cliente API para una URL específica
     * Útil para conectarse a múltiples servidores simultáneamente
     */
    fun createApiForUrl(baseUrl: String): HamChatApi {
        val normalizedUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        val apiUrl = if (normalizedUrl.contains("/api/")) normalizedUrl else "${normalizedUrl}api/"
        return createApi(apiUrl)
    }

    private fun createApi(baseUrl: String): HamChatApi {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HamChatApi::class.java)
    }
}
