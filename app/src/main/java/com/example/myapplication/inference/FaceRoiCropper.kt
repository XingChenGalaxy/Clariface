package com.example.myapplication.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.example.myapplication.R
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.CascadeClassifier
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sqrt

class FaceRoiCropper(
    context: Context,
    mode: TrackingMode = TrackingMode.BALANCED
) {
    private val detectScale: Double = mode.detectScale
    private val detectEveryNFrames: Int = mode.detectEveryNFrames
    private val smoothingAlpha: Float = mode.smoothingAlpha
    private val maxAllowedShiftRatio: Float = mode.maxAllowedShiftRatio
    private val noFaceFramesThreshold: Int = mode.noFaceFramesThreshold
    private val stableDetectionsRequired: Int = mode.stableDetectionsRequired
    private val minFaceAreaRatio: Float = mode.minFaceAreaRatio
    private val maxFaceAreaRatio: Float = mode.maxFaceAreaRatio
    private val minFaceEdgePx: Int = mode.minFaceEdgePx
    private val minNeighbors: Int = mode.minNeighbors
    private val minDetectSizePx: Double = mode.minDetectSizePx

    private val classifier: CascadeClassifier? = loadClassifier(context)

    private var frameIndex = 0
    private var lastDetectedRect: Rect? = null
    private var smoothedRect: Rect? = null
    private var noFaceFrames = 0
    private var stableDetections = 0

    fun extract(source: Bitmap): CropResult {
        frameIndex += 1

        if (frameIndex % detectEveryNFrames == 1) {
            val faces = detectFaces(source)
            val chosen = selectStableFace(faces, lastDetectedRect)
            if (chosen != null) {
                stableDetections = if (isConsistentWithPrevious(chosen, lastDetectedRect)) {
                    stableDetections + 1
                } else {
                    1
                }
                lastDetectedRect = chosen
                noFaceFrames = 0
            } else {
                noFaceFrames += 1
            }
        }

        if (noFaceFrames >= noFaceFramesThreshold) {
            lastDetectedRect = null
            smoothedRect = null
            noFaceFrames = 0
            stableDetections = 0
        }

        if (lastDetectedRect != null) {
            smoothedRect = smoothRect(smoothedRect, lastDetectedRect!!, smoothingAlpha)
        }

        if (stableDetections < stableDetectionsRequired || smoothedRect == null) {
            return CropResult(bitmap = source, roiUsed = false, roiRect = null)
        }

        val faceRect = Rect(smoothedRect!!)

        val roi = expandCropRect(faceRect, source.width, source.height)

        val clamped = Rect(
            roi.left.coerceIn(0, source.width - 1),
            roi.top.coerceIn(0, source.height - 1),
            roi.right.coerceIn(1, source.width),
            roi.bottom.coerceIn(1, source.height)
        )
        if (clamped.width() <= 1 || clamped.height() <= 1) {
            return CropResult(bitmap = source, roiUsed = false, roiRect = null, faceRect = null)
        }
        if (clamped.left == 0 && clamped.top == 0 && clamped.right == source.width && clamped.bottom == source.height) {
            return CropResult(bitmap = source, roiUsed = false, roiRect = null, faceRect = null)
        }
        val faceClamped = Rect(
            faceRect.left.coerceIn(0, source.width - 1),
            faceRect.top.coerceIn(0, source.height - 1),
            faceRect.right.coerceIn(1, source.width),
            faceRect.bottom.coerceIn(1, source.height)
        ).takeIf { it.width() > 1 && it.height() > 1 }

        return CropResult(
            bitmap = Bitmap.createBitmap(source, clamped.left, clamped.top, clamped.width(), clamped.height()),
            roiUsed = true,
            roiRect = clamped,
            faceRect = faceClamped
        )
    }

    private fun detectFaces(source: Bitmap): List<Rect> {
        val detector = classifier ?: return emptyList()
        if (source.width < 2 || source.height < 2) return emptyList()

        val rgba = Mat()
        val small = Mat()
        val gray = Mat()
        val found = MatOfRect()

        return runCatching {
            Utils.bitmapToMat(source, rgba)
            Imgproc.resize(rgba, small, Size(), detectScale, detectScale, Imgproc.INTER_LINEAR)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.equalizeHist(gray, gray)

            detector.detectMultiScale(
                gray,
                found,
                1.1,
                minNeighbors,
                0,
                Size(minDetectSizePx, minDetectSizePx),
                Size()
            )

            val invScale = (1.0 / detectScale).toFloat()
            found.toArray().map { f ->
                Rect(
                    (f.x * invScale).roundToInt(),
                    (f.y * invScale).roundToInt(),
                    ((f.x + f.width) * invScale).roundToInt(),
                    ((f.y + f.height) * invScale).roundToInt()
                )
            }.filter { isReasonableFaceRect(it, source.width, source.height) }
        }.getOrDefault(emptyList()).also {
            rgba.release()
            small.release()
            gray.release()
            found.release()
        }
    }

    private fun selectStableFace(faces: List<Rect>, previousRect: Rect?): Rect? {
        if (faces.isEmpty()) return null
        if (previousRect == null) {
            return faces.maxByOrNull { rectArea(it) }
        }

        val prevCenter = rectCenter(previousRect)
        val prevArea = rectArea(previousRect).coerceAtLeast(1f)

        var minDist = Float.MAX_VALUE
        var best: Rect? = null
        for (rect in faces) {
            val center = rectCenter(rect)
            val dist = distance(center, prevCenter)
            val areaRatio = rectArea(rect) / prevArea
            if (dist < sqrt(prevArea) * maxAllowedShiftRatio && areaRatio in 0.5f..2.0f) {
                if (dist < minDist) {
                    minDist = dist
                    best = rect
                }
            }
        }

        // Keep previous box during short pose changes/occlusions; reset logic handles stale boxes.
        return best ?: previousRect
    }

    private fun isReasonableFaceRect(rect: Rect, sourceW: Int, sourceH: Int): Boolean {
        if (rect.width() <= 0 || rect.height() <= 0) return false
        if (rect.width() < minFaceEdgePx || rect.height() < minFaceEdgePx) return false

        val frameArea = (sourceW * sourceH).coerceAtLeast(1).toFloat()
        val areaRatio = rectArea(rect) / frameArea
        if (areaRatio !in minFaceAreaRatio..maxFaceAreaRatio) return false

        val aspect = rect.width().toFloat() / rect.height().toFloat()
        return aspect in 0.6f..1.6f
    }

    private fun isConsistentWithPrevious(current: Rect, previous: Rect?): Boolean {
        if (previous == null) return false
        val prevArea = rectArea(previous).coerceAtLeast(1f)
        val dist = distance(rectCenter(current), rectCenter(previous))
        val areaRatio = rectArea(current) / prevArea
        return dist < sqrt(prevArea) * maxAllowedShiftRatio && areaRatio in 0.5f..2.0f
    }

    private fun smoothRect(previous: Rect?, current: Rect, alpha: Float): Rect {
        if (previous == null) return Rect(current)
        val oneMinus = 1f - alpha
        return Rect(
            (previous.left * oneMinus + current.left * alpha).roundToInt(),
            (previous.top * oneMinus + current.top * alpha).roundToInt(),
            (previous.right * oneMinus + current.right * alpha).roundToInt(),
            (previous.bottom * oneMinus + current.bottom * alpha).roundToInt()
        )
    }

    private fun rectCenter(rect: Rect): PointF {
        return PointF((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
    }

    private fun rectArea(rect: Rect): Float {
        return rect.width().coerceAtLeast(0) * rect.height().coerceAtLeast(0).toFloat()
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun expandCropRect(rect: Rect, sourceW: Int, sourceH: Int): Rect {
        val width = rect.width().coerceAtLeast(1)
        val height = rect.height().coerceAtLeast(1)
        val centerX = (rect.left + rect.right) / 2
        val centerY = (rect.top + rect.bottom) / 2

        val newW = (width * 3f).roundToInt().coerceAtLeast(width)
        val newH = (height * 3f).roundToInt().coerceAtLeast(height)

        val left = (centerX - newW / 2).coerceAtLeast(0)
        val top = (centerY - newH / 2).coerceAtLeast(0)
        val right = (centerX + newW / 2).coerceAtMost(sourceW)
        val bottom = (centerY + newH / 2).coerceAtMost(sourceH)
        return Rect(left, top, right, bottom)
    }

    private fun loadClassifier(context: Context): CascadeClassifier? {
        if (!OpenCVLoader.initDebug()) {
            return null
        }
        val cascadeFile = File(context.cacheDir, "haarcascade_frontalface_default.xml")
        if (!cascadeFile.exists() || cascadeFile.length() == 0L) {
            runCatching {
                context.resources.openRawResource(R.raw.haarcascade_frontalface_default).use { input ->
                    cascadeFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }.getOrNull() ?: return null
        }
        return runCatching { CascadeClassifier(cascadeFile.absolutePath) }
            .getOrNull()
            ?.takeIf { !it.empty() }
    }
}

enum class TrackingMode(
    val raw: String,
    val detectScale: Double,
    val detectEveryNFrames: Int,
    val smoothingAlpha: Float,
    val maxAllowedShiftRatio: Float,
    val noFaceFramesThreshold: Int,
    val stableDetectionsRequired: Int,
    val minFaceAreaRatio: Float,
    val maxFaceAreaRatio: Float,
    val minFaceEdgePx: Int,
    val minNeighbors: Int,
    val minDetectSizePx: Double
) {
    BALANCED(
        raw = "balanced",
        detectScale = 0.65,
        detectEveryNFrames = 2,
        smoothingAlpha = 0.55f,
        maxAllowedShiftRatio = 1.0f,
        noFaceFramesThreshold = 4,
        stableDetectionsRequired = 1,
        minFaceAreaRatio = 0.012f,
        maxFaceAreaRatio = 0.60f,
        minFaceEdgePx = 56,
        minNeighbors = 6,
        minDetectSizePx = 48.0
    ),
    FAST(
        raw = "fast",
        detectScale = 0.75,
        detectEveryNFrames = 1,
        smoothingAlpha = 0.72f,
        maxAllowedShiftRatio = 1.25f,
        noFaceFramesThreshold = 3,
        stableDetectionsRequired = 1,
        minFaceAreaRatio = 0.008f,
        maxFaceAreaRatio = 0.65f,
        minFaceEdgePx = 40,
        minNeighbors = 4,
        minDetectSizePx = 36.0
    );

    companion object {
        fun fromRaw(raw: String?): TrackingMode {
            return entries.firstOrNull { it.raw == raw } ?: BALANCED
        }
    }
}

data class CropResult(
    val bitmap: Bitmap,
    val roiUsed: Boolean,
    val roiRect: Rect?,
    val faceRect: Rect? = null
)

