package com.snapedge.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.snapedge.app.R
import com.snapedge.app.capture.CapturePermissionActivity
import com.snapedge.app.capture.ScreenCaptureHolder
import com.snapedge.app.data.PreferencesManager
import com.snapedge.app.model.EdgeSide
import com.snapedge.app.overlay.PinnedFloatingWindow
import com.snapedge.app.service.EdgeOverlayService

/**
 * Companion dashboard for enabling the floating Edge Sidebar, granting permissions,
 * customizing edge handle appearance, and browsing the Clip Vault.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager
    private lateinit var vaultAdapter: VaultAdapter

    private lateinit var switchMasterEdge: MaterialSwitch
    private lateinit var tvServiceStatus: TextView
    private lateinit var tvPermOverlay: TextView
    private lateinit var tvPermCapture: TextView
    private lateinit var tvEmptyVault: TextView

    private val notificationPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                toggleEdgeService(true)
            } else {
                Toast.makeText(this, "Notification permission helps keep the sidebar alive.", Toast.LENGTH_SHORT).show()
                toggleEdgeService(true)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        bindViews()
        setupVault()
        setupCustomizer()
    }

    override fun onResume() {
        super.onResume()
        refreshStatusAndVault()
    }

    private fun bindViews() {
        switchMasterEdge = findViewById(R.id.switchMasterEdge)
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvPermOverlay = findViewById(R.id.tvPermOverlay)
        tvPermCapture = findViewById(R.id.tvPermCapture)
        tvEmptyVault = findViewById(R.id.tvEmptyVault)

        switchMasterEdge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                ensureOverlayAndStart()
            } else {
                toggleEdgeService(false)
            }
        }

        findViewById<MaterialButton>(R.id.btnGrantOverlay).setOnClickListener {
            openOverlaySettings()
        }

        findViewById<MaterialButton>(R.id.btnGrantCapture).setOnClickListener {
            CapturePermissionActivity.launch(this)
        }

        findViewById<MaterialButton>(R.id.btnQuickCircleOcr).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings()
                return@setOnClickListener
            }
            EdgeOverlayService.start(this, EdgeOverlayService.ACTION_CIRCLE_OCR)
        }

        findViewById<MaterialButton>(R.id.btnQuickLongShot).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                openOverlaySettings()
                return@setOnClickListener
            }
            EdgeOverlayService.start(this, EdgeOverlayService.ACTION_LONG_SHOT)
        }

        findViewById<MaterialButton>(R.id.btnClearVault).setOnClickListener {
            prefs.clearClips()
            refreshStatusAndVault()
        }
    }

    private fun setupVault() {
        val rvVault = findViewById<RecyclerView>(R.id.rvVault)
        vaultAdapter = VaultAdapter(
            onCopyClick = { clip ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SnapEdge Vault", clip.text))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onPinClick = { clip ->
                if (!Settings.canDrawOverlays(this)) {
                    openOverlaySettings()
                } else {
                    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                    PinnedFloatingWindow(this, wm, clip.text, null).show()
                }
            },
            onOpenUrlClick = { url ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SnapEdge Link", url))
                Toast.makeText(this, "Copied & opening link", Toast.LENGTH_SHORT).show()
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }
            }
        )
        rvVault.layoutManager = LinearLayoutManager(this)
        rvVault.adapter = vaultAdapter
    }

    private fun setupCustomizer() {
        val config = prefs.getEdgeConfig()
        val rgSide = findViewById<RadioGroup>(R.id.rgEdgeSide)
        val rbRight = findViewById<RadioButton>(R.id.rbRightEdge)
        val rbLeft = findViewById<RadioButton>(R.id.rbLeftEdge)
        val seekVertical = findViewById<SeekBar>(R.id.seekVerticalPos)
        val seekOpacity = findViewById<SeekBar>(R.id.seekOpacity)

        if (config.side == EdgeSide.LEFT) rbLeft.isChecked = true else rbRight.isChecked = true
        seekVertical.progress = config.verticalPercent
        seekOpacity.progress = config.opacityPercent

        rgSide.setOnCheckedChangeListener { _, checkedId ->
            val current = prefs.getEdgeConfig()
            val side = if (checkedId == R.id.rbLeftEdge) EdgeSide.LEFT else EdgeSide.RIGHT
            prefs.saveEdgeConfig(current.copy(side = side))
            notifyOverlayConfigChanged()
        }

        seekVertical.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val current = prefs.getEdgeConfig()
                prefs.saveEdgeConfig(current.copy(verticalPercent = progress.coerceAtLeast(5)))
                notifyOverlayConfigChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekOpacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val current = prefs.getEdgeConfig()
                prefs.saveEdgeConfig(current.copy(opacityPercent = progress.coerceAtLeast(15)))
                notifyOverlayConfigChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun ensureOverlayAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            switchMasterEdge.isChecked = false
            openOverlaySettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        toggleEdgeService(true)
    }

    private fun toggleEdgeService(enable: Boolean) {
        prefs.setEdgeEnabled(enable)
        if (enable) {
            EdgeOverlayService.start(this)
        } else {
            val stopIntent = Intent(this, EdgeOverlayService::class.java).apply {
                action = EdgeOverlayService.ACTION_STOP
            }
            startService(stopIntent)
        }
        refreshStatusAndVault()
    }

    private fun notifyOverlayConfigChanged() {
        if (prefs.getEdgeConfig().enabled && Settings.canDrawOverlays(this)) {
            EdgeOverlayService.start(this, EdgeOverlayService.ACTION_REFRESH_CONFIG)
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
        Toast.makeText(this, "Allow 'Display over other apps' for SnapEdge", Toast.LENGTH_LONG).show()
    }

    private fun refreshStatusAndVault() {
        val config = prefs.getEdgeConfig()
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasCapture = ScreenCaptureHolder.isReady()

        switchMasterEdge.setOnCheckedChangeListener(null)
        switchMasterEdge.isChecked = config.enabled && hasOverlay
        switchMasterEdge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) ensureOverlayAndStart() else toggleEdgeService(false)
        }

        tvServiceStatus.text = if (config.enabled && hasOverlay) {
            "Edge Sidebar is ACTIVE (${config.side.name.lowercase().replaceFirstChar { it.uppercase() }} Edge)"
        } else {
            "Edge Sidebar is OFF"
        }

        tvPermOverlay.text = if (hasOverlay) "✓ Display over other apps (Granted)" else "• Display over other apps (Required)"
        tvPermCapture.text = if (hasCapture) "✓ Screen Capture Engine (Ready)" else "• Screen Capture Engine (Tap Activate)"

        val clips = prefs.getClips()
        vaultAdapter.submitList(clips)
        tvEmptyVault.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
    }
}
