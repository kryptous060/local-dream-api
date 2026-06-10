package io.github.xororz.localdream.data

import androidx.compose.runtime.Immutable

/**
 * Fully resolved default generation parameters. The constructor defaults are
 * the app-wide global fallbacks: this is the single place they are defined.
 *
 * Per-model defaults are resolved field by field in [Model.defaults] with the
 * priority: built-in code defaults > config.json in the model directory >
 * these global values.
 */
@Immutable
data class GenerationDefaults(
    val prompt: String = "",
    val negativePrompt: String = "",
    val steps: Float = 20f,
    val cfg: Float = 7f,
    val scheduler: String = "dpm",
    val seed: String = "",
    val denoiseStrength: Float = 0.6f,
    val batchCounts: Int = 1,
    val aspectRatio: String = "1:1",
) {
    companion object {
        val GLOBAL = GenerationDefaults()
    }
}
