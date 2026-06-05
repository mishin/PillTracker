package com.syncftp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ImageCompressor {

    private const val MAX_WIDTH = 640
    private const val MAX_HEIGHT = 480
    private const val COMPRESS_QUALITY = 40

    suspend fun compressImage(sourcePath: String, destFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(sourcePath, options)
                var width = options.outWidth
                var height = options.outHeight
                val sampleSize = if (width > height) (width / MAX_WIDTH).coerceAtLeast(1) else (height / MAX_HEIGHT).coerceAtLeast(1)
                options.inJustDecodeBounds = false
                options.inSampleSize = sampleSize
                var bitmap = BitmapFactory.decodeFile(sourcePath, options)
                bitmap = rotateBitmapIfNeeded(bitmap, sourcePath)
                FileOutputStream(destFile).use { outputStream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESS_QUALITY, outputStream)
                }
                bitmap.recycle()
                return@withContext true
            } catch (e: Exception) {
                return@withContext false
            }
        }
    }

    private fun rotateBitmapIfNeeded(bitmap: Bitmap, filePath: String): Bitmap {
        val exif = ExifInterface(filePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}