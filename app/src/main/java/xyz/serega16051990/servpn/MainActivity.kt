package xyz.serega16051990.servpn

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import android.view.View
import android.widget.*
import androidx.appcompat.app.*
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private val baseUrl = "https://serega16051990.xyz"
    private val client = OkHttpClient()
    private var uid = ""
    private var sig = ""
    private var subUrl = ""
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("servpn_prefs", Context.MODE_PRIVATE)
        val authBox = findViewById<ScrollView>(R.id.authScroll)
        val mainBox = findViewById<ScrollView>(R.id.mainContainer)
        val codeIn = findViewById<EditText>(R.id.codeInput)

        if (!prefs.getBoolean("disc_ok", false)) {
            AlertDialog.Builder(this)
                .setTitle("Защита соединения SERVPN")
                .setMessage("SERVPN использует зашифрованный канал для защиты трафика в публичных Wi-Fi сетях и управления подпиской.")
                .setCancelable(false)
                .setPositiveButton("Принять") { _, _ -> prefs.edit().putBoolean("disc_ok", true).apply() }
                .show()
        }

        fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

        fun logout() {
            prefs.edit().remove("tok").apply()
            uid = ""; sig = ""; subUrl = ""
            mainBox.visibility = View.GONE
            authBox.visibility = View.VISIBLE
            codeIn.setText("")
        }
        fun loadUserData() {
            if (uid.isEmpty() || sig.isEmpty()) return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val u = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user?u=$uid&s=$sig").build()).execute().body?.string() ?: "{}")
                    if (!u.optBoolean("ok")) return@launch
                    subUrl = u.optString("subUrl")
                    val st = u.optString("status", "NONE")
                    isPaused = (st == "PAUSED")
                    val devs = u.optJSONArray("devices")
                    val devLines = StringBuilder()
                    if (devs != null && devs.length() > 0) {
                        for (i in 0 until devs.length()) {
                            val d = devs.getJSONObject(i)
                            devLines.append("• ${d.optString("model")} (${d.optString("platform")})\n")
                        }
                    } else devLines.append("Нет привязанных устройств")
                    withContext(Dispatchers.Main) {
                        findViewById<TextView>(R.id.tvUserTitle).text = "⚡ ${u.optString("name", "SERVPN")}"
                        findViewById<TextView>(R.id.tvSubStatus).text = "Статус: $st"
                        findViewById<TextView>(R.id.tvExpire).text = "Действует до: ${u.optString("expireAt", "—")}"
                        findViewById<TextView>(R.id.tvTraffic).text = "Трафик: ${u.optDouble("used_gb", 0.0)} ГБ • Лимит устр.: ${u.optInt("deviceLimit", 0)}"
                        findViewById<TextView>(R.id.tvBalance).text = "Баланс: ${u.optInt("balance", 0)} ₽"
                        val tBox = findViewById<LinearLayout>(R.id.tariffsBox)
                        tBox.removeAllViews()
                        val tArr = u.optJSONArray("tariffs")
                        if (tArr != null) {
                            for (i in 0 until tArr.length()) {
                                val t = tArr.getJSONObject(i)
                                val tid = t.optInt("id", i + 1)
                                val btn = Button(this@MainActivity).apply {
                                    text = "⚡ Продлить с баланса: ${t.optString("name")} — ${t.optInt("price")} ₽"
                                    setBackgroundColor(0xFF27272A.toInt())
                                    setTextColor(0xFFFACC15.toInt())
                                    setOnClickListener {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            val b = JSONObject().put("u", uid).put("s", sig).put("action", "buy_tariff").put("tariff_id", tid).toString().toRequestBody("application/json".toMediaType())
                                            val r = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user/action").post(b).build()).execute().body?.string() ?: "{}")
                                            withContext(Dispatchers.Main) { toast(r.optString("msg", "Готово")); loadUserData() }
                                        }
                                    }
                                }
                                tBox.addView(btn)
                            }
                        }
                        findViewById<TextView>(R.id.tvDevicesList).text = devLines.toString().trim()
                    }
                } catch (_: Exception) {}
            }
        }

        fun sendAction(act: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().put("u", uid).put("s", sig).put("action", act).toString().toRequestBody("application/json".toMediaType())
                    val r = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/user/action").post(body).build()).execute().body?.string() ?: "{}")
                    withContext(Dispatchers.Main) {
                        toast(r.optString("msg", "Выполнено"))
                        loadUserData()
                    }
                } catch (_: Exception) {}
            }
        }

        fun openCab(tok: String) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(codeIn.windowToken, 0)
            authBox.visibility = View.GONE
            mainBox.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val j = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/profile").addHeader("Authorization", "Bearer $tok").build()).execute().body?.string() ?: "{}")
                    if (j.optBoolean("ok")) {
                        uid = j.optString("tg_id")
                        sig = j.optString("sig")
                        loadUserData()
                    } else withContext(Dispatchers.Main) { logout() }
                } catch (_: Exception) {}
            }
        }

        prefs.getString("tok", null)?.let { if (it.isNotEmpty()) openCab(it) }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            val c = codeIn.text.toString().trim()
            if (c.length != 6) return@setOnClickListener
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().put("code", c).toString().toRequestBody("application/json".toMediaType())
                    val j = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/auth").post(body).build()).execute().body?.string() ?: "{}")
                    withContext(Dispatchers.Main) {
                        if (j.optBoolean("ok")) {
                            val t = j.getString("token")
                            prefs.edit().putString("tok", t).apply()
                            openCab(t)
                        } else toast(j.optString("error", "Неверный код"))
                    }
                } catch (_: Exception) {}
            }
        }

        findViewById<Button>(R.id.btnRefresh).setOnClickListener { loadUserData(); toast("Обновлено") }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }
        findViewById<Button>(R.id.btnFullWeb).setOnClickListener { if (uid.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/cabinet?u=$uid&s=$sig"))) }
        findViewById<Button>(R.id.btnTopup).setOnClickListener {
            if (uid.isNotEmpty()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl/web-pay?u=$uid&s=$sig")))
        }
        findViewById<Button>(R.id.btnCopyKey).setOnClickListener {
            if (subUrl.isEmpty()) return@setOnClickListener
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SERVPN", subUrl))
            toast("Ссылка подписки скопирована!")
        }
        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (subUrl.isEmpty()) { toast("Подписка не активна"); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SERVPN", subUrl))
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("v2rayng://install-sub?url=${Uri.encode(subUrl)}&name=SERVPN"))) }
            catch (_: Exception) { toast("Ссылка скопирована — вставьте её в v2rayNG или Happ") }
        }
        findViewById<Button>(R.id.btnPause).setOnClickListener { sendAction(if (isPaused) "unpause" else "pause") }
        findViewById<Button>(R.id.btnRevoke).setOnClickListener { sendAction("revoke") }
        findViewById<Button>(R.id.btnResetHwid).setOnClickListener { sendAction("hwid") }
    }
}
