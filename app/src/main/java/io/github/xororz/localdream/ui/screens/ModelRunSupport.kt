package io.github.xororz.localdream.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import io.github.xororz.localdream.data.GenerationMode
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.utils.Http
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun checkStoragePermission(context: Context): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    true // Android 10
} else {
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
}

private val tokenizeClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
}

private val healthClient: OkHttpClient by lazy {
    Http.client.newBuilder()
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .build()
}

internal data class TokenizeResult(val count: Int, val maxLength: Int, val overflowOffset: Int)

internal suspend fun tokenizePromptRequest(text: String): TokenizeResult? = withContext(Dispatchers.IO) {
    try {
        val body = JSONObject().apply { put("prompt", text) }
            .toString()
            .toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url("http://localhost:8081/tokenize")
            .post(body)
            .build()
        tokenizeClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val payload = response.body?.string() ?: return@withContext null
            val json = JSONObject(payload)
            TokenizeResult(
                count = json.optInt("count", 0),
                maxLength = json.optInt("max_length", 77),
                overflowOffset = json.optInt("overflow_offset", -1),
            )
        }
    } catch (_: Exception) {
        null
    }
}

internal suspend fun checkBackendHealth(
    backendState: StateFlow<BackendService.BackendState>,
    onHealthy: () -> Unit,
    onUnhealthy: () -> Unit,
) = withContext(Dispatchers.IO) {
    try {
        val startTime = System.currentTimeMillis()
        val timeoutDuration = 60000
        // Poll fast while the backend is likely just starting, then back off:
        // model loading takes seconds to minutes, so hammering every 100 ms
        // for the whole window is pointless.
        var pollDelayMs = 100L

        while (currentCoroutineContext().isActive) {
            if (backendState.value is BackendService.BackendState.Error) {
                withContext(Dispatchers.Main) {
                    onUnhealthy()
                }
                break
            }

            if (System.currentTimeMillis() - startTime > timeoutDuration) {
                withContext(Dispatchers.Main) {
                    onUnhealthy()
                }
                break
            }

            try {
                val request = Request.Builder()
                    .url("http://localhost:8081/health")
                    .get()
                    .build()

                val response = healthClient.newCall(request).execute()
                if (response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        onHealthy()
                    }
                    break
                }
            } catch (e: Exception) {
                // Backend not up yet; retry after the current delay.
            }

            delay(pollDelayMs)
            pollDelayMs = (pollDelayMs * 2).coerceAtMost(500L)
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            onUnhealthy()
        }
    }
}

/**
 * For SDXL with a non-1:1 aspectRatio, returns the centered (target_w, target_h)
 * region inside the 1024x1024 generation canvas. The longest side is forced to
 * canvasMax (1024), the shortest side is scaled by the ratio and aligned down to
 * a multiple of 8. Returns null in all other cases (non-SDXL, 1:1, malformed),
 * meaning "no padding, use canvas size directly."
 */
fun computeAspectTargetSize(isSdxl: Boolean, aspectRatio: String, canvasMax: Int = 1024): Pair<Int, Int>? {
    if (!isSdxl) return null
    val parts = aspectRatio.split(":")
    if (parts.size != 2) return null
    val rw = parts[0].toIntOrNull() ?: return null
    val rh = parts[1].toIntOrNull() ?: return null
    if (rw <= 0 || rh <= 0 || rw == rh) return null
    return if (rw >= rh) {
        val th = ((canvasMax.toDouble() * rh / rw).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(canvasMax, th)
    } else {
        val tw = ((canvasMax.toDouble() * rw / rh).toInt() / 8 * 8).coerceAtLeast(8)
        Pair(tw, canvasMax)
    }
}

/**
 * GCD-reduces (width, height) into a "W:H" aspect-ratio string.
 * Used by reproduce/import paths to recover an aspect from a recorded result size.
 */
fun inferAspectRatioString(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "1:1"
    var a = width
    var b = height
    while (b != 0) {
        val t = b
        b = a % b
        a = t
    }
    return "${width / a}:${height / a}"
}

/**
 * Pads `src` (already at targetW x targetH) into a canvas of size canvasW x canvasH
 * with a centered placement and black borders. If src already matches canvas size,
 * returns the source unchanged.
 */
fun padBitmapToCanvas(src: Bitmap, canvasW: Int, canvasH: Int): Bitmap {
    if (src.width == canvasW && src.height == canvasH) return src
    val out = createBitmap(canvasW, canvasH)
    val canvas = Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    val left = ((canvasW - src.width) / 2).toFloat()
    val top = ((canvasH - src.height) / 2).toFloat()
    canvas.drawBitmap(src, left, top, null)
    return out
}

/**
 * PNG-compresses the bitmap (lossless; the quality argument is ignored by the
 * PNG encoder) and returns it as a base64 string for backend upload.
 *
 * Uploads must stay lossless: inpaint pastes the unmasked region of the
 * uploaded base image verbatim into the final result (laplacian blend), so
 * any compression artifacts would survive into the output. Masks need exact
 * pixel values anyway.
 */
internal fun bitmapToBase64Png(bitmap: Bitmap): String {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
    return Base64.getEncoder().encodeToString(baos.toByteArray())
}

/** Default generation canvas side length for a model class. */
internal fun defaultGenerationSize(isSdxl: Boolean, runOnCpu: Boolean): Int = when {
    isSdxl -> 1024
    runOnCpu -> 256
    else -> 512
}

@Immutable
data class GenerationParameters(
    val steps: Int,
    val cfg: Float,
    val seed: Long?,
    val prompt: String,
    val negativePrompt: String,
    val generationTime: String?,
    val width: Int,
    val height: Int,
    val runOnCpu: Boolean,
    val denoiseStrength: Float = 0.6f,
    val useOpenCL: Boolean = false,
    val scheduler: String = "dpm",
    val mode: GenerationMode = GenerationMode.UNKNOWN,
)
