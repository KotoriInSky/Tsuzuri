package io.github.kotoriinsky.tsuzuri.core

/** JPEG + XMP + MP4 的公开 Motion Photo 容器；具体相册的识别结果需实机验证。 */
object PortalMotionPhoto {
    /** 只读取 JPEG APP1 中的公开实况元数据；媒体字节校验由读取侧完成。 */
    fun embeddedVideoLength(header: ByteArray, fileLength: Long): Long? {
        if (header.size < 4 || header[0] != (-1).toByte() || header[1] != (-40).toByte()) return null
        val namespace = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        var cursor = 2
        while (cursor + 4 <= header.size) {
            if (header[cursor].toInt() and 255 != 255) return null
            val marker = header[cursor + 1].toInt() and 255
            if (marker == 0xDA || marker == 0xD9) return null
            if (marker == 0xFF) { cursor++; continue }
            if (marker == 0x01 || marker in 0xD0..0xD8) { cursor += 2; continue }
            val length = ((header[cursor + 2].toInt() and 255) shl 8) or (header[cursor + 3].toInt() and 255)
            val end = cursor + 2 + length
            if (length < 2 || end > header.size) return null
            val payload = cursor + 4
            if (marker == 0xE1 && end - payload >= namespace.size &&
                namespace.indices.all { header[payload + it] == namespace[it] }) {
                val xml = header.copyOfRange(payload + namespace.size, end).toString(Charsets.UTF_8)
                fun attr(text: String, name: String): String? =
                    Regex("(?:[A-Za-z_][\\w.-]*:)?$name\\s*=\\s*[\"']([^\"']+)[\"']").find(text)?.groupValues?.get(1)
                if ("http://ns.google.com/photos/1.0/camera/" !in xml ||
                    (attr(xml, "MotionPhoto") != "1" && attr(xml, "MicroVideo") != "1")) return null
                val legacy = attr(xml, "MicroVideoOffset")?.toLongOrNull()
                val item = Regex("<[\\w:.-]+\\b[^>]*>").findAll(xml).map { it.value }.firstOrNull {
                    attr(it, "Mime") == "video/mp4" && attr(it, "Semantic") == "MotionPhoto"
                }
                if (item != null && attr(item, "Padding") !in listOf(null, "0")) return null
                val directory = item?.let { attr(it, "Length")?.toLongOrNull() }
                if (legacy != null && directory != null && legacy != directory) return null
                return (directory ?: legacy)?.takeIf { it > 0 && it < fileLength - end }
            }
            cursor = end
        }
        return null
    }
    fun jpegPrefix(jpeg: ByteArray, videoLength: Long, timestampUs: Long): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == (-1).toByte() && jpeg[1] == (-40).toByte())
        require(videoLength > 0 && timestampUs >= 0)
        val xmp = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
            xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
            GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1" GCamera:MotionPhotoPresentationTimestampUs="$timestampUs"
            GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="$videoLength" GCamera:MicroVideoPresentationTimestampUs="$timestampUs">
            <Container:Directory><rdf:Seq><rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Padding="0"/></rdf:li>
            <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoLength" Item:Padding="0"/></rdf:li></rdf:Seq></Container:Directory>
            </rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        val payload = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII) + xmp.toByteArray()
        val length = payload.size + 2
        require(length <= 65535)
        val app1 = byteArrayOf(-1, -31, (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }
}
