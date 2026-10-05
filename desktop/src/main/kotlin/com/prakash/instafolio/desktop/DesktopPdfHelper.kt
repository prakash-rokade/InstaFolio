package com.prakash.instafolio.desktop

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.ByteArrayInputStream
import java.io.OutputStream
import javax.imageio.ImageIO

object DesktopPdfHelper {

    /**
     * Compiles a list of raw image byte arrays into a single multi-page PDF document.
     * Each image is rendered on its own dedicated page matching the image dimensions.
     */
    fun createPdfFromImages(imageBytesList: List<ByteArray>, output: OutputStream) {
        val document = PDDocument()
        try {
            var validPages = 0
            for (bytes in imageBytesList) {
                val bufferedImage = ImageIO.read(ByteArrayInputStream(bytes)) ?: continue
                val width = bufferedImage.width.toFloat()
                val height = bufferedImage.height.toFloat()

                val page = PDPage(PDRectangle(width, height))
                document.addPage(page)

                val pdImage = try {
                    JPEGFactory.createFromImage(document, bufferedImage)
                } catch (e: Exception) {
                    LosslessFactory.createFromImage(document, bufferedImage)
                }

                PDPageContentStream(document, page).use { contentStream ->
                    contentStream.drawImage(pdImage, 0f, 0f, width, height)
                }
                validPages++
            }

            if (validPages == 0) {
                throw IllegalStateException("No valid images could be decoded to create PDF")
            }

            document.save(output)
        } finally {
            document.close()
        }
    }
}
