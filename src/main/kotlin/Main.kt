package org.sz

fun main(args: Array<String>) {
    if (args.size < 2) {
        usage()
        return
    }

    val configuration = Configuration.load(args[0])
    configuration.runRequest(args[1], args.drop(2))
}

fun usage() {
    println("Usage: java -jar time_series_data_client.jar config_file_name requestId [parameters]")
}