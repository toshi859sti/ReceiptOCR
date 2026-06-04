package com.example.greenframeocr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.util.BlackFrameDetector
import com.example.greenframeocr.util.GreenFrameDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import java.util.concurrent.atomic.AtomicLong

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Preview)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // ── デバッグオーバーレイ用 ──────────────────────────────────────────────

    data class FrameOverlayState(
        val corners: List<Point>,
        val success: Boolean,
        val imageWidth: Int,
        val imageHeight: Int
    )

    private val _greenFrameOverlay = MutableStateFlow<FrameOverlayState?>(null)
    val greenFrameOverlay: StateFlow<FrameOverlayState?> = _greenFrameOverlay.asStateFlow()

    private val _blackFrameOverlay = MutableStateFlow<FrameOverlayState?>(null)
    val blackFrameOverlay: StateFlow<FrameOverlayState?> = _blackFrameOverlay.asStateFlow()

    private val lastBlackFrameDetectionMs = AtomicLong(0L)
    private val BLACK_FRAME_INTERVAL_MS = 500L

    sealed class CameraUiState {
        object Preview : CameraUiState()
        object Processing : CameraUiState()
        data class Success(
            val detectionResult: GreenFrameDetector.DetectionResult
        ) : CameraUiState()
        data class Error(
            val message: String,
            val detectionResult: GreenFrameDetector.DetectionResult? = null
        ) : CameraUiState()
    }

    /**
     * 撮影した Bitmap を GreenFrameDetector に通して処理する
     */
    fun processImage(bitmap: Bitmap, debugMode: Boolean = false, sharpness: Double = 0.0) {
        viewModelScope.launch {
            try {
                _uiState.value = CameraUiState.Processing

                val result = withContext(Dispatchers.Default) {
                    GreenFrameDetector.process(bitmap, debugMode, sharpness)
                }

                if (result.success) {
                    Log.d(TAG, "Detection success: ${result.rowBitmaps.size} rows")
                    _uiState.value = CameraUiState.Success(result)
                } else {
                    Log.w(TAG, "Detection failed: ${result.errorMessage}")
                    _uiState.value = CameraUiState.Error(result.errorMessage, result)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error processing image", e)
                _uiState.value = CameraUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun resetToPreview() {
        _uiState.value = CameraUiState.Preview
    }

    fun updateGreenOverlay(corners: List<Point>?, imageWidth: Int, imageHeight: Int) {
        _greenFrameOverlay.value = FrameOverlayState(
            corners = corners ?: emptyList(),
            success = corners != null && corners.size == 4,
            imageWidth = imageWidth,
            imageHeight = imageHeight
        )
    }

    fun shouldRunBlackFrameDetection(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastBlackFrameDetectionMs.get()
        if (now - last <= BLACK_FRAME_INTERVAL_MS) return false
        return lastBlackFrameDetectionMs.compareAndSet(last, now)
    }

    fun updateBlackOverlay(result: BlackFrameDetector.DetectionResult) {
        _blackFrameOverlay.value = FrameOverlayState(
            corners = result.corners,
            success = result.success,
            imageWidth = result.imageWidth,
            imageHeight = result.imageHeight
        )
    }

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
