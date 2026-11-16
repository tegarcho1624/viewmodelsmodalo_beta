package com.alo.xxmiviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer

/** Carga una textura segun su extension: DDS con el decodificador propio, PNG/JPG con BitmapFactory. */
object Images {
    fun load(bytes: ByteArray, path: String, maxSize: Int): Img {
        if (path.endsWith(".dds", ignoreCase = true)) return Dds.decode(bytes, maxSize)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSize) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("Imagen no soportada: ${path.substringAfterLast('/')}")
        val buf = ByteBuffer.allocate(bmp.width * bmp.height * 4)
        bmp.copyPixelsToBuffer(buf)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        return Img(w, h, buf.array(), true)
    }
}
