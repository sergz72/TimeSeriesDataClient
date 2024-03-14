package org.sz

import kotlinx.serialization.Serializable
import com.charleskorn.kaml.Yaml
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths

@Serializable
data class ConfigurationDataType(
    val type: String,
    val defaultValue: String? = null,
    val items: ConfigurationDataType? = null,
    val properties: Map<String, ConfigurationDataType> = mapOf()
) {
    fun serialize(name: String, prefix: String, requestParameters: Map<String, String>): ByteArray {
        val fullName = buildFullName(name, prefix)
        var result = if (fullName == "") {
            byteArrayOf()
        } else {
            val value = Configuration.buildValue(fullName, requestParameters, defaultValue, false)!!
            translateValue(fullName, value)
        }
        for (property in properties) {
            result += property.value.serialize(property.key, fullName, requestParameters)
        }
        return result
    }

    private fun translateValue(name: String, value: String): ByteArray {
        return when (type) {
            "int8" -> byteArrayOf(Configuration.buildInteger(name, value, 1, true).toByte())
            "uint8" -> byteArrayOf(Configuration.buildInteger(name, value, 1, false).toUByte().toByte())
            "int16" -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Configuration.buildInteger(name, value, 2, true).toShort()).array()

            "uint16" -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(Configuration.buildInteger(name, value, 2, false).toUShort().toShort()).array()

            "int32" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Configuration.buildInteger(name, value, 4, true).toInt()).array()

            "uint32" -> ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(Configuration.buildInteger(name, value, 4, false).toUInt().toInt()).array()

            "int64" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(Configuration.buildInteger(name, value, 8, true)).array()

            "uint64" -> ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(Configuration.buildInteger(name, value, 8, false)).array()

            "string" -> ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.length.toShort())
                .array() + value.toByteArray()

            "object" -> byteArrayOf()
            else -> throw IllegalArgumentException("unknown data type $type")
        }
    }

    private fun buildFullName(name: String, prefix: String): String {
        if (prefix == "") {
            return name
        }
        return "$prefix.$name"
    }

    fun decode(stream: DataInputStream): Map<String, ResponseValue> {
        val result: MutableMap<String, ResponseValue> = mutableMapOf()
        return result
    }
}

data class ResponseValue(val value: String?, val values: Map<String, ResponseValue>) {
    fun print(name: String) {
        if (value != null) {
            println("$name $value")
        } else {
            for (v in values) {
                println()
            }
        }
    }
}

class ResponseException(message: String?) : Exception(message)

@Serializable
data class ConfigurationCommand(
    val id: Byte,
    val parameters: String? = null,
    val response: String? = null
) {
    fun build(dataTypes: Map<String, ConfigurationDataType>, requestParameters: Map<String, String>): ByteArray {
        var result = byteArrayOf(id)
        if (parameters != null) {
            val dataType = dataTypes[parameters] ?: throw IllegalArgumentException("null data type")
            result += dataType.serialize("", "", requestParameters)
        }
        return result
    }

    fun printResponse(dataTypes: Map<String, ConfigurationDataType>, response: ByteArray) {
        val data = decodeResponse(dataTypes, response)
    }

    private fun decodeResponse(dataTypes: Map<String, ConfigurationDataType>, responseData: ByteArray): Map<String, ResponseValue> {
        if (responseData.isEmpty()) {
            throw ResponseException("empty response")
        }
        if (responseData.size == 1) {
            if (response != null) {
                throw ResponseException("response is too short")
            }
        } else if (response == null) {
            throw ResponseException("response is too long")
        }
        DataInputStream(ByteArrayInputStream(responseData)).use { stream ->
            stream.readByte()
            if (stream.readByte().toInt() == 0) {
                println("successful response")
                if (response != null) {
                    val dataType = dataTypes[parameters] ?: throw IllegalArgumentException("null data type")
                    return dataType.decode(stream)
                }
            } else {
                val s = Configuration.readStringFromDataStream(stream)
                println("error response $s")
            }
            return mutableMapOf()
        }
    }
}

@Serializable
data class ConfigurationParameters(
    val server: String,
    val port: Int,
    val aesKeyFile: String? = null,
    val rsaKeyFile: String
)

@Serializable
data class Configuration(
    val parameters: ConfigurationParameters,
    val commands: Map<String, ConfigurationCommand>,
    val dataTypes: Map<String, ConfigurationDataType>
) {
    companion object {
        fun load(fileName: String): Configuration {
            val contents = Files.readString(Paths.get(fileName))
            return Yaml.default.decodeFromString(serializer(), contents)
        }

        internal fun buildValue(
            name: String, requestParameters: Map<String, String>, defaultValue: String?,
            allowNullResult: Boolean
        ): String? {
            val value = requestParameters[name]
            if (value == null) {
                if (!allowNullResult && defaultValue == null) {
                    throw IllegalArgumentException("no default value for parameter $name")
                }
                return defaultValue
            }
            return value
        }

        internal fun buildInteger(name: String, value: String, bytes: Int, signed: Boolean): Long {
            val l = value.toLong()
            if (!signed && l < 0) {
                throw IllegalArgumentException("value for $name should be positive")
            }
            val shifted = l shr (bytes * 8)
            if (shifted != 0L) {
                throw IllegalArgumentException("value for $name is out of range")
            }
            return l
        }

        internal fun readStringFromDataStream(stream: DataInputStream): String {
            val l = stream.readShort().toInt()
            val bytes = stream.readNBytes(l)
            if (bytes.size != l) {
                throw ResponseException("unexpected EOF")
            }
            return String(bytes)
        }
    }

    fun runRequest(requestId: String, requestParameters: List<String>) {
        if (!commands.containsKey(requestId)) {
            println("unknown request id $requestId")
            return
        }
        val parametersMap: MutableMap<String, String> = mutableMapOf()
        for (parameter in requestParameters) {
            val parts = parameter.split('=')
            if (parts.size != 2) {
                throw IllegalArgumentException("invalid parameter: $parameter")
            }
            parametersMap[parts[0]] = parts[1]
        }
        val command = commands[requestId]!!
        val data = command.build(dataTypes, parametersMap)
        val response = TcpClient.send(
            ConfigurationParameters(
                server = buildValue("server", parametersMap, parameters.server, false)!!,
                port = buildValue("port", parametersMap, parameters.port.toString(), false)!!.toInt(),
                aesKeyFile = buildValue("aesKeyFile", parametersMap, parameters.aesKeyFile, true),
                rsaKeyFile = buildValue("rsaKeyFile", parametersMap, parameters.rsaKeyFile, false)!!
            ), data
        )
        command.printResponse(dataTypes, response)
    }
}