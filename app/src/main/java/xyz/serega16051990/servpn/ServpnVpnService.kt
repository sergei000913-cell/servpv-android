package xyz.serega16051990.servpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

class ServpnVpnService : VpnService() {
    companion object {
        var isRunning = false
        var activeNodeName = ""
        const val ACTION_START = "START_VPN"
        const val ACTION_STOP = "STOP_VPN"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var coreController: CoreController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        val rawConfig = intent?.getStringExtra("config") ?: return START_NOT_STICKY
        activeNodeName = intent.getStringExtra("node") ?: "SERVPS"
        startVpn(rawConfig)
        return START_STICKY
    }

    private fun startVpn(rawConfig: String) {
        if (isRunning) stopVpn()
        try {
            val builder = Builder()
                .setSession("SERVPS")
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
            try { builder.addDisallowedApplication(packageName) } catch (_: Exception) {}
            vpnInterface = builder.establish() ?: return
            val tunFd = vpnInterface!!.fd
            val finalConfig = rawConfig.replace("__TUN_FD__", tunFd.toString())

            Libv2ray.initCoreEnv(filesDir.absolutePath, "")
            coreController = Libv2ray.newCoreController(object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(p0: Long, p1: String?): Long = 0
            })
            coreController?.startLoop(finalConfig, tunFd)
            isRunning = true
        } catch (_: Exception) {
            stopVpn()
        }
    }

    private fun stopVpn() {
        try { coreController?.stopLoop() } catch (_: Exception) {}
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        coreController = null
        isRunning = false
        activeNodeName = ""
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
