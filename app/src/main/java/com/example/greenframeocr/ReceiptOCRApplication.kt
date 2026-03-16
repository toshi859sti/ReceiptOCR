package com.example.greenframeocr

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader

class ReceiptOCRApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize OpenCV
        try {
            if (OpenCVLoader.initLocal()) {
                Log.d(TAG, "OpenCV initialized successfully")
            } else {
                Log.e(TAG, "OpenCV initialization failed - this may cause runtime errors")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenCV initialization error", e)
        }
    }

    companion object {
        private const val TAG = "ReceiptOCRApplication"
    }
}
