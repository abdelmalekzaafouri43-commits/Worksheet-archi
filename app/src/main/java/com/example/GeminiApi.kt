package com.example

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null
)

@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part>
)

@Serializable
data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

@Serializable
data class InlineData(
    val mimeType: String,
    val data: String
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate>
)

@Serializable
data class Candidate(
    val content: Content
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-2.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    val service: GeminiApiService by lazy {
        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        retrofit.create(GeminiApiService::class.java)
    }
}

fun Bitmap.toBase64(): String {
    val outputStream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
}

suspend fun analyzeWorksheet(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.GEMINI_API_KEY
    if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
        return@withContext "{\"error\": \"Gemini API Key missing or invalid\"}"
    }

    val prompt = """
        Analyze this worksheet layout.
        Extract the title (document heading) and instructions.
        Respond in JSON format like this:
        {
          "heading": "Math Quiz",
          "instructions": "Solve the equations",
          "gridStyle": "Grid",
          "showNameLine": true
        }
        Only output the JSON. No markdown formatting.
    """.trimIndent()

    val request = GenerateContentRequest(
        contents = listOf(Content(
            parts = listOf(
                Part(text = prompt),
                Part(inlineData = InlineData(mimeType = "image/jpeg", data = bitmap.toBase64()))
            )
        ))
    )
    try {
        val response = RetrofitClient.service.generateContent(apiKey, request)
        val text = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "{}"
        // Remove markdown if Gemini adds it
        text.replace("```json", "").replace("```", "").trim()
    } catch (e: Exception) {
        "{\"error\": \"${e.message}\"}"
    }
}

val chatHistory = mutableListOf<Content>()

suspend fun sendChatToGemini(userText: String): Pair<String, String> = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.GEMINI_API_KEY
    if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
        return@withContext Pair("Please set your Gemini API Key in the AI Studio Secrets panel to enable chat.", "{}")
    }

    chatHistory.add(Content(role = "user", parts = listOf(Part(text = userText))))

    val systemInstructionText = """
        You are an AI Worksheet Architect. Chat with the user to help them design a worksheet.
        If the user asks to modify the worksheet (e.g., set a heading, instructions, or theme), 
        you MUST include a JSON block in your response with the exact keys:
        {
          "heading": "...",
          "instructions": "..."
        }
        Wrap the JSON exactly in ```json and ```.
        Also provide a brief, friendly conversational reply explaining what you did OUTSIDE the JSON block.
    """.trimIndent()

    val request = GenerateContentRequest(
        contents = chatHistory.toList(),
        systemInstruction = Content(parts = listOf(Part(text = systemInstructionText)))
    )

    try {
        val response = RetrofitClient.service.generateContent(apiKey, request)
        val replyText = response.candidates.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: "Sorry, I couldn't generate a response."
        
        chatHistory.add(Content(role = "model", parts = listOf(Part(text = replyText))))

        val jsonRegex = "```(?:json)?\\s*(\\{.*?\\})\\s*```".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = jsonRegex.find(replyText)
        var jsonString = match?.groupValues?.get(1)?.trim() ?: "{}"
        
        if (jsonString == "{}") {
             if (replyText.trim().startsWith("{") && replyText.trim().endsWith("}")) {
                 jsonString = replyText.trim()
             }
        }

        val conversationalText = replyText.replace(jsonRegex, "").replace("```json", "").replace("```", "").replace(jsonString, "").trim()
        
        Pair(if(conversationalText.isNotEmpty()) conversationalText else "Done!", jsonString)
    } catch (e: Exception) {
        Pair("Error: ${e.message}", "{}")
    }
}
