package org.sz

import java.net.Socket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource.PSpecified
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class TcpClient(rsaKey: String, label: String, private val aesKey: ByteArray?, private val server: String,
    private val port: Int) {
    companion object {
        private const val TAG_LENGTH_BIT = 128

        private fun getRSAKey(contents: String): String {
            return contents.split('\n').filter { !it.startsWith("----") }.joinToString(separator = "")
        }

        private fun decode(data: ByteArray?, aesKey: ByteArray, aesNonce: ByteArray): ByteArray {
            if (data == null) {
                throw ResponseException("null response")
            }
            val key = SecretKeySpec(aesKey, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(Companion.TAG_LENGTH_BIT, aesNonce))
            return cipher.doFinal(data)
        }
    }

    private val cipher: Cipher

    init {
        val keyString = getRSAKey(rsaKey)
        val decoded = Base64.getDecoder().decode(keyString)
        val spec = X509EncodedKeySpec(decoded)
        val kf = KeyFactory.getInstance("RSA")
        val key = kf.generatePublic(spec)
        cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        val pspec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256,
            PSpecified(label.toByteArray())
        )
        cipher.init(Cipher.ENCRYPT_MODE, key, pspec)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun send(data: ByteArray): ByteArray {
        val hexRequestString = data.toHexString()
        println("Request: $hexRequestString")
        val aesKey = Random.Default.nextBytes(32)
        val aesNonce = Random.nextBytes(12)
        val encoded = encode(data, aesKey, aesNonce)
        Socket(server, port).use { socket ->
            socket.getOutputStream().use { ostream ->
                ostream.write(encoded)
                ostream.flush()
                socket.getInputStream().use { istream ->
                    val response = decode(istream.readAllBytes(), aesKey, aesNonce)
                    if (response.size <= 32) {
                        throw ResponseException("too short response")
                    }
                    val md = MessageDigest.getInstance("SHA-256")
                    val responseData = response.copyOfRange(0, response.size-32)
                    val responseHash = response.copyOfRange(response.size-32, response.size)
                    val digest = md.digest(responseData)
                    if (!digest.contentEquals(responseHash)) {
                        throw ResponseException("incorrect response hash")
                    }
                    return responseData
                }
            }
        }
    }

    private fun encode(data: ByteArray, aesKey2: ByteArray, aesNonce: ByteArray): ByteArray {
        var request = aesKey2 + aesNonce
        if (aesKey != null) {
            request += aesKey
        }
        request += data
        return cipher.doFinal(request)
    }
}