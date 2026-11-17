package com.galax71.steamhapticssinger

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.provider.DocumentsContract
import android.content.ContentResolver
import android.view.View
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var midiStatus: TextView
    private lateinit var notesNow: TextView
    private lateinit var playbackTime: TextView
    private var playbackSession = 0
    private var controllerHapticsEnabled = true
    private var lastHapticErrorShown = false
    private var midiOutputMode = 1
    private var repeatMidiPlayback = false
    private var limitHapticsToTwoChannels = false
    private val puck by lazy { SteamPuckHaptics(this) }
    private val puckPermissionAction = "com.galax71.steamhapticssinger.USB_PERMISSION"
    private var usbPermissionReceiver: BroadcastReceiver? = null
    private lateinit var outputStatus: TextView
    private var lastHapticNote = -1
    private var lastHapticAt = 0L
    private lateinit var controllerStatus: TextView
    private val pickerMidi = 1002
    private val pickerFolder = 1003
    private val pickerImport = 1004
    private lateinit var libraryList: LinearLayout
    private val libraryPrefs by lazy { getSharedPreferences("midi_library", MODE_PRIVATE) }
    private fun folderUri(): Uri? = libraryPrefs.getString("tree", null)?.let(Uri::parse)
    private val player = MidiPlayer()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 16, 26)
        window.navigationBarColor = Color.rgb(13, 16, 26)
        window.decorView.systemUiVisibility = 0
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(24))
        }

        column.addView(TextView(this).apply { text = "MIDI player"; textSize = 22f })
        midiStatus = TextView(this).apply { text = "Choose a Standard MIDI File (.mid/.midi). Simple synthesized sine-wave output; no instrument soundfont." }
        column.addView(midiStatus)
        column.addView(TextView(this).apply { text = "MIDI output"; textSize = 20f })
        val outputSelector = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("Phone speaker", "Steam Controller haptics", "Both (phone + controller)").forEachIndexed { index, label ->
            outputSelector.addView(RadioButton(this).apply {
                id = 5100 + index
                text = label
                textSize = 16f
            })
        }
        outputSelector.check(5101)
        column.addView(outputSelector)
        outputStatus = TextView(this).apply {
            text = "Steam Controller haptics selected. Direct USB-C or Puck supported experimentally."
            textSize = 13f
        }
        column.addView(outputStatus)
        outputSelector.setOnCheckedChangeListener { _, selected ->
            midiOutputMode = (selected - 5100).coerceIn(0, 2)
            controllerHapticsEnabled = midiOutputMode != 0
            if (midiOutputMode == 0) cancelControllerVibration()
            outputStatus.text = when (midiOutputMode) {
                0 -> "Phone speaker selected."
                1 -> "Controller only: experimental pitch-based Puck haptics. Phone audio disabled."
                else -> "Both: phone audio and experimental pitch-based Puck haptics."
            }
        }
        column.addView(Button(this).apply {
            text = "Auto-detect active controller connection"
            setOnClickListener { autoDetectController() }
        })
        column.addView(TextView(this).apply { text = "Controller music strength"; textSize = 18f })
        val gainLabel = TextView(this).apply { text = "High (-3)"; textSize = 14f }
        column.addView(gainLabel)
        column.addView(android.widget.SeekBar(this).apply {
            max = 35
            progress = 32
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val gain = -35 + progress
                    puck.musicGain = gain
                    gainLabel.text = "Haptic strength: ${if (progress < 12) "Low" else if (progress < 25) "Medium" else "High"} ($gain)"
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        })
        column.addView(android.widget.CheckBox(this).apply {
            text = "Use MIDI velocity for haptic strength (SteamHapticsSinger -e)"
            isChecked = false
            setOnCheckedChangeListener { _, checked -> puck.directVelocityGain = checked }
        })
        column.addView(TextView(this).apply { text = "SteamHapticsSinger playback options"; textSize = 18f })
        column.addView(CheckBox(this).apply {
            text = "Repeat MIDI song"
            isChecked = repeatMidiPlayback
            setOnCheckedChangeListener { _, checked -> repeatMidiPlayback = checked }
        })
        column.addView(CheckBox(this).apply {
            text = "Limit controller playback to 2 MIDI channels (1–2)"
            isChecked = limitHapticsToTwoChannels
            setOnCheckedChangeListener { _, checked ->
                limitHapticsToTwoChannels = checked
                if (checked) {
                    puck.note(2, null)
                    puck.note(3, null)
                    hapticNotes[2] = -1
                    hapticNotes[3] = -1
                }
            }
        })
        column.addView(CheckBox(this).apply {
            text = "Swap trackpad and rumble motors"
            isChecked = puck.swapTrackpadAndRumble
            setOnCheckedChangeListener { _, checked ->
                // Silence existing notes using their original actuator mapping.
                stopControllerNotes()
                puck.swapTrackpadAndRumble = checked
            }
        })
        column.addView(TextView(this).apply { text = "Live MIDI notes"; textSize = 20f })
        notesNow = TextView(this).apply { text = "No notes playing"; textSize = 24f; setPadding(12, 12, 12, 12) }
        column.addView(notesNow)
        playbackTime = TextView(this).apply { text = "00:00 / 00:00" }
        column.addView(playbackTime)
        column.addView(TextView(this).apply {
            text = "Live notes follow MIDI playback. USB pitch-based haptics are experimental."
            textSize = 12f
        })
        column.addView(Button(this).apply { text = "Choose MIDI library folder"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }, pickerFolder)
        } })
        column.addView(Button(this).apply { text = "Import MIDI into library"; setOnClickListener {
            if (folderUri() == null) {
                midiStatus.text = "Choose or create a MIDI folder first."
            } else pick(pickerImport)
        } })
        column.addView(Button(this).apply { text = "Refresh MIDI library"; setOnClickListener { refreshLibrary() } })
        libraryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(libraryList)
        refreshLibrary()

        column.addView(TextView(this).apply { text = "Steam Controller test"; textSize = 22f })
        controllerStatus = TextView(this).apply { text = "Use Scan to list Android-visible input and USB devices." }
        column.addView(controllerStatus)
        column.addView(Button(this).apply {
            text = "Scan connected controllers"
            setOnClickListener { scanControllers() }
        })
        column.addView(Button(this).apply {
            text = "Connect Steam Controller (USB-C / Puck)"
            setOnClickListener { connectPuck() }
        })
        column.addView(Button(this).apply {
            text = "Test selected USB rumble"
            setOnClickListener {
                if (!puck.hasPermission()) { connectPuck(); return@setOnClickListener }
                val result = puck.connect()
                val sent = if (result.startsWith("Connected")) puck.rumble(9000, 9000) else false
                controllerStatus.text = "$result\\n" + if (sent)
                    "Direct USB rumble packet sent. Confirm whether the controller vibrated."
                    else "Direct USB rumble not sent; inspect permission/interface."
                if (sent) handler.postDelayed({ puck.rumble(0, 0) }, 120L)
            }
        })
        column.addView(Button(this).apply {
            text = "Test musical note (A4, 440 Hz)"
            setOnClickListener {
                if (!puck.hasPermission()) { connectPuck(); return@setOnClickListener }
                val result = puck.connect()
                if (!result.startsWith("Connected")) {
                    controllerStatus.text = result
                    return@setOnClickListener
                }
                val ok = puck.note(2, 69, 64)
                controllerStatus.text = if (ok)
                    "Sent 440 Hz note to right trackpad. Does it produce a tone? It will stop automatically."
                    else "0x83 musical haptic note transfer failed."
                if (ok) handler.postDelayed({ puck.note(2, null) }, 350L)
            }
        })
        column.addView(Button(this).apply {
            text = "Test Android controller vibration"
            setOnClickListener { testControllerVibration() }
        })
        column.addView(TextView(this).apply {
            text = "Pitch-based USB MIDI playback: channels 1–4 map to four haptic motors. Other MIDI channels are ignored for accuracy, like SteamHapticsSinger."
            textSize = 13f
        })
        setupTabbedUi(column)
        handler.post { autoDetectController() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun stopMidiPlayback() {
        playbackSession++
        player.stop()
        cancelControllerVibration()
        notesNow.text = "No notes playing"
        playbackTime.text = "00:00 / 00:00"
        midiStatus.text = "Playback stopped."
    }

    private fun setupTabbedUi(original: LinearLayout) {
        val children = (0 until original.childCount).map { original.getChildAt(it) }
        original.removeAllViews()
        val controllerStart = children.indexOfFirst {
            it is TextView && it.text.toString() == "Steam Controller test"
        }
        if (controllerStart < 0) return
        val pages = (0..1).map {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(40))
            }
        }
        children.forEachIndexed { index, view ->
            pages[if (index < controllerStart) 0 else 1].addView(view)
        }
        val bg = Color.rgb(13, 16, 26)
        val panel = Color.rgb(29, 34, 50)
        val white = Color.rgb(238, 241, 250)
        fun decorate(view: View) {
            when (view) {
                is Button -> {
                    view.isAllCaps = false
                    view.setTextColor(Color.WHITE)
                    view.textSize = 14f
                    view.backgroundTintList = ColorStateList.valueOf(Color.rgb(86, 69, 178))
                    view.minHeight = dp(48)
                }
                is EditText -> {
                    view.setTextColor(white)
                    view.setHintTextColor(Color.LTGRAY)
                }
                is TextView -> {
                    view.setTextColor(white)
                    if (view.textSize / resources.displayMetrics.scaledDensity > 26f) view.textSize = 23f
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) decorate(view.getChildAt(i))
        }
        pages.forEach { decorate(it) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val heading = TextView(this).apply {
            text = "SteamHapticsSinger Android"
            textSize = 23f
            setTextColor(Color.WHITE)
            setPadding(dp(18), dp(18), dp(18), dp(12))
        }
        root.addView(heading)
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        val tabButtons = listOf("MIDI", "Controller").map { title ->
            Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                minHeight = dp(44)
            }.also { tabs.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f)) }
        }
        root.addView(tabs)
        val content = android.widget.FrameLayout(this)
        pages.forEach { page ->
            val scroller = ScrollView(this).apply {
                clipToPadding = false
                isFillViewport = true
                addView(page)
            }
            content.addView(scroller, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        // Persistent playback control remains visible outside the scrollable page.
        val stop = Button(this).apply {
            text = "Stop MIDI playback"
            isAllCaps = false
            minHeight = dp(56)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(144, 55, 78))
            setOnClickListener { stopMidiPlayback() }
        }
        root.addView(stop, LinearLayout.LayoutParams(-1, dp(56)).apply {
            setMargins(dp(12), dp(4), dp(12), dp(8))
        })
        // Respect navigation/gesture bars and display cutouts on all Android devices.
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout() or
                        android.view.WindowInsets.Type.ime()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            root.fitsSystemWindows = true
        }
        fun selectTab(selected: Int) {
            for (i in 0 until content.childCount) {
                content.getChildAt(i).visibility = if (i == selected) View.VISIBLE else View.GONE
                tabButtons[i].backgroundTintList = ColorStateList.valueOf(
                    if (i == selected) Color.rgb(86, 69, 178) else panel)
            }
        }
        tabButtons.forEachIndexed { index, button -> button.setOnClickListener { selectTab(index) } }
        selectTab(0)
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) root.requestApplyInsets()
    }

    private fun pick(code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, code)
    }

    private data class LibrarySong(val uri: Uri, val name: String)
    private fun songs(): List<LibrarySong> {
        val tree = folderUri() ?: return emptyList()
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val result = mutableListOf<LibrarySong>()
        contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1) ?: continue
                if (name.endsWith(".mid", true) || name.endsWith(".midi", true)) {
                    result.add(LibrarySong(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
                }
            }
        }
        return result.sortedBy { it.name.lowercase() }
    }

    private fun refreshLibrary() {
        if (!::libraryList.isInitialized) return
        libraryList.removeAllViews()
        val tree = folderUri()
        if (tree == null) {
            libraryList.addView(TextView(this).apply { text = "No MIDI folder selected. Choose a folder to store songs." })
            return
        }
        try {
            val items = songs()
            if (items.isEmpty()) libraryList.addView(TextView(this).apply { text = "Library empty. Import a MIDI file." })
            for (song in items) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 8, 0, 8) }
                row.addView(TextView(this).apply { text = song.name; textSize = 17f })
                val actions = LinearLayout(this)
                actions.addView(Button(this).apply { text = "Play"; setOnClickListener {
                    try {
                        val bytes = contentResolver.openInputStream(song.uri)?.use { it.readBytes() } ?: error("Cannot open file")
                        val parsed = MidiFileParser.parse(bytes)
                        midiStatus.text = "Playing ${song.name}"
                        playWithNotes(parsed, song.name)
                    } catch (e: Exception) { midiStatus.text = "Playback error: ${e.message}" }
                } })
                actions.addView(Button(this).apply { text = "Remove"; setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove MIDI file?")
                        .setMessage("Delete ${song.name} from the selected MIDI folder? This cannot be undone.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            try {
                                if (!DocumentsContract.deleteDocument(contentResolver, song.uri)) error("Deletion refused")
                                midiStatus.text = "Removed ${song.name}"
                                refreshLibrary()
                            } catch (e: Exception) { midiStatus.text = "Delete failed: ${e.message}" }
                        }.show()
                } })
                row.addView(actions)
                libraryList.addView(row)
            }
        } catch (e: Exception) {
            libraryList.addView(TextView(this).apply { text = "Cannot read folder: ${e.message}. Choose it again." })
        }
    }

    private fun midiNoteName(note: Int): String {
        val names = arrayOf("C", "C#", "D", "D#", "D#", "F", "F#", "G", "G#", "A", "A#", "B")
        return names[note % 12] + (note / 12 - 1)
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1000L
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }

    private fun playWithNotes(song: MidiSong, filename: String) {
        if (midiOutputMode != 0) {
            if (!puck.hasPermission()) {
                connectPuck()
                midiStatus.text = "Grant USB access to the connected Steam Controller and press Play again."
                return
            }
            val connected = puck.connect()
            if (!connected.startsWith("Connected")) {
                midiStatus.text = "USB controller connection failed: $connected"
                return
            }
        }
        lastHapticErrorShown = false
        hapticNotes.fill(-1)
        // Invalidate callbacks from the prior playback before stopping it.
        val session = ++playbackSession
        player.stop()
        stopControllerNotes()
        midiStatus.text = "Playing $filename (" + if (midiOutputMode == 0) "phone audio)" else if (midiOutputMode == 1) "controller haptics test)" else "phone + experimental controller haptics)"
        notesNow.text = "Waiting for first note..."
        playbackTime.text = "00:00 / ${formatTime(song.durationMs)}"
        player.play(song, { result ->
            handler.post {
                if (session == playbackSession) {
                    notesNow.text = "No notes playing"
                    stopControllerNotes()
                    if (repeatMidiPlayback && result.contains("complete", ignoreCase = true)) {
                        midiStatus.text = "Repeating $filename"
                        // Defer restart until the completed worker has returned.
                        handler.postDelayed({
                            if (session == playbackSession) playWithNotes(song, filename)
                        }, 80L)
                    } else {
                        midiStatus.text = result
                    }
                }
            }
        }, { position, active ->
            handler.post {
                if (session == playbackSession) {
                    playbackTime.text = "${formatTime(position)} / ${formatTime(song.durationMs)}"
                    notesNow.text = if (active.isEmpty()) "Rest — no notes sounding"
                    else active.joinToString("   ") {
                        "${midiNoteName(it.note)} (Ch ${it.channel + 1}, Vel ${it.velocity})"
                    }
                }
            }
        }, phoneAudio = midiOutputMode != 1, onMidiEvent = { event ->
            if (session == playbackSession && midiOutputMode != 0) dispatchControllerMidi(event)
        })
    }

    private fun importMidi(uri: Uri) {
        val tree = folderUri() ?: error("Select a MIDI folder first")
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "import.mid"
        require(name.endsWith(".mid", true) || name.endsWith(".midi", true)) { "Only .mid and .midi files can be imported" }
        require(name.length <= 150) { "Filename too long" }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val safeName = name.replace(Regex("[\\\\/]"), "_")
        val target = DocumentsContract.createDocument(contentResolver, parent, "audio/midi", safeName)
            ?: error("Could not create file in folder")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                contentResolver.openOutputStream(target, "w")?.use { output ->
                    input.copyTo(output)
                } ?: error("Cannot write MIDI file")
            } ?: error("Cannot read MIDI file")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(contentResolver, target) } catch (_: Exception) {}
            throw e
        }
        midiStatus.text = "Imported ${name}"
        refreshLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (::outputStatus.isInitialized && ::controllerStatus.isInitialized) {
            handler.post { autoDetectController() }
        }
    }

    private fun autoDetectController() {
        // Detection does not claim that a Bluetooth HID connection supports
        // the vendor-specific musical note command.
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val direct = puck.directControllers()
        val receiver = puck.receiver()
        val gamepads = InputDevice.getDeviceIds().mapNotNull { InputDevice.getDevice(it) }
            .filter { (it.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (it.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK }
        val bluetoothAllowed = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val bluetooth = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        val pairedSteam = if (bluetoothAllowed) {
            try { bluetooth?.adapter?.bondedDevices?.filter { it.name?.contains("Steam", true) == true }.orEmpty() }
            catch (_: SecurityException) { emptyList() }
        } else emptyList()
        val info = when {
            direct.isNotEmpty() && puck.hasPermission() ->
                "Direct USB-C Steam Controller (28DE:1302) selected. " + puck.connect()
            direct.isNotEmpty() ->
                "Direct USB-C Steam Controller detected (28DE:1302). Grant USB permission to enable MIDI haptics."
            receiver != null && puck.hasPermission() ->
                "Steam Puck selected. " + puck.connect()
            receiver != null ->
                "Steam Puck detected (28DE:1304). Grant USB permission to enable MIDI haptics."
            pairedSteam.isNotEmpty() ->
                "Paired Steam Bluetooth controller found. Bluetooth musical note output requires a verified HID transport."
            gamepads.isNotEmpty() ->
                "Android gamepad found: ${gamepads.first().name}. MIDI pitch output is not available over Android's generic vibrator API."
            else -> "No supported Steam Controller connection detected."
        }
        outputStatus.text = info
        controllerStatus.text = info
    }

    private fun connectPuck() {
        val device = puck.preferredDevice()
        if (device == null) { controllerStatus.text = "No supported Steam Controller USB device detected."; return }
        if (puck.hasPermission()) { controllerStatus.text = puck.connect(); return }
        if (usbPermissionReceiver == null) {
            usbPermissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != puckPermissionAction) return
                    controllerStatus.text = if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                        puck.connect() else "USB permission denied."
                }
            }
            val filter = IntentFilter(puckPermissionAction)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(usbPermissionReceiver, filter)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getBroadcast(this, 0, Intent(puckPermissionAction).setPackage(packageName), flags)
        (getSystemService(USB_SERVICE) as UsbManager).requestPermission(device, pending)
        controllerStatus.text = "Allow USB access to the selected Steam Controller device."
    }

    private fun controllerVibrator(): Vibrator? {
        for (id in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(id) ?: continue
            val gamepad = (device.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (device.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            if (!gamepad) continue
            val vibrator = device.vibrator
            if (vibrator.hasVibrator()) return vibrator
        }
        return null
    }

    private fun cancelControllerVibration() {
        try { controllerVibrator()?.cancel() } catch (_: Exception) {}
        try { stopControllerNotes(); puck.rumble(0, 0) } catch (_: Exception) {}
    }

    private fun testControllerVibration() {
        val vibrator = controllerVibrator()
        if (vibrator == null) {
            controllerStatus.text = "Puck USB detection does not expose a gamepad vibrator through Android. No vibration sent. Raw Puck haptic commands remain unverified."
            return
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(180L, 100))
            controllerStatus.text = "Sent a 180 ms vibration request to an Android gamepad vibrator. Confirm whether the Steam Controller actually vibrated."
        } catch (e: Exception) {
            controllerStatus.text = "Gamepad vibration failed: ${e.message}"
        }
    }

    // Called from MIDI playback worker, never the UI thread. Keep the most recent
    // sounding note on each supported MIDI channel; always send note-off first.
    private val hapticNotes = IntArray(4) { -1 }
    private fun dispatchControllerMidi(event: MidiNoteEvent) {
        if (!controllerHapticsEnabled) return
        // Match SteamHapticsSinger's default channel handling: four dedicated
        // monophonic actuators, with other MIDI channels ignored.
        val channel = event.channel
        if (channel !in 0..3 || (limitHapticsToTwoChannels && channel >= 2)) return
        val previousNote = hapticNotes[channel]
        if (event.on) {
            // Replace a note only on its assigned actuator; sending note-off
            // before a new note avoids a stuck/drifting actuator.
            val success = puck.note(channel, event.note, event.velocity)
            hapticNotes[channel] = if (success) event.note else -1
            if (!success && !lastHapticErrorShown) {
                lastHapticErrorShown = true
                handler.post { outputStatus.text = "Puck 0x83 note output failed; reconnect USB." }
            }
        } else if (previousNote == event.note) {
            // An old note-off must not silence a newer note on this channel.
            puck.note(channel, null)
            hapticNotes[channel] = -1
        }
    }

    private fun stopControllerNotes() {
        for (i in hapticNotes.indices) hapticNotes[i] = -1
        puck.stopNotes()
    }

    private fun scanControllers() {
        val lines = mutableListOf<String>()
        val manager = getSystemService(USB_SERVICE) as UsbManager
        for (device in manager.deviceList.values) {
            val label = if (device.vendorId == 0x28de && device.productId == 0x1304)
                "Steam Controller 2026 wireless Puck (USB receiver detected; haptic output unverified)"
                else "USB device"
            val interfaces = (0 until device.interfaceCount).joinToString(", ") { i ->
                val face = device.getInterface(i)
                "interface ${face.id}: class ${face.interfaceClass}, endpoints ${face.endpointCount}"
            }
            lines.add("$label: VID=%04X PID=%04X, %s\\n%s".format(device.vendorId, device.productId, device.deviceName, interfaces))
        }
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                lines.add("Gamepad: ${d.name} (VID=%04X PID=%04X)".format(d.vendorId, d.productId))
            }
        }
        controllerStatus.text = if (lines.isEmpty()) "No USB peripherals or Android gamepads detected." else lines.joinToString("\n")
    }

    @Deprecated("Simple document picker for Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        if (requestCode == pickerFolder) {
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                libraryPrefs.edit().putString("tree", uri.toString()).apply()
                midiStatus.text = "MIDI library folder selected."
                refreshLibrary()
            } catch (e: Exception) { midiStatus.text = "Folder access error: ${e.message}" }
            return
        }
        try {
            if (requestCode == pickerImport) {
                importMidi(uri)
            } else if (requestCode == pickerMidi) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Unable to open MIDI")
                val song = MidiFileParser.parse(bytes)
                midiStatus.text = "Playing ${song.events.size} note events (${song.durationMs} ms)"
                playWithNotes(song, "Selected MIDI")
            }
        } catch (e: Exception) {
            if (requestCode == pickerMidi || requestCode == pickerImport) midiStatus.text = "MIDI error: ${e.message}"

        }
    }

    override fun onDestroy() {
        player.stop()
        cancelControllerVibration()
        puck.close()
        usbPermissionReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
