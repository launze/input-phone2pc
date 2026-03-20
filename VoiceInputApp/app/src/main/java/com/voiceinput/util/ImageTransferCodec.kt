package com.voiceinput.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.voiceinput.data.model.ClipboardImagePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object ImageTransferCodec {
    private const val MAX_DIMENSION = 1600
    private const val MAX_ENCODED_BYTES = 900_000
    private const val MIN_JPEG_QUALITY = 55
    private const val JPEG_QUALITY_STEP = 5

    suspend fun encodeFromUri(context: Context, uri: Uri): ClipboardImagePayload? =
        withContext(Dispatchers.IO) {
            val decoded = decodeBitmap(context, uri) ?: return@withContext null
            encodeBitmap(decoded, "photo_${System.currentTimeMillis()}.jpg")
        }

    suspend fun encodeFromBitmap(bitmap: Bitmap): ClipboardImagePayload? =
        withContext(Dispatchers.IO) {
            encodeBitmap(bitmap, "photo_${System.currentTimeMillis()}.jpg")
        }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap? {
        return try {
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val size = info.size
                    val maxSide = max(size.width, size.height)
                    if (maxSide > MAX_DIMENSION) {
                        val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
                        decoder.setTargetSize(
                            (size.width * scale).roundToInt(),
                            (size.height * scale).roundToInt()
                        )
                    }
                }
            } else {
                decodeBitmapCompat(context, uri)
            } ?: return null

            rotateIfNeeded(context, uri, bitmap)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBitmapCompat(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        val maxSide = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        val sampleSize = (maxSide.toFloat() / MAX_DIMENSION.toFloat()).roundToInt().coerceAtLeast(1)
        val options = BitmapFactory.Options().apply {
            inSampleSize = highestPowerOfTwo(sampleSize)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, fileName: String): ClipboardImagePayload? {
        var workingBitmap = constrainBitmap(bitmap)
        var quality = 85
        var encoded = compressJpeg(workingBitmap, quality)

        while (encoded.size > MAX_ENCODED_BYTES && quality > MIN_JPEG_QUALITY) {
            quality -= JPEG_QUALITY_STEP
            encoded = compressJpeg(workingBitmap, quality)
        }

        while (encoded.size > MAX_ENCODED_BYTES && max(workingBitmap.width, workingBitmap.height) > 960) {
            workingBitmap = Bitmap.createScaledBitmap(
                workingBitmap,
                (workingBitmap.width * 0.85f).roundToInt().coerceAtLeast(1),
                (workingBitmap.height * 0.85f).roundToInt().coerceAtLeast(1),
                true
            )
            quality = 80
            encoded = compressJpeg(workingBitmap, quality)
            while (encoded.size > MAX_ENCODED_BYTES && quality > MIN_JPEG_QUALITY) {
                quality -= JPEG_QUALITY_STEP
                encoded = compressJpeg(workingBitmap, quality)
            }
        }

        if (encoded.isEmpty()) {
            return null
        }

        val imageBase64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
        return ClipboardImagePayload(
            mimeType = "image/jpeg",
            fileName = fileName,
            imageData = imageBase64,
            width = workingBitmap.width,
            height = workingBitmap.height,
            size = encoded.size
        )
    }

    private fun constrainBitmap(bitmap: Bitmap): Bitmap {
        val maxSide = max(bitmap.width, bitmap.height)
        if (maxSide <= MAX_DIMENSION) {
            return bitmap
        }

        val scale = MAX_DIMENSION.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun rotateIfNeeded(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val rotationDegrees = context.contentResolver.openInputStream(uri)?.use { input ->
            exifRotationDegrees(input)
        } ?: 0

        if (rotationDegrees == 0) {
            return bitmap
        }

        val matrix = android.graphics.Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun exifRotationDegrees(inputStream: InputStream): Int {
        return when (ExifInterface(inputStream).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        return output.toByteArray()
    }

    private fun highestPowerOfTwo(value: Int): Int {
        var sample = 1
        while (sample * 2 <= value) {
            sample *= 2
        }
        return sample
    }
}
