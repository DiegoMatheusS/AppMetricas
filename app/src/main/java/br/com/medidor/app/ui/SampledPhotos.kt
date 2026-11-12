package br.com.medidor.app.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream

/** Decode a bounded image locally, correcting EXIF orientation before measuring. */
internal object SampledPhotos {
    fun fromUri(resolver: ContentResolver, uri: Uri): Bitmap =
        decode { resolver.openInputStream(uri) ?: error("Arquivo inacessível") }

    fun fromFile(file: File): Bitmap = decode { file.inputStream() }

    private fun decode(open: () -> InputStream): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Imagem inválida" }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > 2048) sample *= 2
        val photo = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Erro ao carregar fotografia")
        val exif = open().use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        val matrix = Matrix()
        when (exif) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        }
        if (matrix.isIdentity) return photo
        return Bitmap.createBitmap(photo, 0, 0, photo.width, photo.height, matrix, true).also {
            if (it !== photo) photo.recycle()
        }
    }
}
