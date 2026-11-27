package app.qitou.bridge

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Preserve the bike clock when present; synthesize phone time only for an empty request. */
object TimeAck {
    private const val HEADER = 16
    private const val LENGTH = 29
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'000000'", Locale.US)

    fun ack(request: ByteArray): ByteArray {
        val out = ByteArray(maxOf(45, request.size))
        request.copyInto(out, endIndex = minOf(request.size, out.size))
        val stamp = if (request.size >= HEADER + LENGTH) {
            String(request, HEADER, LENGTH, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        } else {
            ""
        }
        if (stamp.isBlank() || stamp.startsWith("1970") || stamp.startsWith("0000")) {
            val header = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(0, -2)
            header.putInt(4, 0)
            header.putInt(8, 1)
            header.putInt(12, 0)
            val text = format.format(Date()).toByteArray(Charsets.US_ASCII)
            text.copyInto(out, HEADER, endIndex = minOf(text.size, LENGTH))
        }
        return out
    }
}
