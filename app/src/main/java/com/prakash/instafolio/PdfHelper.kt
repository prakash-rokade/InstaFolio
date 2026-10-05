package com.prakash.instafolio

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

object PdfHelper {

    /**
     * Compiles a list of raw image byte arrays into a single multi-page PDF document.
     * Each image is rendered on its own page using its native pixel dimensions for maximum fidelity.
     *
     * @param imageBytesList List of raw bytes for each image in order.
     * @param output Destination stream to write the generated PDF.
     */
    fun createPdfFromImages(imageBytesList: List<ByteArray>, output: OutputStream) {
        val document = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        try {
            var validPages = 0
            for (bytes in imageBytesList) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, validPages + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawBitmap(bitmap, 0f, 0f, paint)
                document.finishPage(page)
                bitmap.recycle()
                validPages++
            }

            if (validPages == 0) {
                throw IllegalStateException("No valid images could be decoded to create PDF")
            }

            document.writeTo(output)
        } finally {
            document.close()
        }
    }
}
