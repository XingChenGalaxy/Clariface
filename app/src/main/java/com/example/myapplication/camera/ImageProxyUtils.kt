package com.example.myapplication.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

object ImageProxyUtils {

    fun toBitmap(imageProxy: ImageProxy): Bitmap? {
        val nv21 = yuv420ToNv21(imageProxy)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val jpegBytes = ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
            out.toByteArray()
        }

        val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val rotation = imageProxy.imageInfo.rotationDegrees
        if (rotation == 0) {
            return decoded
        }

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaPixelStride = image.planes[1].pixelStride
        val width = image.width
        val height = image.height
        var outputPos = ySize

        val uBytes = ByteArray(uSize)
        val vBytes = ByteArray(vSize)
        uBuffer.get(uBytes)
        vBuffer.get(vBytes)

        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val index = row * chromaRowStride + col * chromaPixelStride
                nv21[outputPos++] = vBytes[index]
                nv21[outputPos++] = uBytes[index]
            }
        }

        return nv21
    }
}

