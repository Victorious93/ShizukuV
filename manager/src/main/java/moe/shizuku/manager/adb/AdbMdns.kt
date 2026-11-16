package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    /** Called once if discovery itself cannot start (NSD error). Lets callers fail fast instead of waiting out a timeout. */
    private val onError: ((String) -> Unit)? = null,
    // Kept last so existing `AdbMdns(ctx, type) { port -> ... }` trailing-lambda callers still bind to the observer.
    private val observer: Observer<Int>,
) {

    private val lock = Any()

    // Victorious: the previous implementation only stopped discovery if onDiscoveryStarted had already fired. A stop() that
    // raced the (asynchronous) start callback left the DiscoveryListener registered forever. These flags make stop()
    // correct in every interleaving: [startRequested] = discoverServices() was called and not yet stopped,
    // [discoveryActive] = the platform confirmed it started.
    private var startRequested = false
    private var discoveryActive = false
    private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        synchronized(lock) {
            if (startRequested) return
            startRequested = true
        }
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Throwable) {
            synchronized(lock) { startRequested = false }
            onError?.invoke("discoverServices failed: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        val stopNow: Boolean
        synchronized(lock) {
            if (!startRequested) return
            startRequested = false
            stopNow = discoveryActive
        }
        // If the platform has not confirmed the start yet, onDiscoveryStart() sees startRequested == false and stops it.
        if (stopNow) stopPlatformDiscovery()
    }

    private fun stopPlatformDiscovery() {
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Throwable) {
            Log.w(TAG, "stopServiceDiscovery: ${e.javaClass.simpleName}")
        }
    }

    private fun onDiscoveryStart() {
        val shouldStop: Boolean
        synchronized(lock) {
            discoveryActive = true
            shouldStop = !startRequested
        }
        if (shouldStop) stopPlatformDiscovery()
    }

    private fun onDiscoveryStop() {
        synchronized(lock) { discoveryActive = false }
    }

    private fun onDiscoveryFailed(errorCode: Int) {
        synchronized(lock) { startRequested = false; discoveryActive = false }
        onError?.invoke("discovery failed ($errorCode)")
    }

    private val running: Boolean get() = synchronized(lock) { startRequested }

    private fun onServiceFound(info: NsdServiceInfo) {
        nsdManager.resolveService(info, ResolveListener(this))
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        if (running && NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .any { networkInterface ->
                    networkInterface.inetAddresses
                        .asSequence()
                        .any { resolvedService.host.hostAddress == it.hostAddress }
                }
            && isPortAvailable(resolvedService.port)
        ) {
            serviceName = resolvedService.serviceName
            observer.onChanged(resolvedService.port)
        }
    }

    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onDiscoveryFailed(errorCode)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {}

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }

    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
