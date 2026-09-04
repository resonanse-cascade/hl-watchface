package com.resonanse.hlwatchface

import android.Manifest
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import kotlinx.coroutines.launch

/**
 * On-watch complication editor.
 * Reached via: long-press watch face → Customize.
 * Shows one button per complication slot; tapping opens the system data-source
 * chooser. Each button also reports the data source currently assigned.
 *
 * NOTE: the chooser only opens if the app declares
 * `com.google.android.wearable.permission.RECEIVE_COMPLICATION_DATA`.
 */
class WatchFaceConfigActivity : ComponentActivity() {

    private companion object {
        val SLOTS = listOf(
            "DATE" to LambdaWatchFaceService.TOP_ID,
            "SUIT" to LambdaWatchFaceService.L1_ID,
            "AUX"  to LambdaWatchFaceService.L2_ID,
            "MOVE" to LambdaWatchFaceService.L3_ID,
        )
        val ORANGE = Color.parseColor("#FF9C2E")
    }

    private var editorSession: EditorSession? = null
    private val buttons = mutableMapOf<Int, Button>()
    private var pulseButton: Button? = null
    private var themeButton: Button? = null

    /**
     * Heart rate is read from Health Services rather than the complication, because
     * Samsung Health will not give a real BPM to a side-loaded face. That needs
     * BODY_SENSORS, and a WatchFaceService cannot ask for it — only an Activity can,
     * so the Customize screen offers it.
     */
    private val requestBodySensors =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) pulseButton?.text = "[ PULSE ]\nsensor allowed"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val session = EditorSession.createOnWatchEditorSession(this@WatchFaceConfigActivity)
            editorSession = session
            buildUI()
            // Keeps each button's subtitle in step with what the chooser assigned.
            session.complicationsDataSourceInfo.collect { info ->
                for ((label, id) in SLOTS) {
                    buttons[id]?.text = "[ $label ]\n${info[id]?.name ?: "not set"}"
                }
            }
        }
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(24, 40, 24, 40)
            setBackgroundColor(Color.BLACK)
        }

        val title = TextView(this).apply {
            text      = "HL HUD — EDIT"
            textSize  = 13f
            setTextColor(ORANGE)
            gravity   = Gravity.CENTER
        }
        root.addView(title, lp(marginBottom = 14))

        // Face-wide switch, so it sits above the per-slot buttons.
        val themeBtn = Button(this).apply {
            text     = "[ THEME ]"
            textSize = 12f
            minHeight = 0
            minimumHeight = 0
            setLineSpacing(0f, 0.95f)
            setTextColor(ORANGE)
            setBackgroundColor(Color.parseColor("#1A1A00"))
            setPadding(8, 8, 8, 8)
            setOnClickListener { cycleTheme() }
        }
        themeButton = themeBtn
        root.addView(themeBtn, lp(marginBottom = 8))
        refreshThemeLabel()

        for ((label, id) in SLOTS) {
            val btn = Button(this).apply {
                text     = "[ $label ]"
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setLineSpacing(0f, 0.95f)
                setTextColor(ORANGE)
                setBackgroundColor(Color.parseColor("#1A1A00"))
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    lifecycleScope.launch {
                        editorSession?.openComplicationDataSourceChooser(id)
                    }
                }
            }
            buttons[id] = btn
            root.addView(btn, lp(marginBottom = 8))
        }

        if (!HeartRateSource.hasPermission(this)) {
            val btn = Button(this).apply {
                text     = "[ PULSE ]\nallow heart rate sensor"
                textSize = 12f
                minHeight = 0
                minimumHeight = 0
                setLineSpacing(0f, 0.95f)
                setTextColor(ORANGE)
                setBackgroundColor(Color.parseColor("#1A1A00"))
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    requestBodySensors.launch(Manifest.permission.BODY_SENSORS)
                }
            }
            pulseButton = btn
            root.addView(btn, lp(marginBottom = 8))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFillViewport = true
            addView(root)
        })
    }

    /** The theme setting, or null if the schema has not loaded yet. */
    private fun themeSetting(): ListUserStyleSetting? =
        editorSession?.userStyleSchema?.userStyleSettings
            ?.filterIsInstance<ListUserStyleSetting>()
            ?.firstOrNull { it.id.value == "theme" }

    private fun currentThemeOption(): ListUserStyleSetting.ListOption? {
        val setting = themeSetting() ?: return null
        return editorSession?.userStyle?.value?.get(setting) as? ListUserStyleSetting.ListOption
    }

    private fun refreshThemeLabel() {
        val name = currentThemeOption()?.displayName ?: "—"
        themeButton?.text = "[ THEME ]\n$name"
    }

    /** Steps to the next option, so one button covers however many themes exist. */
    private fun cycleTheme() {
        val session = editorSession ?: return
        val setting = themeSetting() ?: return
        val options = setting.options.filterIsInstance<ListUserStyleSetting.ListOption>()
        if (options.isEmpty()) return
        val currentId = currentThemeOption()?.id
        val idx = options.indexOfFirst { it.id == currentId }
        val next = options[(idx + 1).mod(options.size)]
        session.userStyle.value = session.userStyle.value.toMutableUserStyle()
            .apply { set(setting, next) }
            .toUserStyle()
        refreshThemeLabel()
    }

    private fun lp(marginBottom: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, 0, 0, marginBottom) }

    // Deliberately no onDestroy/close(): createOnWatchEditorSession registers its own
    // lifecycle observer that closes the session on ON_DESTROY and commits the changes.
    // Closing it a second time throws IllegalArgumentException out of onDestroy, which
    // kills the process mid-commit — the slot choice is then silently rolled back.
}
