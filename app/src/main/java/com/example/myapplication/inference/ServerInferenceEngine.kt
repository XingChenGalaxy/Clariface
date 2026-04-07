package com.example.myapplication.inference

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 将屏幕帧发送到远程 Flask 服务器进行伪造检测。
 * 手机不做任何本地人脸裁剪或推理，全部交给服务器处理。
 */
class ServerInferenceEngine(
    private val serverUrl: String = SERVER_URL
) : DeepfakeInferenceEngine {

    companion object {
        // 配置好花生壳后替换此处
        const val SERVER_URL = "https://1221597jrjm30.vicp.fun/detect_frame"
        private const val TAG = "ServerInferenceEngine"
        private const val JPEG_QUALITY = 80
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 4_000
        private const val BOUNDARY = "----MaskSentinelBoundary"
    }

    private var lastProbability = 0.5f
    private var lastLabel = 1

    override fun infer(bitmap: Bitmap): InferenceOutput {
        val start = System.nanoTime()
        val jpegBytes = bitmapToJpeg(bitmap)

        val result = tryPostFrame(jpegBytes)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000L

        if (result == null) {
            Log.w(TAG, "服务器无响应，跳过本帧")
            return InferenceOutput(
                probability = lastProbability,
                elapsedMs = elapsedMs,
                roiUsed = false,
                validForDecision = false
            )
        }

        if (result.has("error")) {
            val err = result.optString("error")
            Log.d(TAG, "服务器返回: $err")
            return InferenceOutput(
                probability = lastProbability,
                elapsedMs = elapsedMs,
                roiUsed = false,
                validForDecision = false
            )
        }

        val prob = result.optDouble("probability", lastProbability.toDouble()).toFloat()
        val label = result.optInt("label", lastLabel)
        val faceRectJson = result.optJSONObject("face_rect")
        val faceRect = faceRectJson?.let {
            Rect(
                it.optInt("left", 0),
                it.optInt("top", 0),
                it.optInt("right", 0),
                it.optInt("bottom", 0)
            )
        }
        lastProbability = prob
        lastLabel = label

        Log.d(TAG, "检测结果: prob=$prob label=$label elapsed=${elapsedMs}ms faceRect=$faceRect")

        return InferenceOutput(
            probability = prob,
            elapsedMs = elapsedMs,
            roiUsed = faceRect != null,
            faceRect = faceRect,
            validForDecision = faceRect != null,
            isFake = label == 0
        )
    }

    override fun close() {
        // 无本地资源需要释放
    }

    private fun bitmapToJpeg(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        return baos.toByteArray()
    }

    /**
     * 用原生 HttpURLConnection 发送 multipart/form-data POST 请求。
     * 字段名为 'frame'，内容为 JPEG 字节。
     * 返回解析后的 JSONObject，失败返回 null。
     */
    private fun tryPostFrame(jpegBytes: ByteArray): JSONObject? {
        return try {
            val url = URL(serverUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$BOUNDARY")

            val dos = DataOutputStream(conn.outputStream)

            // -- part header
            dos.writeBytes("--$BOUNDARY\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"frame\"; filename=\"frame.jpg\"\r\n")
            dos.writeBytes("Content-Type: image/jpeg\r\n")
            dos.writeBytes("\r\n")
            dos.write(jpegBytes)
            dos.writeBytes("\r\n")
            dos.writeBytes("--$BOUNDARY--\r\n")
            dos.flush()
            dos.close()

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP 响应码: $code")
                conn.disconnect()
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "请求失败: ${e.message}")
            null
        }
    }
}
