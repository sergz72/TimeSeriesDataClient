package org.sz

import kotlinx.serialization.Serializable
import com.charleskorn.kaml.Yaml
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths

@Serializable
data class ConfigurationDataType(
    val type: String,
    val position: Int = 1,
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
        for (property in properties.map { Pair(it.key, it.value) }.sortedBy { it.second.position }) {
            result += property.second.serialize(property.first, fullName, requestParameters)
        }
        return result
    }

    fun deserialize(stream: ByteBuffer, dataTypes: Map<String, ConfigurationDataType>): ResponseValue {
        val value = deserializeThis(stream, dataTypes)
        val values = properties
            .map { Pair(it.key, it.value) }
            .sortedBy { it.second.position }
            .associate { it.first to it.second.deserialize(stream, dataTypes) }
        return ResponseValue(value.value, value.items, value.values + values)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun deserializeThis(stream: ByteBuffer, dataTypes: Map<String, ConfigurationDataType>): ResponseValue {
        return when (type) {
            "int8" -> ResponseValue(stream.get().toHexString(), arrayOf(), mapOf())
            "uint8" -> ResponseValue(stream.get().toUByte().toHexString(), arrayOf(), mapOf())
            "int16" -> ResponseValue(stream.getShort().toHexString(), arrayOf(), mapOf())
            "uint16" -> ResponseValue(stream.getShort().toUShort().toHexString(), arrayOf(), mapOf())
            "int32" -> ResponseValue(stream.getInt().toHexString(), arrayOf(), mapOf())
            "uint32" -> ResponseValue(stream.getInt().toUInt().toHexString(), arrayOf(), mapOf())
            "int64" -> ResponseValue(stream.getLong().toHexString(), arrayOf(), mapOf())
            "uint64" -> ResponseValue(stream.getLong().toULong().toHexString(), arrayOf(), mapOf())
            "string" -> ResponseValue(Configuration.readStringFromByteBuffer(stream), arrayOf(), mapOf())
            "object" -> ResponseValue(null, arrayOf(), mapOf())
            "array" -> ResponseValue(null, deserializeArray(stream, dataTypes), mapOf())
            else -> {
                val dataType = dataTypes[type] ?: throw IllegalArgumentException("unknown data type $type")
                return dataType.deserialize(stream, dataTypes)
            }
        }
    }

    private fun deserializeArray(stream: ByteBuffer, dataTypes: Map<String, ConfigurationDataType>): Array<ResponseValue> {
        if (items == null) {
            throw ResponseException("null items type")
        }
        val l = stream.getShort().toUShort().toInt()
        return (0..<l).map { items.deserialize(stream, dataTypes) }.toTypedArray()
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
}

data class ResponseValue(val value: String?, val items: Array<ResponseValue>, val values: Map<String, ResponseValue>) {
    fun print(name: String, level: Int = 0) {
        val spacesBuilder = StringBuilder()
        repeat(level) { spacesBuilder.append(' ') }
        val spaces = spacesBuilder.toString()
        if (name.isNotEmpty()) {
            println("$spaces$name:")
        }
        if (value != null) {
            println("$spaces  $value")
        } else if (items.isNotEmpty()) {
            for ((n, item) in items.withIndex()) {
                item.print("Item $n", level + 2)
            }
        } else {
            for (v in values) {
                v.value.print(v.key, level + 2)
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
        decodeResponse(dataTypes, response).print("")
    }

    private fun decodeResponse(dataTypes: Map<String, ConfigurationDataType>, responseData: ByteArray): ResponseValue {
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
        val stream = ByteBuffer.wrap(responseData).order(ByteOrder.LITTLE_ENDIAN)
        if (stream.get().toInt() == 0) {
            println("successful response")
            if (response != null) {
                val dataType = dataTypes[response] ?: throw IllegalArgumentException("null data type")
                val v = dataType.deserialize(stream, dataTypes)
                if (stream.hasRemaining()) {
                    throw ResponseException("response size mismatch")
                }
                return v
            }
        } else {
            val s = Configuration.readStringFromByteBuffer(stream)
            if (stream.hasRemaining()) {
                throw ResponseException("response size mismatch")
            }
            println("error response $s")
        }
        return ResponseValue(null, arrayOf(), mapOf())
    }
}

@Serializable
data class ConfigurationParameters(
    val server: String,
    val port: Int,
    val aesKeyFile: String? = null,
    val rsaKeyFile: String,
    val label: String
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

        internal fun readStringFromByteBuffer(buffer: ByteBuffer): String {
            val l = buffer.getShort().toUShort().toInt()
            if (buffer.remaining() < l) {
                throw ResponseException("incorrect string length")
            }
            val bytes = ByteArray(l)
            buffer.get(bytes, 0, l)
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
        val server = buildValue("server", parametersMap, parameters.server, false)!!
        val port = buildValue("port", parametersMap, parameters.port.toString(), false)!!.toInt()
        val label = buildValue("label", parametersMap, parameters.label, false)!!
        val aesKeyFile = buildValue("aesKeyFile", parametersMap, parameters.aesKeyFile, true)
        val rsaKeyFile = buildValue("rsaKeyFile", parametersMap, parameters.rsaKeyFile, false)!!
        val aesKey = aesKeyFile?.let { Files.readAllBytes(Paths.get(it)) }
        val rsaKey = Files.readString(Paths.get(rsaKeyFile))
        val client = TcpClient(rsaKey, label, aesKey, server, port)
        val response = client.send(data)
        command.printResponse(dataTypes, response)
    }
}