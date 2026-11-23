package to.silva.gtvlivestream

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Settings and status screen. Everything runs on the UI thread except the self-test POST, which
 * needs its own thread because it talks to the service over loopback.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var healthGeneration: Int = 0

    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var ipText: TextView
    private lateinit var portInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var copyTokenButton: Button
    private lateinit var regenTokenButton: Button
    private lateinit var positionSpinner: Spinner
    private lateinit var widthInput: EditText
    private lateinit var heightInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var muteSwitch: Switch
    private lateinit var bootSwitch: Switch
    private lateinit var saveButton: Button
    private lateinit var grantButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var testButton: Button
    private lateinit var yamlText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        statusText = findViewById(R.id.statusText)
        statusDot = findViewById(R.id.statusDot)
        ipText = findViewById(R.id.ipText)
        portInput = findViewById(R.id.portInput)
        tokenInput = findViewById(R.id.tokenInput)
        copyTokenButton = findViewById(R.id.copyTokenButton)
        regenTokenButton = findViewById(R.id.regenTokenButton)
        positionSpinner = findViewById(R.id.positionSpinner)
        widthInput = findViewById(R.id.widthInput)
        heightInput = findViewById(R.id.heightInput)
        durationInput = findViewById(R.id.durationInput)
        muteSwitch = findViewById(R.id.muteSwitch)
        bootSwitch = findViewById(R.id.bootSwitch)
        saveButton = findViewById(R.id.saveButton)
        grantButton = findViewById(R.id.grantButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        testButton = findViewById(R.id.testButton)
        yamlText = findViewById(R.id.yamlText)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ShowRequestParser.POSITIONS.toList(),
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        positionSpinner.adapter = adapter

        populateFields()
        maybeStartServiceOnLaunch()

        saveButton.setOnClickListener { saveSettings() }
        grantButton.setOnClickListener { grantOverlayPermission() }
        startButton.setOnClickListener { startService() }
        stopButton.setOnClickListener { stopService() }
        testButton.setOnClickListener { sendTestOverlay() }
        copyTokenButton.setOnClickListener { copyToken() }
        regenTokenButton.setOnClickListener { regenerateToken() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshYaml()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun populateFields() {
        portInput.setText(prefs.port.toString())
        tokenInput.setText(prefs.token)
        widthInput.setText(prefs.widthPct.toString())
        heightInput.setText(prefs.heightPct.toString())
        durationInput.setText(prefs.durationS.toString())
        muteSwitch.isChecked = prefs.mute
        bootSwitch.isChecked = prefs.startOnBoot

        val positions = ShowRequestParser.POSITIONS.toList()
        val index = positions.indexOf(prefs.position)
        if (index >= 0) {
            positionSpinner.setSelection(index)
        }
    }

    private fun saveSettings() {
        prefs.port = readInt(portInput, prefs.port, MIN_PORT, MAX_PORT)
        prefs.token = tokenInput.text.toString()
        prefs.position = positionSpinner.selectedItem as? String ?: prefs.position
        prefs.widthPct = readInt(widthInput, prefs.widthPct, MIN_PCT, MAX_PCT)
        prefs.heightPct = readInt(heightInput, prefs.heightPct, MIN_PCT, MAX_PCT)
        prefs.durationS = readInt(durationInput, prefs.durationS, MIN_DURATION_S, MAX_DURATION_S)
        prefs.mute = muteSwitch.isChecked
        prefs.startOnBoot = bootSwitch.isChecked

        // Rewrite the fields so the user sees the clamped values that were actually stored.
        portInput.setText(prefs.port.toString())
        tokenInput.setText(prefs.token)
        widthInput.setText(prefs.widthPct.toString())
        heightInput.setText(prefs.heightPct.toString())
        durationInput.setText(prefs.durationS.toString())

        toast(R.string.toast_saved)
        refreshStatus()
        refreshYaml()
    }

    private fun grantOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + packageName),
        )
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.toast_need_overlay)
        }
    }

    /** The receiver is meant to run headless: opening the app brings the service up by itself. */
    private fun maybeStartServiceOnLaunch() {
        if (!prefs.startOnBoot) {
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        try {
            OverlayService.start(this)
        } catch (e: RuntimeException) {
            // Starting from a foreground activity should always be allowed; ignore if not.
        }
    }

    private fun startService() {
        try {
            OverlayService.start(this)
        } catch (e: RuntimeException) {
            toast(R.string.toast_test_failed)
            return
        }
        if (Settings.canDrawOverlays(this)) {
            toast(R.string.toast_service_starting)
        } else {
            Toast.makeText(this, R.string.toast_starting_without_permission, Toast.LENGTH_LONG).show()
        }
        refreshLater()
    }

    private fun stopService() {
        OverlayService.stop(this)
        toast(R.string.toast_service_stopping)
        refreshLater()
    }

    private fun refreshLater() {
        handler.postDelayed(
            { refreshStatus() },
            REFRESH_DELAY_MS,
        )
    }

    private fun sendTestOverlay() {
        val port = prefs.port
        val token = prefs.token
        val body = JSONObject()
            .put("url", TEST_STREAM_URL)
            .put("title", "Test")
            .put("subtitle", "gtv-live-stream")
            .put("duration_s", 15)
            .toString()

        Thread {
            var ok = false
            var detail = ""
            var connection: HttpURLConnection? = null
            try {
                val conn = URL("http://127.0.0.1:" + port + "/show").openConnection() as HttpURLConnection
                connection = conn
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("X-Auth-Token", token)
                conn.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
                val code = conn.responseCode
                ok = code == HTTP_OK
                if (!ok) {
                    detail = "HTTP $code"
                }
            } catch (e: Exception) {
                detail = e.javaClass.simpleName + ": " + (e.message ?: "no message")
                ok = false
            } finally {
                connection?.disconnect()
            }
            val success = ok
            val failureDetail = detail
            runOnUiThread {
                if (success) {
                    toast(R.string.toast_test_sent)
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.toast_test_failed_detail, failureDetail),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun copyToken() {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText(getString(R.string.label_token), prefs.token))
        toast(R.string.toast_copied)
    }

    private fun regenerateToken() {
        val token = prefs.regenerateToken()
        tokenInput.setText(token)
        refreshYaml()
        Toast.makeText(this, REGEN_HINT, Toast.LENGTH_LONG).show()
    }

    private fun refreshStatus() {
        val port = prefs.port
        val addresses = deviceAddresses()
        val primary = addresses.firstOrNull() ?: "127.0.0.1"

        ipText.text = if (addresses.isEmpty()) {
            ""
        } else {
            addresses.joinToString(", ") + " · " + getString(R.string.label_port) + " " + port
        }
        statusText.text = getString(R.string.status_checking)
        setStatusColor(R.color.status_unknown)

        val generation = ++healthGeneration
        Thread {
            val healthy = probeHealth(port)
            runOnUiThread {
                if (generation != healthGeneration) {
                    return@runOnUiThread
                }
                val canOverlay = Settings.canDrawOverlays(this)
                statusText.text = when {
                    !healthy -> getString(R.string.status_offline, port)
                    else -> getString(R.string.status_running, primary, port)
                }
                setStatusColor(
                    when {
                        !healthy -> R.color.status_stopped
                        !canOverlay -> R.color.status_warning
                        else -> R.color.status_running
                    },
                )
                if (healthy && !canOverlay) {
                    statusText.append("\n" + getString(R.string.status_needs_permission))
                }
            }
        }.start()
    }

    /** Traffic-light dot: green running, amber running without overlay permission, red offline. */
    private fun setStatusColor(colorRes: Int) {
        statusDot.backgroundTintList = ColorStateList.valueOf(getColor(colorRes))
    }

    /** Returns true when the local service answers /health on [port]. */
    private fun probeHealth(port: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL("http://127.0.0.1:" + port + "/health").openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = HEALTH_TIMEOUT_MS
            conn.readTimeout = HEALTH_TIMEOUT_MS
            conn.responseCode == HTTP_OK
        } catch (e: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    private fun refreshYaml() {
        yamlText.text = buildRestCommand()
    }

    private fun buildRestCommand(): String {
        val host = deviceAddresses().firstOrNull() ?: "127.0.0.1"
        val port = prefs.port
        val token = prefs.token
        return """
            rest_command:
              gtv_live_show:
                url: "http://$host:$port/show"
                method: POST
                headers:
                  X-Auth-Token: "$token"
                  Content-Type: "application/json"
                payload: >
                  {"url": "{{ camera_url }}", "title": "Front Door",
                   "position": "bottom-right", "width_pct": 38, "duration_s": 40}

              gtv_live_hide:
                url: "http://$host:$port/hide"
                method: POST
                headers:
                  X-Auth-Token: "$token"
        """.trimIndent()
    }

    /** Every non-loopback, non-link-local IPv4 address on the device, in interface order. */
    private fun deviceAddresses(): List<String> {
        val addresses = ArrayList<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp) {
                    continue
                }
                val inetAddresses = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val address = inetAddresses.nextElement()
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress
                    ) {
                        val host = address.hostAddress
                        if (host != null && !addresses.contains(host)) {
                            addresses.add(host)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Interface enumeration is best-effort; an empty list is shown instead.
            return addresses
        }
        return addresses
    }

    private fun readInt(input: EditText, fallback: Int, min: Int, max: Int): Int {
        val parsed = input.text.toString().trim().toIntOrNull() ?: fallback
        return parsed.coerceIn(min, max)
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MIN_PORT: Int = 1024
        const val MAX_PORT: Int = 65535
        const val MIN_PCT: Int = 15
        const val MAX_PCT: Int = 100
        const val MIN_DURATION_S: Int = 0
        const val MAX_DURATION_S: Int = 600

        const val REFRESH_DELAY_MS: Long = 800
        const val CONNECT_TIMEOUT_MS: Int = 4000
        const val READ_TIMEOUT_MS: Int = 6000
        const val HEALTH_TIMEOUT_MS: Int = 1500
        const val HTTP_OK: Int = 200

        const val TEST_STREAM_URL: String = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        // strings.xml is fixed for this build, so this one hint lives here.
        const val REGEN_HINT: String = "New token saved — restart the service to apply"
    }
}
