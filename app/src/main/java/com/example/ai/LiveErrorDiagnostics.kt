package com.example.ai

import com.google.firebase.ai.type.InvalidAPIKeyException
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.QuotaExceededException
import com.google.firebase.ai.type.ServerException
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import com.google.firebase.ai.type.ServiceDisabledException
import com.google.firebase.ai.type.UnsupportedUserLocationException

/**
 * Extracts structured server error codes, WebSocket close reasons, and exception causes
 * for Gemini Live debug logging and UI recovery messages while strictly redacting
 * API keys, App Check tokens, Bearer credentials, and query secrets.
 */
object LiveErrorDiagnostics {

    private val GOOGLE_API_KEY_REGEX = Regex("""AIza[0-9A-Za-z\-_]{20,}""")
    private val QUERY_SECRET_REGEX = Regex("""(?i)([?&](?:key|api_key|token|access_token|secret)=)[^&\s"']+""")
    private val HEADER_SECRET_REGEX = Regex("""(?i)((?:x-firebase-appcheck|x-goog-api-key|authorization)\s*[:=]\s*)(?:Bearer\s+)?[^\s,;"]+""")
    private val HTTP_CODE_REGEX = Regex("""\b(400|401|403|404|408|409|429|500|502|503|504)\b""")
    private val WS_CODE_REGEX = Regex("""\b(1000|1001|1002|1003|1006|1007|1008|1009|1011|1012|1013)\b""")

    data class ServerErrorReport(
        val errorCode: String,
        val exceptionType: String,
        val sanitizedServerMessage: String,
        val sanitizedCauseChain: String,
        val closeReasonSummary: String?,
        val isRetryable: Boolean,
        val userFacingMessage: String
    ) {
        fun toDebugLogLine(stage: String, modelName: String): String {
            val closePart = closeReasonSummary?.let { " | wsClose=$it" }.orEmpty()
            val causePart = if (sanitizedCauseChain.isNotBlank()) " | cause=$sanitizedCauseChain" else ""
            return "GeminiLive[$stage] code=$errorCode type=$exceptionType model=$modelName " +
                "retryable=$isRetryable message=\"$sanitizedServerMessage\"$closePart$causePart"
        }
    }

    fun redactSecrets(input: String?): String {
        if (input.isNullOrBlank()) return ""
        return input
            .replace(GOOGLE_API_KEY_REGEX, "[REDACTED_API_KEY]")
            .replace(QUERY_SECRET_REGEX, "$1[REDACTED]")
            .replace(HEADER_SECRET_REGEX, "$1[REDACTED]")
    }

    @OptIn(PublicPreviewAPI::class)
    fun inspect(throwable: Throwable, session: LiveSession? = null): ServerErrorReport {
        val wsClose = extractWebSocketCloseReason(session)
        val rawPrimary = throwable.message ?: throwable.localizedMessage.orEmpty()
        val sanitizedPrimary = redactSecrets(rawPrimary)
        val causeChain = buildSanitizedCauseChain(throwable)
        val combinedText = listOfNotNull(sanitizedPrimary, wsClose, causeChain).joinToString(" ")

        val extractedCode = detectErrorCode(throwable, combinedText, wsClose)
        val retryable = determineRetryable(throwable, extractedCode, combinedText)
        val userMessage = buildUserFacingMessage(throwable, extractedCode, sanitizedPrimary, wsClose)

        return ServerErrorReport(
            errorCode = extractedCode,
            exceptionType = throwable::class.simpleName ?: "Exception",
            sanitizedServerMessage = sanitizedPrimary.ifBlank { "No message provided by server" },
            sanitizedCauseChain = causeChain,
            closeReasonSummary = wsClose,
            isRetryable = retryable,
            userFacingMessage = userMessage
        )
    }

    /**
     * Safely inspects the completed WebSocket CloseReason (code + server reason phrase)
     * from an active LiveSession when the server closes the channel mid-session,
     * without throwing or exposing headers/URLs.
     */
    @OptIn(PublicPreviewAPI::class)
    fun extractWebSocketCloseReason(session: LiveSession?): String? {
        if (session == null) return null
        return try {
            val sessionField = session.javaClass.getDeclaredField("session")
            sessionField.isAccessible = true
            val ktorWsSession = sessionField.get(session) ?: return null

            val closeReasonGetter = ktorWsSession.javaClass.methods.firstOrNull {
                it.name == "getCloseReason" && it.parameterCount == 0
            } ?: return null
            val deferred = closeReasonGetter.invoke(ktorWsSession) ?: return null

            val isCompletedMethod = deferred.javaClass.methods.firstOrNull {
                it.name == "isCompleted" && it.parameterCount == 0
            }
            val isCompleted = (isCompletedMethod?.invoke(deferred) as? Boolean) ?: false
            if (!isCompleted) return null

            val getCompletedMethod = deferred.javaClass.methods.firstOrNull {
                it.name == "getCompleted" && it.parameterCount == 0
            } ?: return null
            val closeReasonObj = getCompletedMethod.invoke(deferred) ?: return null

            val code = closeReasonObj.javaClass.methods
                .firstOrNull { it.name == "getCode" && it.parameterCount == 0 }
                ?.invoke(closeReasonObj)
            val message = closeReasonObj.javaClass.methods
                .firstOrNull { it.name == "getMessage" && it.parameterCount == 0 }
                ?.invoke(closeReasonObj)
                ?.toString()

            val cleanMessage = redactSecrets(message).trim()
            if (code != null) {
                if (cleanMessage.isNotEmpty()) "$code ($cleanMessage)" else "$code"
            } else {
                cleanMessage.ifEmpty { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildSanitizedCauseChain(root: Throwable): String {
        val parts = mutableListOf<String>()
        var cursor: Throwable? = root.cause
        var depth = 0
        while (cursor != null && depth < 5) {
            val name = cursor::class.simpleName ?: "Throwable"
            val msg = redactSecrets(cursor.message).trim()
            parts.add(if (msg.isNotEmpty()) "$name: $msg" else name)
            cursor = cursor.cause
            depth++
        }
        return parts.joinToString(" -> ")
    }

    private fun detectErrorCode(
        throwable: Throwable,
        combinedText: String,
        wsClose: String?
    ): String {
        if (wsClose != null) {
            WS_CODE_REGEX.find(wsClose)?.value?.let { return "WS_$it" }
        }
        HTTP_CODE_REGEX.find(combinedText)?.value?.let { return "HTTP_$it" }
        WS_CODE_REGEX.find(combinedText)?.value?.let { return "WS_$it" }

        return when (throwable) {
            is InvalidAPIKeyException -> "AUTH_INVALID_API_KEY"
            is QuotaExceededException -> "QUOTA_EXCEEDED_429"
            is ServiceDisabledException -> "SERVICE_DISABLED_403"
            is UnsupportedUserLocationException -> "UNSUPPORTED_LOCATION"
            is ServerException -> "SERVER_ERROR_5XX"
            is ServiceConnectionHandshakeFailedException -> "WS_HANDSHAKE_CLOSED"
            else -> "STREAM_ERROR"
        }
    }

    private fun determineRetryable(
        throwable: Throwable,
        errorCode: String,
        combinedText: String
    ): Boolean {
        if (throwable is InvalidAPIKeyException ||
            throwable is ServiceDisabledException ||
            throwable is UnsupportedUserLocationException ||
            throwable is QuotaExceededException
        ) {
            return false
        }
        if (errorCode in setOf("HTTP_400", "HTTP_401", "HTTP_403", "HTTP_404", "HTTP_429", "WS_1007", "WS_1008")) {
            return false
        }
        if (combinedText.contains("AppCheck", ignoreCase = true) ||
            combinedText.contains("permission", ignoreCase = true) ||
            combinedText.contains("invalid argument", ignoreCase = true) ||
            combinedText.contains("quota", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun buildUserFacingMessage(
        throwable: Throwable,
        errorCode: String,
        sanitizedPrimary: String,
        wsClose: String?
    ): String {
        return when {
            errorCode == "HTTP_403" ||
                throwable is ServiceDisabledException ||
                sanitizedPrimary.contains("403") ||
                sanitizedPrimary.contains("AppCheck", ignoreCase = true) ->
                "Security attestation check failed (HTTP 403). Please reconnect in a moment."

            throwable is InvalidAPIKeyException || errorCode == "HTTP_401" ->
                "Firebase AI authentication rejected the request ($errorCode). Check project provisioning."

            throwable is UnsupportedUserLocationException ->
                "Gemini Live is not currently available in this region."

            throwable is QuotaExceededException ||
                errorCode == "HTTP_429" ||
                sanitizedPrimary.contains("quota", ignoreCase = true) ->
                "Nipa's voice channel is temporarily busy (rate limit). Give it a few seconds and retry."

            sanitizedPrimary.contains("Unable to resolve host", ignoreCase = true) ||
                sanitizedPrimary.contains("network", ignoreCase = true) ||
                sanitizedPrimary.contains("Socket", ignoreCase = true) ->
                "Oops, I lost the network connection. Tap the microphone to try again."

            wsClose != null && sanitizedPrimary.isNotBlank() ->
                "Oops, I lost the connection ($wsClose): $sanitizedPrimary"

            sanitizedPrimary.isNotBlank() ->
                "Oops, I lost the connection: $sanitizedPrimary"

            else ->
                "Oops, I lost the connection. Tap the microphone to try again."
        }
    }
}
