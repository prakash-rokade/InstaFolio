package com.prakash.instafolio.core.audio

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Calendar

object Id3TagHelper {

    /**
     * Embeds ID3v2.3 metadata (Title, Artist, Album, Year, Cover Art) into MP3 bytes.
     */
    fun wrapMp3WithId3(
        rawAudioBytes: ByteArray,
        title: String?,
        artist: String?,
        albumName: String = "Instagram Audio",
        albumArtBytes: ByteArray? = null
    ): ByteArray {
        val id3HeaderAndFrames = buildId3Tag(title, artist, albumName, albumArtBytes)
        if (id3HeaderAndFrames.isEmpty()) return rawAudioBytes

        // If the audio already has an ID3v2 tag, we can skip or replace it
        var audioOffset = 0
        if (rawAudioBytes.size >= 10 &&
            rawAudioBytes[0] == 'I'.code.toByte() &&
            rawAudioBytes[1] == 'D'.code.toByte() &&
            rawAudioBytes[2] == '3'.code.toByte()
        ) {
            val existingTagSize = decodeSynchsafe(
                rawAudioBytes[6], rawAudioBytes[7], rawAudioBytes[8], rawAudioBytes[9]
            ) + 10
            if (existingTagSize in 10..rawAudioBytes.size) {
                audioOffset = existingTagSize
            }
        }

        val out = ByteArrayOutputStream(id3HeaderAndFrames.size + (rawAudioBytes.size - audioOffset))
        out.write(id3HeaderAndFrames)
        out.write(rawAudioBytes, audioOffset, rawAudioBytes.size - audioOffset)
        return out.toByteArray()
    }

    /**
     * Streams ID3 header + audio bytes directly to an OutputStream.
     */
    fun writeTaggedMp3(
        rawAudioBytes: ByteArray,
        out: OutputStream,
        title: String?,
        artist: String?,
        albumName: String = "Instagram Audio",
        albumArtBytes: ByteArray? = null
    ) {
        val tagged = wrapMp3WithId3(rawAudioBytes, title, artist, albumName, albumArtBytes)
        out.write(tagged)
    }

    private fun buildId3Tag(
        title: String?,
        artist: String?,
        albumName: String,
        albumArtBytes: ByteArray?
    ): ByteArray {
        val framesOut = ByteArrayOutputStream()

        title?.takeIf { it.isNotBlank() }?.let {
            writeTextFrame(framesOut, "TIT2", it.trim())
        }

        artist?.takeIf { it.isNotBlank() }?.let {
            writeTextFrame(framesOut, "TPE1", it.trim())
        }

        if (albumName.isNotBlank()) {
            writeTextFrame(framesOut, "TALB", albumName.trim())
        }

        val year = Calendar.getInstance().get(Calendar.YEAR).toString()
        writeTextFrame(framesOut, "TYER", year)
        writeTextFrame(framesOut, "TCON", "Audio")

        // Cover Artwork frame (APIC)
        if (albumArtBytes != null && albumArtBytes.isNotEmpty()) {
            writeApicFrame(framesOut, albumArtBytes)
        }

        val framesBytes = framesOut.toByteArray()
        if (framesBytes.isEmpty()) return ByteArray(0)

        // ID3v2.3 header (10 bytes)
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = 3 // ID3v2.3
        header[4] = 0 // Revision
        header[5] = 0 // Flags

        // Size is encoded as 4 synchsafe integers (7 bits per byte)
        val synchsafeSize = encodeSynchsafe(framesBytes.size)
        header[6] = synchsafeSize[0]
        header[7] = synchsafeSize[1]
        header[8] = synchsafeSize[2]
        header[9] = synchsafeSize[3]

        val totalTag = ByteArrayOutputStream(10 + framesBytes.size)
        totalTag.write(header)
        totalTag.write(framesBytes)
        return totalTag.toByteArray()
    }

    private fun writeTextFrame(out: ByteArrayOutputStream, frameId: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        // 1 byte encoding flag (0x03 = UTF-8 in ID3v2.3/2.4)
        val payloadSize = 1 + textBytes.size

        out.write(frameId.toByteArray(StandardCharsets.ISO_8859_1), 0, 4)
        // Frame size (4 bytes, standard integer in ID3v2.3)
        out.write((payloadSize shr 24) and 0xFF)
        out.write((payloadSize shr 16) and 0xFF)
        out.write((payloadSize shr 8) and 0xFF)
        out.write(payloadSize and 0xFF)
        // Flags (2 bytes: 0x00 0x00)
        out.write(0)
        out.write(0)
        // Payload
        out.write(3) // UTF-8 encoding
        out.write(textBytes)
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, imageBytes: ByteArray) {
        val mime = if (imageBytes.size >= 8 && imageBytes[0] == 0x89.toByte() && imageBytes[1] == 'P'.code.toByte()) {
            "image/png"
        } else {
            "image/jpeg"
        }
        val mimeBytes = mime.toByteArray(StandardCharsets.ISO_8859_1)

        // APIC payload: 1 byte encoding (0) + mime + 0x00 + 1 byte picture type (3 = Cover Front) + 0x00 (description terminator) + imageBytes
        val payloadSize = 1 + mimeBytes.size + 1 + 1 + 1 + imageBytes.size

        out.write("APIC".toByteArray(StandardCharsets.ISO_8859_1), 0, 4)
        out.write((payloadSize shr 24) and 0xFF)
        out.write((payloadSize shr 16) and 0xFF)
        out.write((payloadSize shr 8) and 0xFF)
        out.write(payloadSize and 0xFF)
        out.write(0)
        out.write(0)

        out.write(0) // ISO-8859-1 for mime/description
        out.write(mimeBytes)
        out.write(0) // null terminator for mime
        out.write(3) // Cover (front)
        out.write(0) // null terminator for empty description
        out.write(imageBytes)
    }

    private fun encodeSynchsafe(size: Int): ByteArray {
        val res = ByteArray(4)
        res[0] = ((size shr 21) and 0x7F).toByte()
        res[1] = ((size shr 14) and 0x7F).toByte()
        res[2] = ((size shr 7) and 0x7F).toByte()
        res[3] = (size and 0x7F).toByte()
        return res
    }

    private fun decodeSynchsafe(b0: Byte, b1: Byte, b2: Byte, b3: Byte): Int {
        return ((b0.toInt() and 0x7F) shl 21) or
                ((b1.toInt() and 0x7F) shl 14) or
                ((b2.toInt() and 0x7F) shl 7) or
                (b3.toInt() and 0x7F)
    }
}
