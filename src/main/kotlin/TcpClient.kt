package org.sz

class TcpClient {
    companion object {
        @OptIn(ExperimentalStdlibApi::class)
        fun send(parameters: ConfigurationParameters, data: ByteArray): ByteArray {
            println(data.toHexString())
            return byteArrayOf(
                0,//success
                2,0, //length
                1,0, // account id
                2,0,0,0,0,0,0,0, //summa
                2,0, // account id
                3,0,0,0,0,0,0,0, //summa
                1, 0, // length
                26, 2, 33, 1) // 20120102
        }
    }
}