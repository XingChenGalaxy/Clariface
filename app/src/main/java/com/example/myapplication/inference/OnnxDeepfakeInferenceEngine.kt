package com.example.myapplication.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.exp

class OnnxDeepfakeInferenceEngine(
    private val context: Context,
    private val assetModelPath: String = "models/best_model.onnx"
) : DeepfakeInferenceEngine {

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    private val faceRoiCropper = FaceRoiCropper(context)

    init {
        val modelFile = copyAssetToCache(assetModelPath)
        val options = OrtSession.SessionOptions()
        session = environment.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.first()
    }

    override fun infer(bitmap: Bitmap): InferenceOutput {
        val start = System.nanoTime()
        val preprocess = preprocess(bitmap)
        val input = preprocess.input
        val shape = longArrayOf(1L, 3L, 256L, 256L)

        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape).use { inputTensor ->
            val output = session.run(mapOf(inputName to inputTensor))
            output.use {
                val raw = readFirstLogit(output[0].value)
                val probability = sigmoid(raw)
                val elapsed = (System.nanoTime() - start) / 1_000_000L
                    return InferenceOutput(
                        probability = probability,
                        elapsedMs = elapsed,
                        roiUsed = preprocess.roiUsed,
                        roiRect = preprocess.roiRect
                    )
            }
        }
    }

    override fun close() {
        session.close()
        environment.close()
    }

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val cropResult = faceRoiCropper.extract(bitmap)
        val roiBitmap = cropResult.bitmap
        val scaled = Bitmap.createScaledBitmap(roiBitmap, 256, 256, true)
        if (roiBitmap !== bitmap) {
            roiBitmap.recycle()
        }
        val pixels = IntArray(256 * 256)
        scaled.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        scaled.recycle()

        val out = FloatArray(3 * 256 * 256)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f

            out[i] = (r - mean[0]) / std[0]
            out[256 * 256 + i] = (g - mean[1]) / std[1]
            out[2 * 256 * 256 + i] = (b - mean[2]) / std[2]
        }
        return PreprocessResult(input = out, roiUsed = cropResult.roiUsed, roiRect = cropResult.roiRect)
    }

    private data class PreprocessResult(
        val input: FloatArray,
        val roiUsed: Boolean,
        val roiRect: Rect?
    )

    private fun readFirstLogit(value: Any?): Float {
        return when (value) {
            is FloatArray -> value.firstOrNull() ?: 0f
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> first.firstOrNull() ?: 0f
                    is Array<*> -> (first.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f
                    else -> 0f
                }
            }
            else -> 0f
        }
    }

    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    private fun copyAssetToCache(path: String): File {
        val modelName = path.substringAfterLast('/')
        val outFile = File(context.cacheDir, modelName)
        copySingleAsset(path, outFile)

        // Exported ONNX may use external tensor storage in a sibling .data file.
        val dataAssetPath = "$path.data"
        val dataOutFile = File(context.cacheDir, "$modelName.data")
        copySingleAssetIfExists(dataAssetPath, dataOutFile)

        return outFile
    }

    private fun copySingleAsset(assetPath: String, outFile: File) {
        if (outFile.exists()) {
            return
        }
        context.assets.open(assetPath).use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun copySingleAssetIfExists(assetPath: String, outFile: File) {
        if (outFile.exists()) {
            return
        }
        runCatching {
            context.assets.open(assetPath).use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}

