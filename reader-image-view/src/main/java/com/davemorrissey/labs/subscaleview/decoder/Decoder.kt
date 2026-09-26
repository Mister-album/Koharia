package com.davemorrissey.labs.subscaleview.decoder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.Log
import com.davemorrissey.labs.subscaleview.provider.InputProvider
import tachiyomi.decoder.ImageDecoder
import tachiyomi.decoder.ImageDecoder.Companion.newInstance

class Decoder(
    private val cropBorders: Boolean,
    private val hardwareConfig: Boolean,
    private val displayProfile: ByteArray,
) : ImageRegionDecoder {

    private var decoder: ImageDecoder? = null

    /**
     * Initialise the decoder. When possible, perform initial setup work once in this method. The
     * dimensions of the image must be returned.
     *
     * @param context  Application context. A reference may be held, but must be cleared on recycle.
     * @param provider Provider of the image.
     * @return Dimensions of the image.
     * @throws Exception if initialisation fails.
     */
    override fun init(context: Context, provider: InputProvider): Point {
        try {
            provider.openStream().use { inputStream ->
                decoder = newInstance(inputStream!!, cropBorders, displayProfile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init decoder", e)
        }
        if (decoder == null) {
            error("Image decoder failed to initialize and get image size")
        }
        return Point(decoder!!.width, decoder!!.height)
    }

    /**
     * Decode a region of the image with the given sample size. This method is called off the UI
     * thread so it can safely load the image on the current thread. It is called from
     * [android.os.AsyncTask]s running in an executor that may have multiple threads, so
     * implementations must be thread safe. Adding `synchronized` to the method signature
     * is the simplest way to achieve this, but bear in mind the [.recycle] method can be
     * called concurrently.
     *
     * @param sRect      Source image rectangle to decode.
     * @param sampleSize Sample size.
     * @return The decoded region. It is safe to return null if decoding fails.
     */
    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        var bitmap = decoder?.decode(sRect, sampleSize)
        check(bitmap != null) { "Failed to decode region" }
        if (hardwareConfig && Build.VERSION.SDK_INT >= 26) {
            val hwBitmap = bitmap.copy(Bitmap.Config.HARDWARE, false)
            if (hwBitmap != null) {
                bitmap.recycle()
                bitmap = hwBitmap
            }
        }
        return bitmap
    }

    /**
     * Status check. Should return false before initialisation and after recycle.
     *
     * @return true if the decoder is ready to be used.
     */
    override fun isReady(): Boolean {
        return decoder != null && !decoder!!.isRecycled
    }

    /**
     * This method will be called when the decoder is no longer required. It should clean up any
     * resources still in use.
     */
    override fun recycle() {
        decoder?.recycle()
    }
}

private val TAG = Decoder::class.java.simpleName
