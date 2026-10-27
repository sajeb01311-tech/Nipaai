package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.ai.LiveErrorDiagnostics
import com.example.ai.LiveSessionManager
import com.example.ai.NipaPersona
import com.example.audio.AudioStreamer
import com.example.core.configureAppCheck
import com.example.tools.ToolManager
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ServiceConnectionHandshakeFailedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(PublicPreviewAPI::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `app name matches Nipa AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Nipa AI", appName)
    }

    @Test
    fun `gemini live system instruction omits unsupported system role for googleAI backend`() {
        val systemContent = NipaPersona.buildSystemInstructionContent()
        // Gemini Developer API BidiGenerateContent rejects role="system" in setup.systemInstruction;
        // Firebase AI Logic Live API requires default content { text(...) } (role="user" or null).
        assertNotEquals("system", systemContent.role)
        assertTrue(NipaPersona.SYSTEM_INSTRUCTION_TEXT.contains("Nipa"))
        assertEquals("gemini-3.1-flash-live-preview", LiveSessionManager.LIVE_MODEL_NAME)
    }

    @Test
    fun `error diagnostics extract server codes and strictly redact api keys and secrets`() {
        val fakeKey = "AIzaSyFakeKeyForUnitTestRedaction123456789"
        val rawUrlError =
            "WebSocket upgrade failed with HTTP 403 at wss://firebasevertexai.googleapis.com/ws/BidiGenerateContent?key=$fakeKey&token=mySecretDebugToken (used key $fakeKey)"
        val handshakeException = ServiceConnectionHandshakeFailedException(
            "Channel was closed by the server.",
            IllegalStateException(rawUrlError)
        )

        val report = LiveErrorDiagnostics.inspect(handshakeException)
        val debugLine = report.toDebugLogLine("connect_handshake", LiveSessionManager.LIVE_MODEL_NAME)

        assertEquals("HTTP_403", report.errorCode)
        assertFalse(report.isRetryable)
        assertFalse("API key must never appear in debug log", debugLine.contains(fakeKey))
        assertFalse("Debug token must never appear in debug log", debugLine.contains("mySecretDebugToken"))
        assertTrue(debugLine.contains("?key=[REDACTED]"))
        assertTrue(debugLine.contains("[REDACTED_API_KEY]"))
        assertTrue(debugLine.contains("ServiceConnectionHandshakeFailedException"))

        val quotaException = IllegalStateException("HTTP 429: Resource has been exhausted (check quota).")
        val quotaReport = LiveErrorDiagnostics.inspect(quotaException)
        assertEquals("HTTP_429", quotaReport.errorCode)
        assertFalse(quotaReport.isRetryable)
    }

    @Test
    fun `configureAppCheck persists debug secret synchronously from launch intent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testToken = "00000000-1111-2222-3333-444444444444"
        val launchIntent = Intent().putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", testToken)

        configureAppCheck(context, launchIntent)

        val persistenceKey = FirebaseApp.getInstance().persistenceKey
        val prefs = context.getSharedPreferences(
            "com.google.firebase.appcheck.debug.store.$persistenceKey",
            Context.MODE_PRIVATE
        )
        assertEquals(
            testToken,
            prefs.getString("com.google.firebase.appcheck.debug.DEBUG_SECRET", null)
        )
    }

    @Test
    fun `tool manager validates safe urls and blocks dangerous schemes`() {
        val youtube = ToolManager.validateAndNormalizeUrl("youtube")
        assertTrue(youtube is ToolManager.UrlValidationResult.Valid)
        assertEquals("https://www.youtube.com", (youtube as ToolManager.UrlValidationResult.Valid).normalizedUrl)

        val bareDomain = ToolManager.validateAndNormalizeUrl("github.com/explore")
        assertTrue(bareDomain is ToolManager.UrlValidationResult.Valid)
        assertEquals("https://github.com/explore", (bareDomain as ToolManager.UrlValidationResult.Valid).normalizedUrl)

        val xss = ToolManager.validateAndNormalizeUrl("javascript:alert(1)")
        assertTrue(xss is ToolManager.UrlValidationResult.Invalid)

        val loopback = ToolManager.validateAndNormalizeUrl("http://localhost:8080/admin")
        assertTrue(loopback is ToolManager.UrlValidationResult.Invalid)
    }

    @Test
    fun `audio streamer resamples 48kHz to 16kHz PCM16 cleanly`() {
        val streamer = AudioStreamer()
        val input48k = ShortArray(480) { (it * 25).toShort() }
        val resampled16k = streamer.resampleLinear(input48k, input48k.size, 48_000, 16_000)
        assertEquals(160, resampled16k.size)

        val pcmBytes = streamer.shortsToLittleEndianBytes(resampled16k)
        assertEquals(320, pcmBytes.size)
    }
}
