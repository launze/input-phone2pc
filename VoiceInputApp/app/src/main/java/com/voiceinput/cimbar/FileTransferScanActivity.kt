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
    private var ringProgress: RingProgressView? = null
    private var speedText: TextView? = null
    private var sizeText: TextView? = null
    private var statusText: TextView? = null
    private var statsText: TextView? = null
    private var debugText: TextView? = null
    private var cameraApiButton: Button? = null
    private var cameraFpsButton: Button? = null
    private var cameraShutterButton: Button? = null
    private var cameraIsoButton: Button? = null
    private var trackReuseButton: Button? = null
    private var diagnosticButton: Button? = null
    private var resetParamsButton: Button? = null

    private var nativeLoaded = false
    private var useCamera2 = true
    private val cameraFpsOptions = intArrayOf(30, 60)
    private val shutterOptionsNs = longArrayOf(
        16_666_666L, 8_333_333L, 6_944_444L, 6_250_000L,
        5_555_556L, 5_000_000L, 4_545_455L, 4_166_666L
    )
    private val shutterLabels = arrayOf("1/60", "1/120", "1/144", "1/160", "1/180", "1/200", "1/220", "1/240")
    private val isoOptions = intArrayOf(100, 200, 300, 400, 800, 1600)
    private val trackReuseOptions = intArrayOf(0, 5, 10)
    private var cameraFpsIndex = 1
    private var shutterIndex = 5
    private var isoIndex = 1
    private var trackReuseIndex = 0
    private var processingComplete = false
    private var lastStatsUpdate = 0L
    private var lastProgressUpdate = 0L
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var lastPayloadBytes = 0L
    private var lastProgress = 0
    private var finalFileSize = 0L

    private val dataPath: String by lazy { filesDir.absolutePath }
    private val modeVal = HEMERA_C2_MODE

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
        ringProgress = findViewById(R.id.file_scan_ring_progress)
        speedText = findViewById(R.id.file_scan_speed)
        sizeText = findViewById(R.id.file_scan_size)
        statusText = findViewById(R.id.file_scan_status)
        statsText = findViewById(R.id.file_scan_stats)
        debugText = findViewById(R.id.file_scan_debug)
        cameraApiButton = findViewById<Button>(R.id.file_scan_camera_api).apply {
            setOnClickListener { switchCameraApi() }
        }
        cameraFpsButton = findViewById<Button>(R.id.file_scan_camera_fps).apply {
            setOnClickListener {
                cameraFpsIndex = (cameraFpsIndex + 1) % cameraFpsOptions.size
                applyCameraManualParams(restartCamera1 = true)
            }
        }
        cameraShutterButton = findViewById<Button>(R.id.file_scan_camera_shutter).apply {
            setOnClickListener {
                shutterIndex = (shutterIndex + 1) % shutterOptionsNs.size
                applyCameraManualParams()
            }
        }
        cameraIsoButton = findViewById<Button>(R.id.file_scan_camera_iso).apply {
            setOnClickListener {
                isoIndex = (isoIndex + 1) % isoOptions.size
                applyCameraManualParams()
            }
        }
        trackReuseButton = findViewById<Button>(R.id.file_scan_track_reuse).apply {
            setOnClickListener {
                trackReuseIndex = (trackReuseIndex + 1) % trackReuseOptions.size
                applyTrackingParams()
                if (nativeLoaded) resetStatsJNI()
            }
        }
        diagnosticButton = findViewById<Button>(R.id.file_scan_diag).apply {
            setOnClickListener {
                if (nativeLoaded) {
                    requestDiagnosticSaveJNI()
                    Toast.makeText(this@FileTransferScanActivity, "已请求保存诊断帧", Toast.LENGTH_SHORT).show()
                }
            }
        }
        resetParamsButton = findViewById<Button>(R.id.file_scan_reset_params).apply {
            setOnClickListener { resetCameraParams() }
        }
        findViewById<Button>(R.id.file_scan_back).setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        applyCameraManualParams()
        updateControlButtons()
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
            applyTrackingParams()
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
        lastPayloadBytes = 0L
        lastProgress = 0
        finalFileSize = 0L
        ringProgress?.setProgressPercent(0)
        speedText?.text = "速度 0 KB/s"
        sizeText?.text = "已接收 0 KB / --"
        statusText?.visibility = View.VISIBLE
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
        updateControlButtons()
        if (restart && previous != activeCameraView) {
            previous?.disableView()
            if (nativeLoaded) {
                resetStatsJNI()
            }
            enableCameraIfReady()
        }
    }

    private fun applyCameraManualParams(restartCamera1: Boolean = false) {
        val fps = cameraFpsOptions[cameraFpsIndex]
        val shutterNs = shutterOptionsNs[shutterIndex]
        val iso = isoOptions[isoIndex]
        OpencvCameraView.setTargetPreviewFps(fps)
        OpencvCamera2View.setManualControls(fps, shutterNs, iso)
        (camera2View as? OpencvCamera2View)?.applyManualControls()
        updateControlButtons()
        if (restartCamera1 && !useCamera2 && nativeLoaded) {
            activeCameraView?.disableView()
            enableCameraIfReady()
        }
    }

    private fun applyTrackingParams() {
        if (nativeLoaded) {
            setTrackingJNI(trackReuseOptions[trackReuseIndex])
        }
        updateControlButtons()
    }

    private fun resetCameraParams() {
        useCamera2 = true
        cameraFpsIndex = 1
        shutterIndex = 5
        isoIndex = 1
        trackReuseIndex = 0
        applyCameraManualParams()
        applyTrackingParams()
        applyCameraSelection(restart = true)
        if (nativeLoaded) {
            resetStatsJNI()
        }
        Toast.makeText(this, "参数已重置", Toast.LENGTH_SHORT).show()
    }

    private fun updateControlButtons() {
        cameraApiButton?.text = if (useCamera2) "C2" else "C1"
        cameraFpsButton?.text = "${cameraFpsOptions[cameraFpsIndex]}帧"
        cameraShutterButton?.text = shutterLabels[shutterIndex]
        cameraIsoButton?.text = "ISO${isoOptions[isoIndex]}"
        trackReuseButton?.text = trackReuseLabel()
    }

    private fun trackReuseLabel(): String {
        val frames = trackReuseOptions[trackReuseIndex]
        return if (frames == 0) "全扫" else "复用$frames"
    }

    private fun updateProgress(stats: String, now: Long) {
        if (now - lastProgressUpdate < 120) {
            return
        }
        lastProgressUpdate = now
        val progress = statPercent(stats, "Progress").coerceIn(0, 100)
        val payloadBytes = statLong(stats, "Payload bytes")
        val payloadTotalBytes = statLong(stats, "Payload total bytes")
        lastProgress = progress
        lastPayloadBytes = payloadBytes
        val speed = statPayloadSpeed(stats)
        runOnUiThread {
            ringProgress?.setProgressPercent(progress)
            speedText?.text = "速度 $speed"
            val receivedBytes = displayReceivedBytes(payloadBytes, payloadTotalBytes, progress)
            sizeText?.text = "已接收 ${formatBytes(receivedBytes)} / ${totalSizeText(payloadBytes, payloadTotalBytes, progress)}"
            val hasStarted = progress > 0 || payloadBytes > 0L || payloadTotalBytes > 0L
            statusText?.visibility = if (hasStarted) View.GONE else View.VISIBLE
            if (!hasStarted) {
                statusText?.text = "对准电脑屏幕上的文件传输图像"
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
        val debug = debugStats(stats)
        runOnUiThread {
            statsText?.text = compact
            debugText?.text = debug
        }
    }

    private fun handleDecodedFile(fileName: String) {
        runOnUiThread {
            statusText?.text = "接收完成，正在发送..."
            statusText?.visibility = View.VISIBLE
            ringProgress?.setProgressPercent(100)
        }

        try {
            val source = File(dataPath, fileName)
            if (!source.exists()) {
                throw IllegalStateException("解码文件不存在: ${source.absolutePath}")
            }
            finalFileSize = source.length()
            runOnUiThread {
                sizeText?.text = "已接收 ${formatBytes(finalFileSize)} / ${formatBytes(finalFileSize)}"
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
                statusText?.visibility = View.VISIBLE
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
        val files = statLine(stats, "Files").removePrefix("Files: ")
        return "状态 ${if (processingComplete) "完成" else "接收中"}  进度 $progress%\n" +
            "画面 ${fps}帧/秒  相机 $camera  ${if (useCamera2) "C2" else "C1"}\n" +
            "速率 $speed\n" +
            "扫描/解码 $ok\n" +
            "文件 $files\n" +
            "定位 ${trackReuseLabel()}"
    }

    private fun debugStats(stats: String): String {
        val translated = stats
            .lineSequence()
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("Progress:") || it.startsWith("Camera FPS:") || it.startsWith("Payload speed:") || it.startsWith("Payload bytes:") || it.startsWith("Payload total bytes:") || it.startsWith("Files:") }
            .joinToString("\n") { line ->
                line
                    .replace("Frames:", "帧:")
                    .replace("scanned:", "扫描:")
                    .replace("decoded:", "解码:")
                    .replace("Scan OK:", "扫描成功:")
                    .replace("Decode OK:", "解码成功:")
                    .replace("Locate:", "定位:")
                    .replace("reuse", "复用")
                    .replace("full", "全扫")
                    .replace("interval", "间隔")
                    .replace("Mode:", "模式:")
                    .replace("detected", "检测")
                    .replace("backlog", "积压")
                    .replace("drop", "丢帧")
                    .replace("Work ms:", "耗时ms:")
                    .replace("scan", "扫描")
                    .replace("extract", "提取")
                    .replace("decode", "解码")
                    .replace("Threads:", "线程:")
            }
        return translated.ifBlank { "调试信息等待中..." }
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

    private fun statLong(stats: String, prefix: String): Long {
        val line = statLine(stats, prefix)
        val colon = line.indexOf(':')
        if (colon < 0) {
            return 0L
        }
        return line.substring(colon + 1).trim().toLongOrNull() ?: 0L
    }

    private fun statPayloadSpeed(stats: String): String {
        val raw = statLine(stats, "Payload speed")
            .removePrefix("Payload speed: ")
            .substringBefore(" KB/s 5s")
            .trim()
            .toDoubleOrNull() ?: 0.0
        return String.format(Locale.US, "%.1f KB/s", raw)
    }

    private fun totalSizeText(receivedBytes: Long, totalBytes: Long, progress: Int): String {
        if (finalFileSize > 0) {
            return formatBytes(finalFileSize)
        }
        if (totalBytes > 0L) {
            return formatBytes(totalBytes)
        }
        if (receivedBytes <= 0L || progress <= 0) {
            return "--"
        }
        val estimated = (receivedBytes * 100L / progress).coerceAtLeast(receivedBytes)
        return "~${formatBytes(estimated)}"
    }

    private fun displayReceivedBytes(payloadBytes: Long, totalBytes: Long, progress: Int): Long {
        if (finalFileSize > 0) {
            return finalFileSize
        }
        if (totalBytes > 0L && progress > 0) {
            return (totalBytes * progress / 100L).coerceIn(0L, totalBytes)
        }
        return payloadBytes
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 KB"
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb)
        }
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
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
        private const val HEMERA_C2_MODE = 68
        const val EXTRA_FILE_PATH = "com.voiceinput.cimbar.extra.FILE_PATH"
        const val EXTRA_FILE_NAME = "com.voiceinput.cimbar.extra.FILE_NAME"
        const val EXTRA_FILE_SIZE = "com.voiceinput.cimbar.extra.FILE_SIZE"
    }
}
