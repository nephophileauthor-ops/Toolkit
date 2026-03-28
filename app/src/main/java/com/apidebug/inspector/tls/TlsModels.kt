package com.apidebug.inspector.tls

data class DeveloperCaState(
    val ready: Boolean = false,
    val commonName: String = "API Debug Inspector Root CA",
    val certificatePath: String = "",
    val lastIssuedHost: String = "",
    val lastError: String? = null
)
