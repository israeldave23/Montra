package com.dga.montra

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import com.dga.montra.domain.ImageScaling
import java.io.File
import java.io.FileOutputStream

/**
 * The profile photo: imported once from the gallery, downscaled and re-encoded into app storage, then
 * drawn as a circle wherever the profile avatar appears.
 *
 * The app stores the photo itself rather than holding a durable URI permission: the original file may
 * be moved or deleted, and a persisted grant would keep a handle on someone else's document forever.
 */
internal object ProfileAvatar {
    private const val FILE_NAME = "profile_avatar.jpg"

    /** Stored photos are capped at this many pixels on the long edge. */
    const val MAX_DIMENSION = 512
    private const val QUALITY = 88

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.isFile && it.length() > 0L }

    /** File-backed picker intent; no storage permission is needed for the document picker. */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
    }

    /**
     * Copies the picked image into app storage. Returns true when a usable image was written.
     * Call this off the main thread: it decodes and re-encodes the bitmap.
     */
    fun import(context: Context, uri: Uri): Boolean {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }.getOrNull()
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = ImageScaling.sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
        }.getOrNull() ?: return false

        val resized = resize(decoded)
        val oriented = applyOrientation(context, uri, resized)
        val target = file(context)
        val temp = File(context.cacheDir, "avatar_tmp.jpg")
        val written = runCatching {
            FileOutputStream(temp).use { output ->
                oriented.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)
            }
        }.getOrDefault(false)
        if (!written) {
            temp.delete()
            return false
        }
        val stored = runCatching {
            temp.copyTo(target, overwrite = true)
            target.length() > 0L
        }.getOrDefault(false)
        temp.delete()
        if (!stored) {
            target.delete()
            return false
        }
        return true
    }

    fun remove(context: Context) {
        file(context).delete()
    }

    /**
     * Shows the stored photo as a circle, or falls back to the initials view. Both views are swapped
     * rather than layered so only one is ever read by a screen reader.
     */
    fun bind(context: Context, image: ImageView, initials: TextView, targetPx: Int) {
        val bitmap = load(context, targetPx)
        if (bitmap == null) {
            image.setImageDrawable(null)
            image.visibility = View.GONE
            initials.visibility = View.VISIBLE
            return
        }
        val drawable = RoundedBitmapDrawableFactory.create(context.resources, bitmap).apply {
            isCircular = true
        }
        image.setImageDrawable(drawable)
        image.contentDescription = context.getString(R.string.profile_photo_description)
        image.visibility = View.VISIBLE
        initials.visibility = View.GONE
    }

    /** Decodes the stored photo at (at most) [targetPx]; null when there is no usable photo. */
    fun load(context: Context, targetPx: Int): Bitmap? {
        val source = file(context)
        if (!source.isFile || source.length() == 0L) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageScaling.sampleSizeFor(
                bounds.outWidth,
                bounds.outHeight,
                targetPx.coerceAtLeast(1)
            )
        }
        val bitmap = runCatching {
            BitmapFactory.decodeFile(source.absolutePath, options)
        }.getOrNull() ?: return null
        return resize(bitmap, targetPx * 2)
    }

    private fun resize(bitmap: Bitmap, maxDimension: Int = MAX_DIMENSION): Bitmap {
        if (ImageScaling.fitsWithin(bitmap.width, bitmap.height, maxDimension)) return bitmap
        val (width, height) = ImageScaling.scaledDimension(bitmap.width, bitmap.height, maxDimension)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** Camera photos often carry a rotation flag; without this the circle would be sideways. */
    private fun applyOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { rotated ->
                if (rotated !== bitmap) bitmap.recycle()
            }
        }.getOrDefault(bitmap)
    }
}
