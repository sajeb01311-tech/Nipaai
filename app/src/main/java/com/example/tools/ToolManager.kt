package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.domain.BrowserActionEvent
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * Tool Layer for Nipa AI Assistant.
 * Defines safe browser navigation function declarations (`openWebsite` and `searchWeb`),
 * strictly validates URL formats, schemes, and host safety, executes Android browser intents,
 * and returns structured `FunctionResponsePart` payloads immediately to Gemini Live.
 */
class ToolManager(
    private val appContext: Context,
    private val onBrowserActionExecuted: (BrowserActionEvent) -> Unit
) {

    companion object {
        private const val TAG = "ToolManager"
        const val FN_OPEN_WEBSITE = "openWebsite"
        const val FN_SEARCH_WEB = "searchWeb"

        private val ALLOWED_SCHEMES = setOf("https", "http")
        private val BLOCKED_HOSTS = setOf(
            "localhost",
            "127.0.0.1",
            "0.0.0.0",
            "::1",
            "169.254.169.254"
        )

        private val KNOWN_SITE_SHORTCUTS = mapOf(
            "youtube" to "https://www.youtube.com",
            "google" to "https://www.google.com",
            "github" to "https://github.com",
            "wikipedia" to "https://www.wikipedia.org",
            "reddit" to "https://www.reddit.com",
            "gmail" to "https://mail.google.com",
            "maps" to "https://maps.google.com",
            "spotify" to "https://open.spotify.com",
            "netflix" to "https://www.netflix.com",
            "news" to "https://news.google.com",
            "x" to "https://x.com",
            "twitter" to "https://x.com",
            "linkedin" to "https://www.linkedin.com"
        )

        fun validateAndNormalizeUrl(input: String): UrlValidationResult {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) {
                return UrlValidationResult.Invalid("URL cannot be empty.")
            }
            if (trimmed.length > 2048) {
                return UrlValidationResult.Invalid("URL exceeds maximum allowed length.")
            }

            val lower = trimmed.lowercase()
            val forbiddenPrefixes = listOf("javascript:", "data:", "file:", "intent:", "content:", "vbscript:", "blob:")
            if (forbiddenPrefixes.any { lower.startsWith(it) }) {
                return UrlValidationResult.Invalid("Protocol is disallowed for security reasons.")
            }

            val shortcut = KNOWN_SITE_SHORTCUTS[lower]
            val candidate = when {
                shortcut != null -> shortcut
                lower.startsWith("https://") || lower.startsWith("http://") -> trimmed
                !trimmed.contains("://") && trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
                else -> return UrlValidationResult.Invalid("Invalid URL format. Only http/https web URLs are allowed.")
            }

            return try {
                val parsed = URI(candidate)
                val scheme = parsed.scheme?.lowercase()
                    ?: return UrlValidationResult.Invalid("Missing URL protocol.")
                if (scheme !in ALLOWED_SCHEMES) {
                    return UrlValidationResult.Invalid("Only HTTP and HTTPS protocols are permitted.")
                }

                if (parsed.userInfo != null) {
                    return UrlValidationResult.Invalid("Embedded credentials in URLs are not allowed.")
                }

                val host = parsed.host?.lowercase()?.trim()
                    ?: return UrlValidationResult.Invalid("URL must include a valid domain host.")

                if (host in BLOCKED_HOSTS ||
                    host.endsWith(".local") ||
                    host.startsWith("192.168.") ||
                    host.startsWith("10.") ||
                    !host.contains(".")
                ) {
                    return UrlValidationResult.Invalid("Private, local, or unqualified network hosts are blocked.")
                }

                val finalUri = if (scheme == "http" && parsed.port == -1) {
                    URI(
                        "https",
                        parsed.userInfo,
                        parsed.host,
                        parsed.port,
                        parsed.path,
                        parsed.query,
                        parsed.fragment
                    ).toASCIIString()
                } else {
                    parsed.toASCIIString()
                }

                UrlValidationResult.Valid(normalizedUrl = finalUri, host = host)
            } catch (e: Exception) {
                UrlValidationResult.Invalid("Malformed URL syntax.")
            }
        }
    }

    fun getTools(): List<Tool> {
        val openWebsiteDeclaration = FunctionDeclaration(
            name = FN_OPEN_WEBSITE,
            description = "Opens a validated website URL in the user's browser when the user asks Nipa to open or visit a website (for example YouTube, GitHub, Wikipedia, or any valid domain).",
            parameters = mapOf(
                "url" to Schema.string(
                    description = "The full HTTPS URL to open, such as https://www.youtube.com or https://github.com"
                ),
                "siteName" to Schema.string(
                    description = "Friendly short name of the website being opened, e.g. YouTube"
                )
            ),
            optionalParameters = listOf("siteName")
        )

        val searchWebDeclaration = FunctionDeclaration(
            name = FN_SEARCH_WEB,
            description = "Opens a safe web search in the user's browser when the user asks Nipa to look up or search for something in their browser.",
            parameters = mapOf(
                "query" to Schema.string(
                    description = "The search query to open in Google Search"
                )
            ),
            optionalParameters = emptyList()
        )

        return listOf(
            Tool.functionDeclarations(listOf(openWebsiteDeclaration, searchWebDeclaration))
        )
    }

    fun executeToolCall(call: FunctionCallPart): FunctionResponsePart {
        Log.d(TAG, "Executing Gemini Live tool call: ${call.name}")
        return when (call.name) {
            FN_OPEN_WEBSITE -> handleOpenWebsite(call)
            FN_SEARCH_WEB -> handleSearchWeb(call)
            else -> buildErrorResponse(
                call = call,
                errorMessage = "Unsupported tool '${call.name}'."
            )
        }
    }

    private fun handleOpenWebsite(call: FunctionCallPart): FunctionResponsePart {
        val rawUrl = call.args["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        val rawSiteName = call.args["siteName"]?.jsonPrimitive?.content?.trim().orEmpty()

        val validation = validateAndNormalizeUrl(rawUrl)
        if (validation !is UrlValidationResult.Valid) {
            val reason = (validation as UrlValidationResult.Invalid).reason
            Log.w(TAG, "Rejected unsafe or invalid URL '$rawUrl': $reason")
            return buildErrorResponse(call, reason)
        }

        val safeUrl = validation.normalizedUrl
        val displayTitle = rawSiteName.ifBlank { validation.host }

        return launchBrowserIntent(
            call = call,
            safeUrl = safeUrl,
            actionTitle = displayTitle
        )
    }

    private fun handleSearchWeb(call: FunctionCallPart): FunctionResponsePart {
        val query = call.args["query"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (query.isBlank() || query.length > 300) {
            return buildErrorResponse(call, "Search query must be non-empty and under 300 characters.")
        }

        val encodedQuery = Uri.encode(query)
        val safeSearchUrl = "https://www.google.com/search?q=$encodedQuery"
        return launchBrowserIntent(
            call = call,
            safeUrl = safeSearchUrl,
            actionTitle = "Search: $query"
        )
    }

    private fun launchBrowserIntent(
        call: FunctionCallPart,
        safeUrl: String,
        actionTitle: String
    ): FunctionResponsePart {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            onBrowserActionExecuted(
                BrowserActionEvent(
                    actionTitle = actionTitle,
                    validatedUrl = safeUrl
                )
            )
            val payload = JsonObject(
                mapOf(
                    "success" to JsonPrimitive(true),
                    "openedUrl" to JsonPrimitive(safeUrl),
                    "title" to JsonPrimitive(actionTitle),
                    "message" to JsonPrimitive("Successfully opened $actionTitle ($safeUrl) in the browser.")
                )
            )
            if (call.id != null) {
                FunctionResponsePart(call.name, payload, call.id)
            } else {
                FunctionResponsePart(call.name, payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch browser for $safeUrl", e)
            buildErrorResponse(
                call = call,
                errorMessage = "Could not open browser on this device: ${e.localizedMessage ?: "No browser available"}"
            )
        }
    }

    private fun buildErrorResponse(call: FunctionCallPart, errorMessage: String): FunctionResponsePart {
        val payload = JsonObject(
            mapOf(
                "success" to JsonPrimitive(false),
                "error" to JsonPrimitive(errorMessage)
            )
        )
        return if (call.id != null) {
            FunctionResponsePart(call.name, payload, call.id)
        } else {
            FunctionResponsePart(call.name, payload)
        }
    }

    sealed interface UrlValidationResult {
        data class Valid(val normalizedUrl: String, val host: String) : UrlValidationResult
        data class Invalid(val reason: String) : UrlValidationResult
    }
}
