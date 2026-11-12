package com.codescv.delta

import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.codescv.delta.input.GameInputRouter
import com.codescv.delta.ui.DeltaApp
import com.codescv.delta.ui.DeltaNavigator
import com.codescv.delta.update.LaunchUpdateCheck
import com.codescv.delta.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val navigator = DeltaNavigator()

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = GameInputRouter.deviceAdded()
        override fun onInputDeviceRemoved(deviceId: Int) = GameInputRouter.deviceRemoved(deviceId)
        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DeltaApp(navigator)
            LaunchUpdateCheck()
        }
        (getSystemService(INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, null)
        if (savedInstanceState == null) handleIncoming(intent)
    }

    companion object {
        const val EXTRA_LAUNCH_ROM = "rom"
        const val EXTRA_RESUME_STATE = "state"
    }

    /**
     * Resumes a pending APK install after "Install unknown apps" was granted (also after process
     * death) and silently re-checks GitHub when returning to the foreground.
     */
    override fun onResume() {
        super.onResume()
        UpdateManager.get(this).onHostResume(this)
    }

    override fun onDestroy() {
        (getSystemService(INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        GameInputRouter.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        GameInputRouter.onMotionEvent(event) || super.dispatchGenericMotionEvent(event)

    /** "Open in Delta" / share of a ROM, .zip or .deltaskin; `--es rom <file>` launches a game. */
    private fun handleIncoming(intent: Intent?) {
        intent?.getStringExtra(EXTRA_LAUNCH_ROM)?.let { name ->
            launchByName(name, intent.getStringExtra(EXTRA_RESUME_STATE))
            return
        }
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            else -> null
        } ?: return
        val container = DeltaApplication.container
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                runCatching {
                    val name = displayName(uri) ?: uri.lastPathSegment ?: "game"
                    contentResolver.openInputStream(uri)?.use { input ->
                        if (name.endsWith(".deltaskin", ignoreCase = true)) {
                            val skin = container.skins.importDeltaSkin(input, name, null)
                            "Imported ${skin.skin.name}"
                        } else {
                            val imported = container.library.import(input, name)
                            "Imported ${imported.size} game(s)"
                        }
                    } ?: "Unable to open $name"
                }.getOrElse { it.message ?: "Import failed" }
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Automation hook: launch a library game by file name or path (rescanning ROMs/ first). */
    private fun launchByName(name: String, resumeState: String?) {
        val container = DeltaApplication.container
        lifecycleScope.launch {
            val rom = withContext(Dispatchers.IO) {
                val match = { container.library.value.entries.firstOrNull { it.fileName == name || it.path == name || it.title == name } }
                match() ?: run {
                    container.library.rescan()
                    match()
                }
            }
            if (rom != null) navigator.play(rom, resumeState) else Toast.makeText(this@MainActivity, "No game named $name", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
