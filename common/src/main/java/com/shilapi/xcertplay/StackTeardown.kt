package com.shilapi.xcertplay

/**
 * Closes one CarPlay stack and always reports completion, even when a close step throws.
 *
 * `CarPlayHostActivity.restartCarPlay` uses [onComplete] to clear its `handshakeResetInProgress`
 * latch. That latch must never stick: every later reconnect early-returns on it, so a latch left
 * set is a permanent dead state until the app restarts — the exact "won't reconnect automatically"
 * failure this path is meant to prevent. Each step is guarded on its own so a failing close cannot
 * skip the steps after it either.
 */
internal fun teardownCarPlayStack(
    closeController: () -> Unit,
    awaitControllerClosed: () -> Unit,
    closeSink: () -> Unit,
    reportFailure: (message: String, error: Throwable) -> Unit,
    onComplete: () -> Unit,
) {
    try {
        try {
            closeController()
        } catch (error: Throwable) {
            reportFailure("controller teardown failed", error)
        }
        try {
            awaitControllerClosed()
        } catch (error: Throwable) {
            reportFailure("controller close wait failed", error)
        }
        try {
            closeSink()
        } catch (error: Throwable) {
            reportFailure("media sink teardown failed", error)
        }
    } finally {
        onComplete()
    }
}
