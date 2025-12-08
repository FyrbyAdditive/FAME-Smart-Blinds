package com.fyrbyadditive.famesmartblinds.util

/**
 * CRC32 calculation for OTA firmware upload
 */
object CRC32 {
    private val table: LongArray = LongArray(256) { i ->
        var crc = i.toLong()
        repeat(8) {
            crc = if ((crc and 1L) == 1L) {
                0xEDB88320L xor (crc shr 1)
            } else {
                crc shr 1
            }
        }
        crc
    }

    /**
     * Calculate CRC32 checksum for byte array
     */
    fun calculate(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            val index = ((crc xor byte.toLong()) and 0xFFL).toInt()
            crc = table[index] xor (crc shr 8)
        }
        return crc xor 0xFFFFFFFFL
    }

    /**
     * Format CRC32 as hex string (8 characters)
     */
    fun toHexString(crc: Long): String {
        return String.format("%08x", crc and 0xFFFFFFFFL)
    }
}
