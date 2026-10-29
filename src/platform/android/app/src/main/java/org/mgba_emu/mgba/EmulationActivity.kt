/*
 * Copyright (C) 2026 Ishan
 * Android Port component of mGBA.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, version 3.
 *
 * This program is distributed without any warranty. See the GNU General Public License for more details.
 */

package org.mgba_emu.mgba

import android.content.pm.ActivityInfo
import android.net.Uri
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import java.text.DateFormat
import java.util.Date
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mgba_emu.mgba.core.Core
import org.mgba_emu.mgba.databinding.ActivityEmulationBinding
import org.mgba_emu.mgba.input.GbaKey
import org.mgba_emu.mgba.input.InputState
import org.mgba_emu.mgba.model.GameModel
import org.mgba_emu.mgba.renderer.gl.EmulationThread
import org.mgba_emu.mgba.renderer.gl.FrameBuffer
import org.mgba_emu.mgba.renderer.gl.OpenGLRenderer
import org.mgba_emu.mgba.settings.SettingsFragment
import org.mgba_emu.mgba.settings.SettingsFragmentArgs
import org.mgba_emu.mgba.settings.model.Settings
import org.mgba_emu.mgba.utils.BiosStore
import org.mgba_emu.mgba.utils.ConfigManager
import org.mgba_emu.mgba.utils.LifecycleUtils.collect
import org.mgba_emu.mgba.utils.SaveDataStore
import org.mgba_emu.mgba.utils.SaveStateStore
import org.mgba_emu.mgba.utils.SearchLocationHelper
import org.mgba_emu.mgba.utils.ViewUtils.applySafePadding

class EmulationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmulationBinding
    private lateinit var inputState: InputState
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var glRenderer: OpenGLRenderer
    private lateinit var frameBuffer: FrameBuffer

    private var currentGame: GameModel? = null
    private lateinit var stateStore: SaveStateStore

    private var emulationThread: EmulationThread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEmulationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableFullScreenImmersive()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            currentGame = intent.getParcelableExtra(GameModel.launchId, GameModel::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<GameModel>(GameModel.launchId)?.let { game: GameModel? ->
                currentGame = game
            }
        }

        if (currentGame == null) {
            val gameUri: Uri? = intent.data
            if (gameUri != null) {
                currentGame = SearchLocationHelper.getGame(gameUri)
            }

            currentGame ?: return finish()
        }

        stateStore = SaveStateStore(this, currentGame!!.uri)
        ConfigManager.gameFileName = currentGame!!.fileName
        ConfigManager.shouldHandleInGameMenu = true

        Settings.screenOrientation.flow.collect(this) {
            requestedOrientation = when (it) {
                0 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                3 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                4 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                5 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }

        binding.drawerLayout.addDrawerListener(object : DrawerLayout.DrawerListener {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

            override fun onDrawerOpened(drawerView: View) {
                pauseEmulation()
                binding.inGameMenu.requestFocus()
            }

            override fun onDrawerClosed(drawerView: View) {
                resumeEmulation()
            }

            override fun onDrawerStateChanged(newState: Int) {}
        })

        binding.inGameMenu.getHeaderView(0).findViewById<TextView>(R.id.game_title).text = currentGame!!.title ?: currentGame!!.fileName

        binding.inGameMenu.setNavigationItemSelectedListener {
            when (it.itemId) {
                R.id.menu_resume_emulation -> {
                    binding.drawerLayout.close()
                    true
                }

                R.id.menu_save_states -> {
                    showSaveStateDialog()
                    true
                }

                R.id.menu_settings -> {
                    openSettings()
                    true
                }

                R.id.menu_settings_per_game -> {
                    openSettings(currentGame)
                    true
                }

                R.id.menu_overlay_options -> {
                    showOverlayOptions()
                    true
                }

                R.id.menu_exit -> {
                    finish()
                    true
                }

                else -> true
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.touchControlsView.isEditing) {
                        binding.touchControlsView.finishEditing()
                        return
                    }
                    binding.drawerLayout.apply {
                        if (isOpen) close() else open()
                    }
                }
            }
        )

        binding.fps.isVisible = Settings.fpsCounter.value
        binding.fps.applySafePadding()

        Settings.fpsCounter.flow.distinctUntilChanged().collect(this) {
            binding.fps.isVisible = it
        }

        inputState = InputState()
        // placeholder size 0 until the first ROM loads and publishes a real frame
        frameBuffer = FrameBuffer(initialPixelCount = 0)

        if (!Core.init()) {
            Toast.makeText(this, "Failed to initialize emulator core", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupGlSurface()
        setupTouchControls()

        loadRomFromUri(currentGame!!.uri)
        setInsets()
    }

    private fun openSettings(game: GameModel? = null) {
        ConfigManager.shouldHandleInGameMenu = false
        val settingsFragment = SettingsFragment().apply {
            arguments = SettingsFragmentArgs(
                game,
                Settings.MenuTag.SECTION_ROOT
            ).toBundle()
            onDismiss = {
                ConfigManager.shouldHandleInGameMenu = true
            }
        }
        settingsFragment.show(supportFragmentManager, "SettingsFragment")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Slots and thumbnails are drawn only in the UI layer. The drawer remains
     * open, so the emulation thread is paused during save/load.
     */
    private fun showSaveStateDialog() {
        val slots = stateStore.list()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        lateinit var dialog: AlertDialog
        for (slot in slots) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val preview = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(116), dp(78))
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.rgb(34, 34, 42))
                val bitmap = if (slot.occupied) stateStore.thumbnail(slot.index) else null
                if (bitmap != null) setImageBitmap(bitmap)
                else setImageResource(R.drawable.mgba)
            }
            row.addView(preview)
            val detail = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            detail.addView(TextView(this).apply {
                text = getString(R.string.save_state_slot, slot.index)
                textSize = 16f
            })
            detail.addView(TextView(this).apply {
                text = if (slot.occupied) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(slot.modifiedAt))
                } else {
                    getString(R.string.save_state_empty)
                }
                textSize = 12f
            })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val save = Button(this).apply {
                text = getString(R.string.save_state_save)
                isAllCaps = false
                textSize = 12f
                setPadding(0, 0, 0, 0)
            }
            save.setOnClickListener {
                val action = { saveSnapshot(slot.index, dialog) }
                if (slot.occupied) {
                    AlertDialog.Builder(this)
                        .setTitle(R.string.save_state_overwrite_title)
                        .setMessage(getString(R.string.save_state_overwrite_message, slot.index))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.save_state_save) { _, _ -> action() }
                        .show()
                } else action()
            }
            actions.addView(save, LinearLayout.LayoutParams(0, dp(48), 1f))
            val load = Button(this).apply {
                text = getString(R.string.save_state_load)
                isAllCaps = false
                textSize = 12f
                isEnabled = slot.occupied
                setPadding(0, 0, 0, 0)
            }
            load.setOnClickListener { restoreSnapshot(slot.index, dialog) }
            actions.addView(load, LinearLayout.LayoutParams(0, dp(48), 1f))
            val delete = Button(this).apply {
                text = getString(R.string.save_state_delete)
                isAllCaps = false
                textSize = 12f
                isEnabled = slot.occupied
                setPadding(0, 0, 0, 0)
            }
            delete.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(R.string.save_state_delete)
                    .setMessage(getString(R.string.save_state_delete_message, slot.index))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.save_state_delete) { _, _ ->
                        val ok = stateStore.delete(slot.index)
                        Toast.makeText(this,
                            if (ok) R.string.save_state_deleted else R.string.save_state_failed,
                            Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        showSaveStateDialog()
                    }.show()
            }
            actions.addView(delete, LinearLayout.LayoutParams(0, dp(48), 1f))
            detail.addView(actions)
            row.addView(detail, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(content) }
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.save_states)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun saveSnapshot(slot: Int, dialog: AlertDialog) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val state = Core.exportState() ?: return@withContext false
                val png = try { stateStore.captureThumbnail() }
                    catch (e: Exception) {
                        Log.w(LOG_TAG, "Snapshot thumbnail failed", e)
                        null
                    }
                stateStore.save(slot, state, png)
            }
            Toast.makeText(this@EmulationActivity,
                if (ok) R.string.save_state_saved else R.string.save_state_failed,
                Toast.LENGTH_SHORT).show()
            if (dialog.isShowing) {
                dialog.dismiss()
                showSaveStateDialog()
            }
        }
    }

    private fun restoreSnapshot(slot: Int, dialog: AlertDialog) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = stateStore.load(slot) ?: return@withContext false
                Core.importState(bytes)
            }
            Toast.makeText(this@EmulationActivity,
                if (ok) R.string.save_state_loaded else R.string.save_state_failed,
                Toast.LENGTH_SHORT).show()
            if (ok) {
                // Closing the drawer resumes emulation from the restored frame.
                dialog.dismiss()
                binding.drawerLayout.close()
            }
        }
    }

    private fun showOverlayOptions() {
        val anchor = binding.inGameMenu.findViewById<View>(R.id.menu_overlay_options)
        val popup = PopupMenu(this, anchor)

        popup.menuInflater.inflate(R.menu.menu_overlay_options, popup.menu)

        popup.menu.apply {
            findItem(R.id.menu_toggle_fps).isChecked = Settings.fpsCounter.value
        }

        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_toggle_fps -> {
                    it.isChecked = !it.isChecked
                    Settings.fpsCounter.value = it.isChecked
                    true
                }
                R.id.menu_edit_touch_layout -> {
                    binding.drawerLayout.close()
                    binding.touchControlsView.beginEditing()
                    Toast.makeText(this, R.string.touch_layout_hint, Toast.LENGTH_LONG).show()
                    true
                }
                R.id.menu_reset_touch_layout -> {
                    binding.touchControlsView.resetPositions()
                    Toast.makeText(this, R.string.touch_layout_reset_done, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> true
            }
        }

        popup.show()
    }

    private fun setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.inGameMenu
        ) { v: View, windowInsets: WindowInsetsCompat ->
            val cutInsets: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            var left = 0
            var right = 0
            if (ViewCompat.getLayoutDirection(v) == ViewCompat.LAYOUT_DIRECTION_LTR) {
                left = cutInsets.left
            } else {
                right = cutInsets.right
            }

            v.updatePadding(left = left, top = cutInsets.top, right = right)
            windowInsets
        }
    }

    private fun keyCodeToGbaKey(keyCode: Int): Int? {
        // TODO: implement mapping support
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> return GbaKey.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> return GbaKey.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> return GbaKey.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> return GbaKey.RIGHT
            KeyEvent.KEYCODE_BUTTON_A -> return GbaKey.A
            KeyEvent.KEYCODE_BUTTON_B -> return GbaKey.B
            KeyEvent.KEYCODE_BUTTON_L1 -> return GbaKey.L
            KeyEvent.KEYCODE_BUTTON_R1 -> return GbaKey.R
            KeyEvent.KEYCODE_BUTTON_START -> return GbaKey.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> return GbaKey.SELECT

            KeyEvent.KEYCODE_X -> return GbaKey.A
            KeyEvent.KEYCODE_Z -> return GbaKey.B
            KeyEvent.KEYCODE_A -> return GbaKey.L
            KeyEvent.KEYCODE_S -> return GbaKey.R
            KeyEvent.KEYCODE_ENTER -> return GbaKey.START
            KeyEvent.KEYCODE_BACKSLASH -> return GbaKey.SELECT
        }

        return null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyDown(keyCode, event)

        val validSources = InputDevice.SOURCE_GAMEPAD or
                InputDevice.SOURCE_JOYSTICK or
                InputDevice.SOURCE_DPAD or
                InputDevice.SOURCE_KEYBOARD

        if ((event.source and validSources) == 0 || event.repeatCount != 0) {
            return super.onKeyDown(keyCode, event)
        }

        val gbaKey = keyCodeToGbaKey(keyCode)
        gbaKey ?: return super.onKeyDown(keyCode, event)
        inputState.press(gbaKey)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyUp(keyCode, event)

        val validSources = InputDevice.SOURCE_GAMEPAD or
                InputDevice.SOURCE_JOYSTICK or
                InputDevice.SOURCE_DPAD or
                InputDevice.SOURCE_KEYBOARD

        if ((event.source and validSources) == 0) {
            return super.onKeyUp(keyCode, event)
        }

        val gbaKey = keyCodeToGbaKey(keyCode)
        gbaKey ?: return super.onKeyUp(keyCode, event)
        inputState.release(gbaKey)
        return true
    }

    private fun enableFullScreenImmersive() {
        with(window) {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            val insetsController = WindowInsetsControllerCompat(this, decorView)
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) attributes.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupGlSurface() {
        glSurfaceView = binding.glSurfaceView
        glSurfaceView.setEGLContextClientVersion(2)
        glRenderer = OpenGLRenderer(frameBuffer, applicationContext)
        glSurfaceView.setRenderer(glRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

    }

    private fun setupTouchControls() {
        binding.touchControlsView.inputState = inputState
        // Changes are applied on the GL render thread on the next frame.
        Settings.glslShader.flow.distinctUntilChanged().collect(this) { mode ->
            glRenderer.requestedShaderId = mode
            glSurfaceView.requestRender()
        }
        Settings.upscalingFilter.flow.distinctUntilChanged().collect(this) { filter ->
            glRenderer.requestedUpscaleId = filter
            glSurfaceView.requestRender()
        }
        Settings.touchLayoutStyle.flow.distinctUntilChanged().collect(this) { style ->
            // Shared mode setting controls BOTH visible hit areas and GL viewport.
            // @Volatile flag is read by the GL thread when it next draws a frame.
            glRenderer.classicMode = (style == 1)
            glRenderer.fullscreenMode = (style == 2)
            binding.touchControlsView.setLayoutStyle(style)
            glSurfaceView.requestRender()
        }
        Settings.touchOpacity.flow.distinctUntilChanged().collect(this) {
            binding.touchControlsView.setOpacity(it)
        }
        Settings.touchSize.flow.distinctUntilChanged().collect(this) {
            binding.touchControlsView.setSize(it)
        }
    }

    private fun loadRomFromUri(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // stop any previously running emulation thread before loading
                stopEmulationThread()
                val ok = Core.loadRom(uri)

                if (ok) {
                    if (!Settings.skipBios.value) {
                        if (BiosStore.has(Core.getPlatform())) {
                            val biosFd = BiosStore.load(Core.getPlatform())
                            if (biosFd != null) {
                                if (!Core.loadBios(biosFd)) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@EmulationActivity,
                                            "Imported BIOS was rejected",
                                            Toast.LENGTH_SHORT
                                        )
                                            .show()
                                    }
                                }
                            }
                        }
                    }

                    val save = SaveDataStore.load(currentGame?.fileName ?: "")
                    val saveOk = Core.loadSaveData(save)
                    if (!saveOk) {
                        // expected when playing for the first time
                        Log.w(LOG_TAG, "could not restore save data")
                    }
                    Core.reset()
                    Log.i(LOG_TAG, "ROM loaded ${Core.gameTitle()}, ${Core.gameCode()}")
                    startEmulationThread()
                } else {
                    Log.w(LOG_TAG, "Core rejected ROM")
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error reading ROM: ${e.message}")
            }
        }
    }

    private fun persistSaveData() {
        val fileName = currentGame?.fileName ?: return
        val saveBytes = Core.exportSaveData()
        if (saveBytes.isNotEmpty()) {
            SaveDataStore.save(fileName, saveBytes)
        }
    }

    private fun startEmulationThread() {
        val thread = EmulationThread(
            inputState = inputState,
            frameBuffer = frameBuffer,
            onFrameReady = { glSurfaceView.requestRender() },
            onFpsUpdated = { currentFps ->
                if (Settings.fpsCounter.value) {
                    runOnUiThread {
                        binding.fps.text = String.format("%.1f FPS", currentFps)
                    }
                }
            }
        )
        emulationThread = thread
        thread.start()
    }

    private fun stopEmulationThread() {
        emulationThread?.let { thread ->
            thread.requestStop()
            thread.join(THREAD_JOIN_TIMEOUT_MS)
        }
        emulationThread = null
    }

    private fun pauseEmulation() {
        emulationThread?.paused = true
        glSurfaceView.onPause()
        persistSaveData()
    }

    private fun resumeEmulation() {
        glSurfaceView.onResume()
        emulationThread?.paused = false
    }

    override fun onPause() {
        super.onPause()
        pauseEmulation()
    }

    override fun onResume() {
        super.onResume()
        resumeEmulation()
    }

    override fun onDestroy() {
        super.onDestroy()
        persistSaveData()
        stopEmulationThread()
        Core.shutdown()
        ConfigManager.gameFileName = null
        ConfigManager.shouldHandleInGameMenu = false
    }

    companion object {
        private const val LOG_TAG = "EmulationActivity"
        private const val THREAD_JOIN_TIMEOUT_MS = 500L
    }
}