package xyz.serega16051990.servpn

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

object VlessParser {
    fun extractFirstVless(subBody: String): Pair<String, String>? {
        val raw = try {
            val decoded = String(Base64.decode(subBody.trim(), Base64.DEFAULT))
            if (decoded.contains("vless://")) decoded else subBody
        } catch (_: Exception) { subBody }

        val line = raw.lines().map { it.trim() }.firstOrNull { it.startsWith("vless://") } ?: return null
        val name = if ("#" in line) URLDecoder.decode(line.substringAfter("#"), "UTF-8") else "SERVPS"
        return Pair(name, buildXrayJson(line))
    }

    fun buildXrayJson(vlessUri: String): String {
        val clean = vlessUri.substringBefore("#")
        val uri = Uri.parse(clean)
        val uuid = uri.userInfo ?: ""
        val host = uri.host ?: ""
        val port = if (uri.port > 0) uri.port else 443
        fun q(k: String, def: String = "") = uri.getQueryParameter(k) ?: def

        val net = q("type", "tcp")
        val sec = q("security", "none")
        val sni = q("sni", host)
        val fp = q("fp", "chrome")
        val pbk = q("pbk")
        val sid = q("sid")
        val flow = q("flow")
        val path = q("path", "/")
        val hostHdr = q("host", host)

        val userObj = JSONObject().put("id", uuid).put("encryption", "none")
        if (flow.isNotEmpty()) userObj.put("flow", flow)

        val stream = JSONObject().put("network", net).put("security", sec)
        if (sec == "reality") {
            stream.put("realitySettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", fp)
                .put("publicKey", pbk)
                .put("shortId", sid))
        } else if (sec == "tls") {
            stream.put("tlsSettings", JSONObject()
                .put("serverName", sni)
                .put("fingerprint", fp))
        }
        if (net == "xhttp" || net == "splithttp") {
            stream.put("xhttpSettings", JSONObject().put("path", path).put("host", hostHdr))
        } else if (net == "ws") {
            stream.put("wsSettings", JSONObject().put("path", path).put("headers", JSONObject().put("Host", hostHdr)))
        } else if (net == "grpc") {
            stream.put("grpcSettings", JSONObject().put("serviceName", q("serviceName")))
        }

        val proxyOutbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(userObj))
            )))
            .put("streamSettings", stream)

        val tunInbound = JSONObject()
            .put("tag", "tun-in")
            .put("protocol", "tun")
            .put("settings", JSONObject().put("fd", "__TUN_FD__").put("mtu", 1500))
            .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("http").put("tls")))

        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(tunInbound))
            .put("outbounds", JSONArray()
                .put(proxyOutbound)
                .put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            )
        return root.toString().replace("\"__TUN_FD__\"", "__TUN_FD__")
    }
}
