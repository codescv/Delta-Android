package com.codescv.delta.update

import java.io.IOException

/** Cooperative cancellation for blocking HTTP calls: [cancel] aborts the attached connection. */
class CancelToken {
    @Volatile
    var isCancelled: Boolean = false
        private set

    private var abort: (() -> Unit)? = null

    /** Cancels; the currently attached request (if any) is aborted immediately. */
    fun cancel() {
        val action: (() -> Unit)?
        synchronized(this) {
            isCancelled = true
            action = abort
            abort = null
        }
        runCatching { action?.invoke() }
    }

    /**
     * Registers [onAbort] for the running request. If [cancel] already happened before attaching,
     * invokes [onAbort] right away and throws [IOException] to close the cancel-before-attach race.
     */
    fun attach(onAbort: () -> Unit) {
        synchronized(this) {
            if (!isCancelled) {
                abort = onAbort
                return
            }
        }
        runCatching { onAbort() }
        throw IOException("cancelled")
    }

    fun detach() {
        synchronized(this) {
            abort = null
        }
    }
}
