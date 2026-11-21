package xyz.serega16051990.servpn

import android.annotation.SuppressLint
import android.content.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.*
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
    private var currentSubUrl = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getSharedPreferences("servpn_prefs", Context.MODE_PRIVATE)
        val authBox = findViewById<LinearLayout>(R.id.authContainer)
        val mainBox = findViewById<LinearLayout>(R.id.mainContainer)
        val codeIn = findViewById<EditText>(R.id.codeInput)
        val btnLogin = findViewById<Button>(R.id.btnLogin)
        val btnSub = findViewById<Button>(R.id.btnImportSub)
        val tvStatus = findViewById<TextView>(R.id.tvSubStatus)
        val web = findViewById<WebView>(R.id.cabinetWebView)

        fun logoutToCode() {
            prefs.edit().remove("tok").apply()
            CookieManager.getInstance().removeAllCookies(null)
            web.clearCache(true)
            web.loadUrl("about:blank")
            mainBox.visibility = View.GONE
            authBox.visibility = View.VISIBLE
            codeIn.setText("")
        }
        if (!prefs.getBoolean("disc_ok", false)) {
            AlertDialog.Builder(this)
                .setTitle("Защита соединения SERVPN")
                .setMessage("SERVPN использует зашифрованный канал для защиты трафика в публичных Wi-Fi сетях и доступа к личному кабинету.")
                .setCancelable(false)
                .setPositiveButton("Принять") { _, _ -> prefs.edit().putBoolean("disc_ok", true).apply() }
                .show()
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                val u = r?.url?.toString() ?: return false
                if (u.contains("/logout") || u.contains("/login") || u == baseUrl || u == "$baseUrl/") {
                    logoutToCode()
                    return true
                }
                if (u.startsWith(baseUrl)) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return true
            }
        }

        fun openCab(tok: String) {
            authBox.visibility = View.GONE
            mainBox.visibility = View.VISIBLE
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val req = Request.Builder().url("$baseUrl/api/mobile/profile").addHeader("Authorization", "Bearer $tok").build()
                    val j = JSONObject(client.newCall(req).execute().body?.string() ?: "{}")
                    if (j.optBoolean("ok")) {
                        currentSubUrl = j.optString("subscriptionUrl")
                        val cabUrl = j.optString("cabinetUrl", "$baseUrl/cabinet")
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "${j.optString("username")} • ${j.optString("status")}"
                            web.loadUrl(cabUrl)
                        }
                    } else {
                        withContext(Dispatchers.Main) { logoutToCode() }
                    }
                } catch (_: Exception) {}
            }
        }

        prefs.getString("tok", null)?.let { if (it.isNotEmpty()) openCab(it) }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val j = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/version").build()).execute().body?.string() ?: "{}")
                val curVer = packageManager.getPackageInfo(packageName, 0).longVersionCode.toInt()
                if (j.optBoolean("ok") && j.optInt("versionCode", 1) > curVer) {
                    val apkUrl = j.optString("apkUrl", "$baseUrl/app.apk")
                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Доступно обновление SERVPN")
                            .setMessage("Вышла новая версия приложения. Нажмите «Обновить» для загрузки и установки.")
                            .setPositiveButton("Обновить") { _, _ -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl))) }
                            .setNegativeButton("Позже", null)
                            .show()
                    }
                }
            } catch (_: Exception) {}
        }

        btnLogin.setOnClickListener {
            val c = codeIn.text.toString().trim()
            if (c.length != 6) return@setOnClickListener
            btnLogin.isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val body = JSONObject().put("code", c).toString().toRequestBody("application/json".toMediaType())
                    val j = JSONObject(client.newCall(Request.Builder().url("$baseUrl/api/mobile/auth").post(body).build()).execute().body?.string() ?: "{}")
                    withContext(Dispatchers.Main) {
                        btnLogin.isEnabled = true
                        if (j.optBoolean("ok")) {
                            val t = j.getString("token")
                            prefs.edit().putString("tok", t).apply()
                            openCab(t)
                        } else Toast.makeText(this@MainActivity, j.optString("error", "Ошибка"), Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    withContext(Dispatchers.Main) { btnLogin.isEnabled = true }
                }
            }
        }

        btnSub.setOnClickListener {
            if (currentSubUrl.isEmpty()) return@setOnClickListener
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SERVPN", currentSubUrl))
            Toast.makeText(this, "Ключ скопирован!", Toast.LENGTH_SHORT).show()
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("v2rayng://install-sub?url=${Uri.encode(currentSubUrl)}&name=SERVPN"))) } catch (_: Exception) {}
        }
    }
}
