package com.example.greenframeocr.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.greenframeocr.util.GreenFrameDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Preview)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

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

    companion object {
        private const val TAG = "CameraViewModel"
    }
}
