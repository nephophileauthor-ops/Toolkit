package com.apidebug.inspector.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import javax.net.SocketFactory

fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

class VpnSocketProtector : SocketProtector {
    @Volatile
    private var delegate: SocketProtector = SocketProtector { true }

    fun install(protector: SocketProtector?) {
        delegate = protector ?: SocketProtector { true }
    }

    override fun protect(socket: Socket): Boolean = delegate.protect(socket)
}

class ProtectedSocketFactory(
    private val socketProtector: SocketProtector,
    private val delegate: SocketFactory = SocketFactory.getDefault()
) : SocketFactory() {

    override fun createSocket(): Socket = delegate.createSocket().protectOrThrow()

    override fun createSocket(host: String, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket {
        return createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
    }

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket {
        return createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
        }
    }

    private fun Socket.protectOrThrow(): Socket {
        if (socketProtector.protect(this)) {
            return this
        }

        runCatching { close() }
        throw SocketException("VpnService.protect(socket) failed for OkHttp upstream socket")
    }
}
