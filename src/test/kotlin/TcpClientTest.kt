package org.sz

import kotlin.test.Test
import kotlin.test.assertContentEquals

class TcpClientTest {
    @Test
    fun testAES() {
        val aesKey = ByteArray(32) { pos -> pos.toByte() }
        val aesNonce = ByteArray(12) { pos -> pos.toByte() }
        val encrypted = TcpClient.encode(aesKey, aesKey, aesNonce)
        assertContentEquals(encrypted, byteArrayOf(71, 3, -44, 24, -63, -32, -60, 28, -123, 72, -99, -128, -67, -28, 118, 98,
            -109, -57, -107, 39, -28, 110, 73, 107, 32, 126, -1, -98, 1, 116, 30, -83, 94, -35, -36, 80, 116, 4, 78, 34,
            -126, -76, 50, -77, -14, -40, -10, 115))
        val decryped = TcpClient.decode(encrypted, aesKey, aesNonce)
        assertContentEquals(decryped, aesKey)
    }
}