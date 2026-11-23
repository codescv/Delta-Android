package com.codescv.delta.sync

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.codescv.delta.MainActivity

/**
 * Translucent, no-history trampoline activity that receives Google's Android OAuth 2.0 custom
 * URI scheme redirect (`com.googleusercontent.apps.<id>:/oauth2redirect?...`), delivers the URI
 * to [OAuthRedirectCoordinator.Default], brings [MainActivity] back to the foreground, and
 * finishes immediately without leaving a blank screen.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleRedirectIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRedirectIntent(intent)
    }

    private fun handleRedirectIntent(intent: Intent?) {
        val rawUri = intent?.dataString
        if (!rawUri.isNullOrBlank()) {
            OAuthRedirectCoordinator.Default.deliver(rawUri)
        }
        val bringToFront = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(bringToFront)
        finish()
    }
}
