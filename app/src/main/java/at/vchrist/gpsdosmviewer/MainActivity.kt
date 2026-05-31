package at.vchrist.gpsdosmviewer

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : ComponentActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var map: MapView
    private lateinit var settingsButton: Button
    private lateinit var settingsPanel: ScrollView
    private lateinit var settingsContent: LinearLayout
    private lateinit var mainProfileSpinner: Spinner
    private lateinit var selectedProfileText: TextView
    private lateinit var profileNameEdit: EditText
    private lateinit var hostEdit: EditText
    private lateinit var portEdit: EditText
    private lateinit var autoconnectCheckBox: CheckBox
    private lateinit var newProfileButton: Button
    private lateinit var saveProfileButton: Button
    private lateinit var deleteProfileButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var closeSettingsButton: Button
    private lateinit var statusChip: TextView
    private lateinit var statusText: TextView
    private lateinit var profileAdapter: ArrayAdapter<String>
    private lateinit var settingsBackCallback: OnBackPressedCallback

    private val profiles = mutableListOf<GpsdProfile>()
    private var suppressProfileSelection = false
    private var settingsVisible = false
    private var editingProfileName: String? = null
    private var activeProfileName: String? = null

    private var gpsdJob: Job? = null
    private var socket: Socket? = null
    private var marker: Marker? = null
    private var firstFix = true

    private data class GpsdProfile(
        val name: String,
        val host: String,
        val port: Int,
        val autoconnect: Boolean = false
    )

    private companion object {
        const val PREFS_NAME = "gpsd_osm_viewer_profiles"
        const val PREF_KEY_PROFILES = "profiles_json"
        const val PREF_KEY_LAST_PROFILE = "last_profile_name"

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTING = 1
        const val STATE_CONNECTED = 2
        const val STATE_ERROR = 3
    }

    private fun applySystemBarInsets(root: LinearLayout) {
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            insets
        }

        root.requestApplyInsets()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Important for OpenStreetMap tile servers: identify your app.
        Configuration.getInstance().userAgentValue = "GpsdOsmViewer/0.2 at.vchrist.gpsdosmviewer"

        val root = createUi()
        setContentView(root)
        applySystemBarInsets(root)
        setupMap()
        setupBackHandling()

        loadProfiles()
        setupProfileActions()

        val lastProfileName = getLastProfileName()
        refreshProfileSpinner(selectName = lastProfileName, selectIndex = 0, rememberSelection = false)
        setSettingsPanelVisible(false)
        updateConnectionButtons()

        settingsButton.setOnClickListener {
            setSettingsPanelVisible(!settingsVisible)
        }

        closeSettingsButton.setOnClickListener {
            setSettingsPanelVisible(false)
        }

        connectButton.setOnClickListener {
            connectSelectedProfile()
        }

        disconnectButton.setOnClickListener {
            disconnectGpsd()
        }

        currentSelectedProfile()?.let { selected ->
            persistLastProfileName(selected.name)
            updateStatus("Selected ${selected.name}. Press Connect to use ${selected.host}:${selected.port}.", STATE_DISCONNECTED)
        }
    }

    private fun createUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 250, 250))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        profileAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(4))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        settingsButton = Button(this).apply {
            text = "⚙"
            contentDescription = "Open profile settings"
            textSize = 20f
            minWidth = 0
            minHeight = 0
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        mainProfileSpinner = Spinner(this).apply {
            adapter = profileAdapter
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        connectButton = Button(this).apply {
            text = "Connect"
            minWidth = 0
            minHeight = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        disconnectButton = Button(this).apply {
            text = "Disconnect"
            minWidth = 0
            minHeight = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        topBar.addView(settingsButton)
        topBar.addView(mainProfileSpinner)
        topBar.addView(connectButton)
        topBar.addView(disconnectButton)

        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(6))
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        statusChip = TextView(this).apply {
            text = "OFFLINE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(117, 117, 117))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        statusText = TextView(this).apply {
            text = "Disconnected"
            textSize = 13f
            setSingleLine(true)
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBar.addView(statusChip)
        statusBar.addView(statusText)

        settingsPanel = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = false
            setBackgroundColor(Color.rgb(245, 245, 245))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        settingsContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val settingsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }

        val settingsTitle = TextView(this).apply {
            text = "Profile management"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        closeSettingsButton = Button(this).apply {
            text = "Done"
            minHeight = 0
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        settingsHeader.addView(settingsTitle)
        settingsHeader.addView(closeSettingsButton)

        selectedProfileText = TextView(this).apply {
            text = "Selected profile"
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        }

        val profileSectionTitle = sectionTitle("Profile")

        profileNameEdit = EditText(this).apply {
            hint = "Profile name, e.g. Boat, Car, Raspberry Pi"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val connectionSectionTitle = sectionTitle("gpsd connection")

        val connectionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        hostEdit = EditText(this).apply {
            hint = "Host/IP"
            setText("192.168.1.1")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_NEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        portEdit = EditText(this).apply {
            hint = "Port"
            setText("2947")
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        connectionRow.addView(hostEdit)
        connectionRow.addView(portEdit)

        autoconnectCheckBox = CheckBox(this).apply {
            text = "Autoconnect when this is the last used profile"
            setPadding(0, dp(6), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val hintText = TextView(this).apply {
            text = "Tip: use 10.0.2.2 only in the Android emulator. On a real phone, use the gpsd device's LAN IP."
            textSize = 12f
            setTextColor(Color.rgb(90, 90, 90))
            setPadding(0, 0, 0, dp(6))
        }

        val profileActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        newProfileButton = Button(this).apply {
            text = "New"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        saveProfileButton = Button(this).apply {
            text = "Save"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        deleteProfileButton = Button(this).apply {
            text = "Delete"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        profileActionRow.addView(newProfileButton)
        profileActionRow.addView(saveProfileButton)
        profileActionRow.addView(deleteProfileButton)

        settingsContent.addView(settingsHeader)
        settingsContent.addView(selectedProfileText)
        settingsContent.addView(profileSectionTitle)
        settingsContent.addView(profileNameEdit)
        settingsContent.addView(connectionSectionTitle)
        settingsContent.addView(connectionRow)
        settingsContent.addView(autoconnectCheckBox)
        settingsContent.addView(hintText)
        settingsContent.addView(profileActionRow)
        settingsPanel.addView(settingsContent)

        map = MapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(topBar)
        root.addView(statusBar)
        root.addView(settingsPanel)
        root.addView(map)
        return root
    }

    private fun sectionTitle(title: String): TextView = TextView(this).apply {
        text = title.uppercase()
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(90, 90, 90))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun setupProfileActions() {
        mainProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!suppressProfileSelection && position in profiles.indices) {
                    val profile = profiles[position]
                    applyProfileToFields(profile)
                    persistLastProfileName(profile.name)
                    if (gpsdJob?.isActive != true) {
                        updateStatus("Selected ${profile.name}. Press Connect to use ${profile.host}:${profile.port}.", STATE_DISCONNECTED)
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        newProfileButton.setOnClickListener {
            editingProfileName = null
            profileNameEdit.setText("")
            hostEdit.setText("")
            portEdit.setText("2947")
            autoconnectCheckBox.isChecked = false
            profileNameEdit.error = null
            hostEdit.error = null
            portEdit.error = null
            selectedProfileText.text = "New profile"
            updateStatus("Enter a name, host and port, then press Save.", STATE_DISCONNECTED)
            setSettingsPanelVisible(true)
        }

        saveProfileButton.setOnClickListener {
            saveCurrentProfile()
        }

        deleteProfileButton.setOnClickListener {
            deleteSelectedProfile()
        }
    }

    private fun setSettingsPanelVisible(visible: Boolean) {
        settingsVisible = visible
        settingsPanel.visibility = if (visible) View.VISIBLE else View.GONE
        settingsButton.text = if (visible) "×" else "⚙"
        settingsButton.contentDescription = if (visible) "Close profile settings" else "Open profile settings"
        if (::settingsBackCallback.isInitialized) {
            settingsBackCallback.isEnabled = visible
        }
        if (visible) {
            currentSelectedProfile()?.let { applyProfileToFields(it) }
        }
    }

    private fun setupBackHandling() {
        settingsBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                setSettingsPanelVisible(false)
            }
        }
        onBackPressedDispatcher.addCallback(this, settingsBackCallback)
    }

    private fun loadProfiles() {
        profiles.clear()

        val jsonString = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY_PROFILES, null)

        if (!jsonString.isNullOrBlank()) {
            runCatching {
                val jsonProfiles = JSONArray(jsonString)
                for (i in 0 until jsonProfiles.length()) {
                    val obj = jsonProfiles.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val host = obj.optString("host").trim()
                    val port = obj.optInt("port", 2947)
                    val autoconnect = obj.optBoolean("autoconnect", false)
                    if (name.isNotBlank() && host.isNotBlank() && port in 1..65535) {
                        profiles.add(GpsdProfile(name, host, port, autoconnect))
                    }
                }
            }.onFailure {
                Toast.makeText(this, "Could not read stored gpsd profiles. Using default.", Toast.LENGTH_LONG).show()
            }
        }

        if (profiles.isEmpty()) {
            profiles.add(defaultProfile())
            persistProfiles()
        }
    }

    private fun persistProfiles() {
        val jsonProfiles = JSONArray()
        profiles.forEach { profile ->
            jsonProfiles.put(
                JSONObject()
                    .put("name", profile.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("autoconnect", profile.autoconnect)
            )
        }

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_PROFILES, jsonProfiles.toString())
            .apply()
    }

    private fun getLastProfileName(): String? = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREF_KEY_LAST_PROFILE, null)

    private fun persistLastProfileName(profileName: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_KEY_LAST_PROFILE, profileName)
            .apply()
    }

    private fun defaultProfile(): GpsdProfile = GpsdProfile(
        name = "Default",
        host = "192.168.1.1",
        port = 2947,
        autoconnect = false
    )

    private fun profileLabel(profile: GpsdProfile): String = buildString {
        append(profile.name)
        if (profile.autoconnect) append(" · auto")
    }

    private fun refreshProfileSpinner(
        selectName: String? = null,
        selectIndex: Int? = null,
        rememberSelection: Boolean = true
    ) {
        suppressProfileSelection = true
        profileAdapter.clear()
        profiles.forEach { profileAdapter.add(profileLabel(it)) }
        profileAdapter.notifyDataSetChanged()

        val index = when {
            selectName != null -> profiles.indexOfFirst { it.name == selectName }
            selectIndex != null -> selectIndex.coerceIn(0, profiles.lastIndex)
            mainProfileSpinner.selectedItemPosition in profiles.indices -> mainProfileSpinner.selectedItemPosition
            else -> 0
        }.let { if (it in profiles.indices) it else 0 }

        if (index in profiles.indices) {
            mainProfileSpinner.setSelection(index, false)
            applyProfileToFields(profiles[index])
            if (rememberSelection) {
                persistLastProfileName(profiles[index].name)
            }
        }
        suppressProfileSelection = false
    }

    private fun currentSelectedProfile(): GpsdProfile? {
        val selectedIndex = mainProfileSpinner.selectedItemPosition
        if (selectedIndex in profiles.indices) {
            return profiles[selectedIndex]
        }
        val editedName = profileNameEdit.text.toString().trim()
        return profiles.firstOrNull { it.name == editedName }
    }

    private fun currentSelectedProfileIndex(): Int {
        val selectedIndex = mainProfileSpinner.selectedItemPosition
        if (selectedIndex in profiles.indices) return selectedIndex

        val editedName = profileNameEdit.text.toString().trim()
        return profiles.indexOfFirst { it.name == editedName }
    }

    private fun applyProfileToFields(profile: GpsdProfile) {
        editingProfileName = profile.name
        selectedProfileText.text = "Editing: ${profile.name}  •  ${profile.host}:${profile.port}"
        profileNameEdit.setText(profile.name)
        hostEdit.setText(profile.host)
        portEdit.setText(profile.port.toString())
        autoconnectCheckBox.isChecked = profile.autoconnect
        profileNameEdit.error = null
        hostEdit.error = null
        portEdit.error = null
    }

    private fun saveCurrentProfile() {
        val host = hostEdit.text.toString().trim()
        val port = portEdit.text.toString().toIntOrNull()
        val profileName = profileNameEdit.text.toString().trim().ifBlank {
            if (host.isNotBlank() && port != null) "$host:$port" else "Unnamed"
        }
        val autoconnect = autoconnectCheckBox.isChecked

        profileNameEdit.error = null
        hostEdit.error = null
        portEdit.error = null

        if (profileName.isBlank()) {
            profileNameEdit.error = "Required"
            updateStatus("Please enter a profile name.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }

        if (host.isBlank()) {
            hostEdit.error = "Required"
            updateStatus("Please enter a gpsd host/IP before saving.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }

        if (port == null || port !in 1..65535) {
            portEdit.error = "1…65535"
            updateStatus("Please enter a valid TCP port from 1 to 65535 before saving.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }

        val profile = GpsdProfile(profileName, host, port, autoconnect)

        val sameNameIndex = profiles.indexOfFirst { it.name == profileName }
        val editingIndex = editingProfileName
            ?.let { originalName -> profiles.indexOfFirst { it.name == originalName } }
            ?: -1

        if (sameNameIndex >= 0 && editingIndex >= 0 && sameNameIndex != editingIndex) {
            profileNameEdit.error = "Already exists"
            updateStatus("A different profile named '$profileName' already exists.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }

        val targetIndex = when {
            sameNameIndex >= 0 -> sameNameIndex
            editingIndex >= 0 -> editingIndex
            else -> -1
        }

        if (targetIndex >= 0) {
            profiles[targetIndex] = profile
        } else {
            profiles.add(profile)
        }

        persistProfiles()
        refreshProfileSpinner(selectName = profileName)
        persistLastProfileName(profileName)
        updateStatus("Saved ${profile.name}. Press Connect to use ${profile.host}:${profile.port}.", STATE_DISCONNECTED)
        Toast.makeText(this, "Saved profile '${profile.name}'", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedProfile() {
        val index = currentSelectedProfileIndex()

        if (index !in profiles.indices) {
            updateStatus("No stored profile selected.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }

        val removed = profiles.removeAt(index)
        if (activeProfileName == removed.name) {
            disconnectGpsd(updateUi = false)
            activeProfileName = null
        }

        if (profiles.isEmpty()) {
            profiles.add(defaultProfile())
        }

        persistProfiles()
        val newIndex = index.coerceAtMost(profiles.lastIndex)
        refreshProfileSpinner(selectIndex = newIndex)
        currentSelectedProfile()?.let { persistLastProfileName(it.name) }
        updateStatus("Deleted ${removed.name}.", STATE_DISCONNECTED)
        Toast.makeText(this, "Deleted profile '${removed.name}'", Toast.LENGTH_SHORT).show()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(16.0)
        map.controller.setCenter(GeoPoint(48.2864, 14.2990)) // default: around Hagenberg/Linz area
    }

    private fun connectSelectedProfile() {
        val profile = currentSelectedProfile()
        if (profile == null) {
            updateStatus("No profile selected.", STATE_ERROR)
            setSettingsPanelVisible(true)
            return
        }
        persistLastProfileName(profile.name)
        connectGpsd(profile)
    }

    private fun maybeAutoconnectSelectedProfile() {
        if (gpsdJob?.isActive == true) return

        val profile = currentSelectedProfile() ?: return
        persistLastProfileName(profile.name)

        if (profile.autoconnect) {
            connectGpsd(profile)
        }
    }

    private fun connectGpsd(profile: GpsdProfile) {
        connectGpsd(profile.host, profile.port, profile.name)
    }

    private fun connectGpsd(host: String, port: Int, profileName: String? = null) {
        if (gpsdJob?.isActive == true) {
            disconnectGpsd(updateUi = false)
        }

        if (host.isBlank()) {
            updateStatus("Please enter a gpsd host/IP.", STATE_ERROR)
            setSettingsPanelVisible(true)
            updateConnectionButtons()
            return
        }

        firstFix = true
        activeProfileName = profileName
        updateConnectionButtons(connecting = true)
        updateStatus("Connecting to ${profileName ?: "gpsd"} at $host:$port …", STATE_CONNECTING)
        setSettingsPanelVisible(false)

        gpsdJob = uiScope.launch {
            try {
                readGpsd(host, port, profileName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateStatus("GPSD error: ${e.message ?: e.javaClass.simpleName}", STATE_ERROR)
                setSettingsPanelVisible(true)
            } finally {
                disconnectGpsd(updateUi = true)
            }
        }
    }

    private suspend fun readGpsd(host: String, port: Int, profileName: String?) = withContext(Dispatchers.IO) {
        Socket().use { s ->
            socket = s
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = 15_000

            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII))
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            writer.write("?WATCH={\"enable\":true,\"json\":true};\n")
            writer.flush()

            withContext(Dispatchers.Main) {
                updateStatus("Connected to ${profileName ?: "$host:$port"}. Waiting for TPV fix …", STATE_CONNECTED)
                updateConnectionButtons(connected = true)
            }

            while (isActive) {
                val line = reader.readLine() ?: break
                handleGpsdLine(line, profileName)
            }
        }
    }

    private suspend fun handleGpsdLine(line: String, profileName: String?) = withContext(Dispatchers.Main) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return@withContext
        if (json.optString("class") != "TPV") return@withContext

        val mode = json.optInt("mode", 0)
        if (mode < 2 || !json.has("lat") || !json.has("lon")) {
            updateStatus("${profileName ?: "gpsd"}: TPV received, but no 2D/3D fix yet. mode=$mode", STATE_CONNECTED)
            return@withContext
        }

        val lat = json.getDouble("lat")
        val lon = json.getDouble("lon")
        val speedMps = json.optDouble("speed", Double.NaN)
        val trackDeg = json.optDouble("track", Double.NaN)
        val epx = json.optDouble("epx", Double.NaN)
        val epy = json.optDouble("epy", Double.NaN)
        val time = json.optString("time", "")

        val point = GeoPoint(lat, lon)
        updateMarker(point, mode, speedMps, trackDeg)

        if (firstFix) {
            map.controller.setZoom(17.0)
            firstFix = false
        }
        map.controller.animateTo(point)

        val speedText = if (speedMps.isFinite()) " ${(speedMps * 3.6).roundToInt()} km/h" else ""
        val trackText = if (trackDeg.isFinite()) " track=${trackDeg.roundToInt()}°" else ""
        val errText = if (epx.isFinite() && epy.isFinite()) " epx/epy=${epx.roundToInt()}/${epy.roundToInt()} m" else ""
        updateStatus("${profileName ?: "gpsd"}: mode=$mode lat=%.6f lon=%.6f%s%s%s %s".format(lat, lon, speedText, trackText, errText, time), STATE_CONNECTED)
    }

    private fun updateMarker(point: GeoPoint, mode: Int, speedMps: Double, trackDeg: Double) {
        val currentMarker = marker ?: Marker(map).also {
            it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            it.title = "Remote GPSD position"
            map.overlays.add(it)
            marker = it
        }
        currentMarker.position = point
        currentMarker.snippet = buildString {
            append("mode=$mode")
            if (speedMps.isFinite()) append("\nspeed=%.1f km/h".format(speedMps * 3.6))
            if (trackDeg.isFinite()) append("\ntrack=%.0f°".format(trackDeg))
        }
        map.invalidate()
    }

    private fun updateStatus(message: String, state: Int) {
        statusText.text = message
        when (state) {
            STATE_CONNECTING -> {
                statusChip.text = "CONNECTING"
                statusChip.setBackgroundColor(Color.rgb(251, 140, 0))
                statusChip.setTextColor(Color.WHITE)
            }
            STATE_CONNECTED -> {
                statusChip.text = "ONLINE"
                statusChip.setBackgroundColor(Color.rgb(46, 125, 50))
                statusChip.setTextColor(Color.WHITE)
            }
            STATE_ERROR -> {
                statusChip.text = "ERROR"
                statusChip.setBackgroundColor(Color.rgb(198, 40, 40))
                statusChip.setTextColor(Color.WHITE)
            }
            else -> {
                statusChip.text = "OFFLINE"
                statusChip.setBackgroundColor(Color.rgb(117, 117, 117))
                statusChip.setTextColor(Color.WHITE)
            }
        }
    }

    private fun updateConnectionButtons(connecting: Boolean = false, connected: Boolean = gpsdJob?.isActive == true) {
        connectButton.isEnabled = !connecting && !connected
        disconnectButton.isEnabled = connecting || connected
        mainProfileSpinner.isEnabled = !connecting && !connected
        if (connecting) {
            connectButton.text = "Connecting"
        } else {
            connectButton.text = "Connect"
        }
    }

    private fun disconnectGpsd(updateUi: Boolean = true) {
        gpsdJob?.cancel()
        gpsdJob = null
        runCatching { socket?.close() }
        socket = null
        val wasActive = activeProfileName
        activeProfileName = null
        if (updateUi) {
            updateConnectionButtons(connected = false)
            if (!statusText.text.startsWith("GPSD error")) {
                updateStatus(if (wasActive != null) "Disconnected from $wasActive." else "Disconnected", STATE_DISCONNECTED)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        maybeAutoconnectSelectedProfile()
    }

    override fun onPause() {
        map.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        disconnectGpsd(updateUi = false)
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
