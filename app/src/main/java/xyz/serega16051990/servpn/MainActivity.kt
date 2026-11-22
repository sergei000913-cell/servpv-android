package xyz.serega16051990.servpn

import android.app.AlertDialog
import android.content.*
import android.net.*
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder

class MainActivity : AppCompatActivity() {
    private val baseUrl = "https://serega16051990.xyz"
    private val client = OkHttpClient()
    private var uid = ""; private var sig = ""; private var subUrl = ""; private var isPaused = false
    private var pendingCfg = ""; private var pendingNode = ""
    private val nodeList = mutableListOf<Pair<String, String>>()
    private var selectedNodeIdx = 0

    private val vpnPermLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == RESULT_OK && pendingCfg.isNotEmpty()) startVpnTunnel(pendingCfg, pendingNode)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("servpn", Context.MODE_PRIVATE)
        val authScroll = findViewById<ScrollView>(R.id.authScroll)
        val mainBox = findViewById<ScrollView>(R.id.mainContainer)
        val codeIn = findViewById<EditText>(R.id.codeInput)
        val pInfo = packageManager.getPackageInfo(packageName, 0)
        val verText = "SERVPS • Релиз v${pInfo.versionName} (сборка #${pInfo.longVersionCode})"
        findViewById<TextView>(R.id.tvAuthVersion).text = verText
        findViewById<TextView>(R.id.tvMainVersion).text = verText

        fun openCab(tok: String) {
            codeIn.clearFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
            authScroll.visibility = View.GONE; mainBox.visibility = View.VISIBLE; loadProfile(tok)
        }
        val savedTok = prefs.getString("token", "") ?: ""
        if (savedTok.isNotEmpty()) openCab(savedTok)

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val code = codeIn.text.toString().trim()
            if (code.length != 6) { toast("Введите 6 цифр из бота"); return@setOnClickListener }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType())
                    val res = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/auth").post(body).build()).execute().body?.string() ?: "{}")
                    withContext(Dispatchers.Main) {
                        if (res.optBoolean("ok")) { val t = res.getString("token"); prefs.edit().putString("token", t).apply(); openCab(t) }
                        else toast(res.optString("error", "Неверный код"))
                    }
                } catch (_: Exception) { withContext(Dispatchers.Main) { toast("Ошибка сети") } }
            }
        }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { prefs.edit().remove("token").apply(); mainBox.visibility = View.GONE; authScroll.visibility = View.VISIBLE }
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadUserData(); toast("Обновлено") }
        findViewById<Button>(R.id.btnTopup).setOnClickListener { if (uid.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/web-pay?u=$uid&s=$sig"))) }
        findViewById<Button>(R.id.btnFullWeb).setOnClickListener { if (uid.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/cabinet?u=$uid&s=$sig"))) }
        val btnNode = findViewById<Button>(R.id.btnSelectNode)
        btnNode.setOnClickListener {
            if (nodeList.isEmpty()) { toast("Список серверов загружается..."); return@setOnClickListener }
            AlertDialog.Builder(this).setTitle("Выберите страну / сервер").setItems(nodeList.map { it.first }.toTypedArray()) { _, which ->
                selectedNodeIdx = which; btnNode.text = "🌍 Сервер: ${nodeList[which].first}"
            }.show()
        }
        val btnConn = findViewById<Button>(R.id.btnConnect)
        if (ServpnVpnService.isRunning) { btnConn.text = "🟢 Отключить VPN (${ServpnVpnService.activeNodeName})"; btnConn.setBackgroundColor(0xFF16A34A.toInt()) }
        btnConn.setOnClickListener {
            if (ServpnVpnService.isRunning) {
                startService(Intent(this, ServpnVpnService::class.java).apply { action = ServpnVpnService.ACTION_STOP })
                btnConn.text = "🔑 Подключить в 1 клик"; btnConn.setBackgroundColor(0xFFF59E0B.toInt()); toast("VPN отключён"); return@setOnClickListener
            }
            if (nodeList.isNotEmpty()) {
                val chosen = nodeList[selectedNodeIdx]; pendingNode = chosen.first; pendingCfg = VlessParser.buildXrayJson(chosen.second)
                val prep = VpnService.prepare(this); if (prep != null) vpnPermLauncher.launch(prep) else startVpnTunnel(pendingCfg, pendingNode)
                return@setOnClickListener
            }
            if (subUrl.isEmpty()) { toast("Подписка не активна"); return@setOnClickListener }
            btnConn.text = "⏳ Загрузка узлов..."
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = client.newCall(Request.Builder().url(subUrl).header("User-Agent", "v2rayNG/1.8.5").build()).execute().body?.string() ?: ""
                    val parsed = VlessParser.extractFirstVless(body)
                    withContext(Dispatchers.Main) {
                        if (parsed == null) { btnConn.text = "🔑 Подключить в 1 клик"; toast("Не найдены VLESS-узлы"); return@withContext }
                        pendingNode = parsed.first; pendingCfg = parsed.second
                        val prep = VpnService.prepare(this@MainActivity); if (prep != null) vpnPermLauncher.launch(prep) else startVpnTunnel(pendingCfg, pendingNode)
                    }
                } catch (_: Exception) { withContext(Dispatchers.Main) { btnConn.text = "🔑 Подключить в 1 клик"; toast("Ошибка загрузки") } }
            }
        }
        findViewById<Button>(R.id.btnCopyKey).setOnClickListener {
            val l = subUrl.ifEmpty { nodeList.firstOrNull()?.second ?: "" }; if (l.isEmpty()) { toast("Ключ не найден"); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SERVPS", l)); toast("Ссылка скопирована")
        }
        findViewById<Button>(R.id.btnPause).setOnClickListener { sendAction(if (isPaused) "unpause" else "pause") }
        findViewById<Button>(R.id.btnRevoke).setOnClickListener { sendAction("revoke") }
        findViewById<Button>(R.id.btnResetHwid).setOnClickListener { sendAction("reset_hwid") }
        checkForUpdates()
    }

    private fun startVpnTunnel(cfg: String, nodeName: String) {
        startService(Intent(this, ServpnVpnService::class.java).apply { action = ServpnVpnService.ACTION_START; putExtra("config", cfg); putExtra("node", nodeName) })
        findViewById<Button>(R.id.btnConnect).apply { text = "🟢 Отключить VPN ($nodeName)"; setBackgroundColor(0xFF16A34A.toInt()) }
        toast("VPN подключён: $nodeName")
    }

    private fun loadProfile(tok: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val res = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/profile").header("Authorization", "Bearer $tok").build()).execute().body?.string() ?: "{}")
                if (res.optBoolean("ok")) {
                    uid = res.optString("tg_id"); sig = res.optString("sig")
                    withContext(Dispatchers.Main) { findViewById<TextView>(R.id.tvUserTitle).text = "⚡ ${res.optString("first_name", "SERVPS")}"; loadUserData() }
                } else withContext(Dispatchers.Main) {
                    getSharedPreferences("servpn", Context.MODE_PRIVATE).edit().remove("token").apply()
                    findViewById<ScrollView>(R.id.mainContainer).visibility = View.GONE; findViewById<ScrollView>(R.id.authScroll).visibility = View.VISIBLE
                }
            } catch (_: Exception) {}
        }
    }

    private fun loadUserData() {
        if (uid.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val u = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user?u=$uid&s=$sig").build()).execute().body?.string() ?: "{}")
                if (!u.optBoolean("ok")) return@launch
                subUrl = u.optString("subUrl"); isPaused = u.optBoolean("paused_rem", false); nodeList.clear()
                val vk = u.optString("vlessKey")
                if (vk.startsWith("vless://")) nodeList.add(Pair(if ("#" in vk) URLDecoder.decode(vk.substringAfter("#"), "UTF-8") else "Авто", vk))
                val rk = u.optJSONArray("routerKeys")
                if (rk != null) for (i in 0 until rk.length()) {
                    val it = rk.opt(i); val s = if (it is JSONObject) it.optString("url").ifEmpty { it.optString("key") } else it?.toString() ?: ""
                    if (s.startsWith("vless://") && nodeList.none { n -> n.second == s }) {
                        val nm = if (it is JSONObject && it.optString("name").isNotEmpty()) it.optString("name") else if ("#" in s) URLDecoder.decode(s.substringAfter("#"), "UTF-8") else "Сервер #${i + 1}"
                        nodeList.add(Pair(nm, s))
                    }
                }
                withContext(Dispatchers.Main) {
                    findViewById<TextView>(R.id.tvSubStatus).text = "Статус: ${u.optString("status", "—")}"
                    findViewById<TextView>(R.id.tvExpire).text = "Действует до: ${u.optString("expireAt", "—").take(10)}"
                    findViewById<TextView>(R.id.tvTraffic).text = "Трафик: ${u.optDouble("usedGb", 0.0)} ГБ • Лимит устр.: ${u.optInt("deviceLimit", 1)}"
                    findViewById<TextView>(R.id.tvBalance).text = "Баланс: ${u.optInt("balance", 0)} ₽"
                    findViewById<Button>(R.id.btnPause).text = if (isPaused) "▶ Возобновить подписку" else "⏸ Поставить на паузу"
                    if (nodeList.isNotEmpty()) { if (selectedNodeIdx >= nodeList.size) selectedNodeIdx = 0; findViewById<Button>(R.id.btnSelectNode).text = "🌍 Сервер: ${nodeList[selectedNodeIdx].first}" }
                    val tBox = findViewById<LinearLayout>(R.id.tariffsBox); tBox.removeAllViews(); val tArr = u.optJSONArray("tariffs")
                    if (tArr != null) for (i in 0 until tArr.length()) {
                        val t = tArr.getJSONObject(i); val tid = t.optInt("id", i + 1)
                        tBox.addView(Button(this@MainActivity).apply {
                            text = "⚡ Продлить с баланса: ${t.optString("name")} — ${t.optInt("price")} ₽"; setBackgroundColor(0xFF27272A.toInt()); setTextColor(0xFFFACC15.toInt())
                            setOnClickListener { CoroutineScope(Dispatchers.IO).launch {
                                val b = JSONObject().put("u", uid).put("s", sig).put("action", "buy_tariff").put("tariff_id", tid).toString().toRequestBody("application/json".toMediaType())
                                val r = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user/action").post(b).build()).execute().body?.string() ?: "{}")
                                withContext(Dispatchers.Main) { toast(r.optString("msg", "Готово")); loadUserData() }
                            } }
                        })
                    }
                    val devs = u.optJSONArray("devices"); val sb = StringBuilder()
                    if (devs != null) for (i in 0 until devs.length()) { val d = devs.getJSONObject(i); sb.append("• ${d.optString("deviceModel", "Устройство")} (${d.optString("platform", "")})\n") }
                    findViewById<TextView>(R.id.tvDevicesList).text = if (sb.isEmpty()) "Нет привязанных устройств" else sb.toString().trim()
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendAction(act: String) {
        if (uid.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().put("u", uid).put("s", sig).put("action", act).toString().toRequestBody("application/json".toMediaType())
                val res = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user/action").post(body).build()).execute().body?.string() ?: "{}")
                withContext(Dispatchers.Main) { toast(res.optString("msg", if (res.optBoolean("ok")) "Выполнено" else "Ошибка")); loadUserData() }
            } catch (_: Exception) { withContext(Dispatchers.Main) { toast("Ошибка соединения") } }
        }
    }

    private fun checkForUpdates() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val verCode = packageManager.getPackageInfo(packageName, 0).longVersionCode
                val res = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/version").build()).execute().body?.string() ?: "{}")
                if (res.optLong("versionCode", 0) > verCode) withContext(Dispatchers.Main) {
                    AlertDialog.Builder(this@MainActivity).setTitle("Доступно обновление SERVPS").setMessage("Вышла новая версия. Установить сейчас?")
cat << 'EOF' >> /opt/servpn-android/app/src/main/java/xyz/serega16051990/servpn/MainActivity.kt
                        .setPositiveButton("Обновить") { _, _ -> downloadAndInstall(res.getString("apkUrl")) }.setNegativeButton("Позже", null).show()
                }
            } catch (_: Exception) {}
        }
    }

    private fun downloadAndInstall(apkUrl: String) {
        toast("Скачивание обновления...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = client.newCall(Request.Builder().url(apkUrl).build()).execute().body?.bytes() ?: return@launch
                val f = File(cacheDir, "update.apk").apply { writeBytes(bytes) }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", f)
                withContext(Dispatchers.Main) { startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }) }
            } catch (_: Exception) { withContext(Dispatchers.Main) { toast("Ошибка загрузки") } }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
