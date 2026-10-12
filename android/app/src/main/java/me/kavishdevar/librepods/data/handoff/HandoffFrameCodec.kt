package me.kavishdevar.librepods.data.handoff

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class HandoffFrameDecoder {
    private var pending = ByteArray(0)

    fun append(fragment: ByteArray): List<String> {
        if (fragment.isEmpty()) return emptyList()
        require(pending.size + fragment.size <= MAX_FRAME_BYTES + PREFIX_BYTES) {
            "inbound buffer overflow"
        }
        pending += fragment
        val frames = mutableListOf<String>()
        while (pending.size >= PREFIX_BYTES) {
            val length = ((pending[0].toInt() and 0xff) shl 8) or
                (pending[1].toInt() and 0xff)
            require(length in 1..MAX_FRAME_BYTES) { "invalid frame length" }
            if (pending.size < PREFIX_BYTES + length) break
            val bytes = pending.copyOfRange(PREFIX_BYTES, PREFIX_BYTES + length)
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            frames += decoder.decode(ByteBuffer.wrap(bytes)).toString()
            pending = pending.copyOfRange(PREFIX_BYTES + length, pending.size)
        }
        require(pending.size <= MAX_FRAME_BYTES + PREFIX_BYTES) { "oversized frame" }
        return frames
    }

    fun reset() {
        pending = ByteArray(0)
    }

    companion object {
        const val MAX_FRAME_BYTES = 8192
        private const val PREFIX_BYTES = 2

        fun encode(json: String): ByteArray {
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            require(bytes.size in 1..MAX_FRAME_BYTES)
            return ByteBuffer.allocate(PREFIX_BYTES + bytes.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort(bytes.size.toShort())
                .put(bytes)
                .array()
        }
    }
}
