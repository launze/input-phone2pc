package com.voiceinput.cimbar

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.voiceinput.R
import org.opencv.android.CameraBridgeViewBase
import org.opencv.android.CameraBridgeViewBase.CvCameraViewFrame
import org.opencv.android.CameraBridgeViewBase.CvCameraViewListener2
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileTransferScanActivity : Activity(), CvCameraViewListener2 {
    private var camera1View: CameraBridgeViewBase? = null
    private var camera2View: CameraBridgeViewBase? = null
    private var activeCameraView: CameraBridgeViewBase? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var cameraApiButton: Button? = null

    private var nativeLoaded = false
    private var useCamera2 = false
    private var processingComplete = false
    private var lastStatsUpdate = 0L
    private var lastProgressUpdate = 0L
    private var frameCount = 0
    private var lastFrameTime = 0L

    private val dataPath: String by lazy { filesDir.absolutePath }
    private val modeVal = 68

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_file_transfer_scan)

        camera1View = findViewById<CameraBridgeViewBase>(R.id.file_scan_camera1).apply {
            setCvCameraViewListener(this@FileTransferScanActivity)
        }
        camera2View = findViewById<CameraBridgeViewBase>(R.id.file_scan_camera2).apply {
            setCvCameraViewListener(this@FileTransferScanActivity)
        }
        progressBar = findViewById(R.id.file_scan_progress)
        statusText = findViewById(R.id.file_scan_status)
        statsText = findViewById(R.id.file_scan_stats)
        cameraApiButton = findViewById<Button>(R.id.file_scan_camera_api).apply {
            setOnClickListener { switchCameraApi() }
        }
        findViewById<Button>(R.id.file_scan_back).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        OpencvCameraView.setTargetPreviewFps(60)
        OpencvCamera2View.setManualControls(60, 5_000_000L, 200)
        applyCameraSelection(restart = false)
        requestCameraPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        if (!OpenCVLoader.initDebug()) {
            statusText?.text = "OpenCV 初始化失败"
            Toast.makeText(this, "OpenCV 初始化失败", Toast.LENGTH_LONG).show()
            return
        }
        if (!nativeLoaded) {
            System.loadLibrary("cfc-cpp")
            nativeLoaded = true
            setTrackingJNI(0)
        }
        enableCameraIfReady()
    }

    override fun onPause() {
        if (nativeLoaded) {
            shutdownJNI()
        }
        camera1View?.disableView()
        camera2View?.disableView()
        super.onPause()
    }

    override fun onDestroy() {
        if (nativeLoaded) {
            shutdownJNI()
        }
        camera1View?.disableView()
        camera2View?.disableView()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                grantCameraPermissionToViews()
                enableCameraIfReady()
            } else {
                statusText?.text = "未授予相机权限"
                Toast.makeText(this, "未授予相机权限，无法扫码接收文件", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCameraViewStarted(width: Int, height: Int) {
        if (nativeLoaded) {
            resetStatsJNI()
        }
        lastFrameTime = 0
        frameCount = 0
        statusText?.text = "对准电脑屏幕上的文件传输图像"
    }

    override fun onCameraViewStopped() = Unit

    override fun onCameraFrame(frame: CvCameraViewFrame): Mat {
        val mat = frame.rgba()
        if (!nativeLoaded || processingComplete) {
            return mat
        }

        val now = System.currentTimeMillis()
        if (lastFrameTime > 0 && now > lastFrameTime) {
            frameCount++
        }
        lastFrameTime = now

        val result = processImageJNI(mat.nativeObjAddr, dataPath, modeVal)
        val stats = getStatsJNI()
        updateProgress(stats, now)
        updateStats(stats, now)

        if (result.isNotBlank() && !result.startsWith("/")) {
            processingComplete = true
            handleDecodedFile(result)
        }

        return mat
    }

    private fun requestCameraPermissionIfNeeded() {
        if (hasCameraPermission()) {
            grantCameraPermissionToViews()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST
            )
        }
    }

    private fun hasCameraPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun grantCameraPermissionToViews() {
        camera1View?.setCameraPermissionGranted()
        camera2View?.setCameraPermissionGranted()
    }

    private fun enableCameraIfReady() {
        if (!nativeLoaded || !hasCameraPermission() || processingComplete) {
            return
        }
        grantCameraPermissionToViews()
        activeCameraView?.enableView()
    }

    private fun switchCameraApi() {
        useCamera2 = !useCamera2
        applyCameraSelection(restart = true)
    }

    private fun applyCameraSelection(restart: Boolean) {
        val previous = activeCameraView
        activeCameraView = if (useCamera2) camera2View else camera1View
        camera1View?.visibility = if (useCamera2) View.GONE else SurfaceView.VISIBLE
        camera2View?.visibility = if (useCamera2) SurfaceView.VISIBLE else View.GONE
        cameraApiButton?.text = if (useCamera2) "C2" else "C1"
        if (restart && previous != activeCameraView) {
            previous?.disableView()
            if (nativeLoaded) {
                resetStatsJNI()
            }
            enableCameraIfReady()
        }
    }

    private fun updateProgress(stats: String, now: Long) {
        if (now - lastProgressUpdate < 120) {
            return
        }
        lastProgressUpdate = now
        val progress = statPercent(stats, "Progress").coerceIn(0, 100)
        runOnUiThread {
            progressBar?.progress = progress
            statusText?.text = if (progress > 0) {
                "正在接收文件 $progress%"
            } else {
                "对准电脑屏幕上的文件传输图像"
            }
        }
    }

    private fun updateStats(stats: String, now: Long) {
        if (now - lastStatsUpdate < 1000) {
            return
        }
        lastStatsUpdate = now
        val fps = frameCount
        frameCount = 0
        val compact = compactStats(stats, fps)
        runOnUiThread {
            statsText?.text = compact
        }
    }

    private fun handleDecodedFile(fileName: String) {
        runOnUiThread {
            statusText?.text = "接收完成，正在发送..."
            progressBar?.progress = 100
        }

        try {
            val source = File(dataPath, fileName)
            if (!source.exists()) {
                throw IllegalStateException("解码文件不存在: ${source.absolutePath}")
            }

            val safeName = sanitizeFileName(fileName)
            val output = uniqueFile(File(cacheDir, "scanned_files").apply { mkdirs() }, safeName)
            FileInputStream(source).use { input ->
                FileOutputStream(output).use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
            source.delete()

            val data = Intent().apply {
                putExtra(EXTRA_FILE_PATH, output.absolutePath)
                putExtra(EXTRA_FILE_NAME, output.name)
                putExtra(EXTRA_FILE_SIZE, output.length())
            }
            setResult(RESULT_OK, data)
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "failed to handle decoded file", e)
            runOnUiThread {
                statusText?.text = "接收失败: ${e.message ?: "未知错误"}"
                Toast.makeText(this, statusText?.text, Toast.LENGTH_LONG).show()
                processingComplete = false
            }
        }
    }

    private fun compactStats(stats: String, fps: Int): String {
        val progress = statPercent(stats, "Progress")
        val camera = statLine(stats, "Camera FPS").removePrefix("Camera FPS: ")
        val speed = statLine(stats, "Payload speed")
            .removePrefix("Payload speed: ")
            .replace(" KB/s 5s, ", " / ")
            .replace(" active avg", "")
        val ok = statLine(stats, "Scan OK")
            .removePrefix("Scan OK: ")
            .replace("  Decode OK: ", " / ")
        return "P $progress%  View ${fps}fps  Cam $camera\nRate $speed\nScan $ok"
    }

    private fun statLine(stats: String, prefix: String): String =
        stats.lineSequence().firstOrNull { it.startsWith(prefix) }.orEmpty()

    private fun statPercent(stats: String, prefix: String): Int {
        val line = statLine(stats, prefix)
        val colon = line.indexOf(':')
        val pct = line.indexOf('%', startIndex = (colon + 1).coerceAtLeast(0))
        if (colon < 0 || pct < 0) {
            return 0
        }
        return line.substring(colon + 1, pct).trim().toIntOrNull() ?: 0
    }

    private fun sanitizeFileName(fileName: String): String {
        val cleaned = fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\r\\n\\t]"), "_")
            .ifBlank { "scanned-file-${timestamp()}" }
        return "${timestamp()}_$cleaned"
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun uniqueFile(dir: File, preferredName: String): File {
        var candidate = File(dir, preferredName)
        if (!candidate.exists()) {
            return candidate
        }
        val dot = preferredName.lastIndexOf('.')
        val base = if (dot > 0) preferredName.substring(0, dot) else preferredName
        val ext = if (dot > 0) preferredName.substring(dot) else ""
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$suffix$ext")
            suffix++
        }
        return candidate
    }

    private external fun processImageJNI(mat: Long, path: String, modeInt: Int): String
    private external fun getStatsJNI(): String
    private external fun resetStatsJNI()
    private external fun shutdownJNI()
    private external fun setTrackingJNI(interval: Int)
    @Suppress("unused")
    private external fun requestDiagnosticSaveJNI()
    @Suppress("unused")
    private external fun consumeDiagnosticFilesJNI(): String

    companion object {
        private const val TAG = "FileTransferScan"
        private const val CAMERA_PERMISSION_REQUEST = 4101
        const val EXTRA_FILE_PATH = "com.voiceinput.cimbar.extra.FILE_PATH"
        const val EXTRA_FILE_NAME = "com.voiceinput.cimbar.extra.FILE_NAME"
        const val EXTRA_FILE_SIZE = "com.voiceinput.cimbar.extra.FILE_SIZE"
    }
}
