package com.vexora.app.media

import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import com.github.penfeizhou.animation.loader.ByteBufferLoader
import com.github.penfeizhou.animation.webp.WebPDrawable
import java.nio.ByteBuffer
import okio.ByteString.Companion.encodeUtf8

/** API 24–27 没有系统动态图解码器，使用同一 Coil 请求生命周期管理 WebP 动画。 */
class LegacyWebpDecoder(private val result: SourceResult) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = result.source.source().readByteArray()
        val drawable = WebPDrawable(object : ByteBufferLoader() {
            override fun getByteBuffer(): ByteBuffer = ByteBuffer.wrap(bytes)
        }).apply { setAutoPlay(false) }
        return DecodeResult(drawable, isSampled = false)
    }
    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val source = result.source.source()
            val animated = source.request(21) && source.rangeEquals(0, "RIFF".encodeUtf8()) &&
                source.rangeEquals(8, "WEBP".encodeUtf8()) && source.rangeEquals(12, "VP8X".encodeUtf8()) &&
                (source.buffer[20].toInt() and 2) != 0
            return if (animated) LegacyWebpDecoder(result) else null
        }
    }
}
