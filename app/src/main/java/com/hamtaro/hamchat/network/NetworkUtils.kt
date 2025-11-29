package com.hamtaro.hamchat.network

import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Utilidades de red para HamChat
 * Incluye descubrimiento de IP local y verificación de servidores
 */
object NetworkUtils {

    private const val TAG = "NetworkUtils"
    private const val DEFAULT_PORT = 8080
    private const val SCAN_TIMEOUT_MS = 500

    /**
     * Obtiene la dirección IP local del dispositivo
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP", e)
        }
        return null
    }

    /**
     * Obtiene la URL completa del servidor local
     */
    fun getLocalServerUrl(port: Int = DEFAULT_PORT): String {
        val ip = getLocalIpAddress() ?: "localhost"
        return "http://$ip:$port"
    }

    /**
     * Verifica si un servidor HamChat está disponible en la URL dada
     */
    fun checkServer(url: String, callback: (Boolean) -> Unit) {
        Thread {
            try {
                val api = HamChatApiClient.createApiForUrl(url)
                val response = api.health().execute()
                val isOk = response.isSuccessful && response.body()?.status == "ok"
                callback(isOk)
            } catch (e: Exception) {
                Log.e(TAG, "Server check failed for $url", e)
                callback(false)
            }
        }.start()
    }

    /**
     * Verifica si un puerto está abierto en una IP
     */
    fun isPortOpen(ip: String, port: Int, timeoutMs: Int = SCAN_TIMEOUT_MS): Boolean {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Escanea la red local buscando dispositivos HamChat
     * @param onPeerFound callback llamado cuando se encuentra un peer
     * @param onComplete callback llamado cuando termina el escaneo
     */
    fun scanLocalNetwork(
        port: Int = DEFAULT_PORT,
        onPeerFound: (String) -> Unit,
        onComplete: (List<String>) -> Unit
    ) {
        Thread {
            val localIp = getLocalIpAddress()
            if (localIp == null) {
                onComplete(emptyList())
                return@Thread
            }

            val subnet = localIp.substringBeforeLast(".")
            val foundPeers = mutableListOf<String>()

            Log.d(TAG, "Scanning subnet $subnet.*")

            // Escanear en paralelo usando threads
            val threads = (1..254).map { i ->
                Thread {
                    val ip = "$subnet.$i"
                    if (ip != localIp && isPortOpen(ip, port)) {
                        // Verificar que sea un servidor HamChat
                        try {
                            val api = HamChatApiClient.createApiForUrl("http://$ip:$port")
                            val response = api.health().execute()
                            if (response.isSuccessful && response.body()?.status == "ok") {
                                synchronized(foundPeers) {
                                    foundPeers.add("http://$ip:$port")
                                }
                                onPeerFound("http://$ip:$port")
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            threads.forEach { it.start() }
            threads.forEach { it.join() }

            Log.d(TAG, "Scan complete. Found ${foundPeers.size} peers")
            onComplete(foundPeers)
        }.start()
    }
}
