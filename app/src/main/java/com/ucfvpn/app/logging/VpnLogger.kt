package com.ucfvpn.app.logging

import com.ucfvpn.app.state.VpnState
import com.ucfvpn.app.state.VpnStateMachine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Central logging and error-handling singleton for the UCF VPN app.
 *
 * Responsibilities:
 * - Structured logging with categories and severity levels.
 * - Circular buffer storage (200 entries) for in-app log viewing.
 * - Timeout detection per connection stage with automatic error transitions.
 * - Error classification (recoverable vs fatal) for reconnect decisions.
 * - User-friendly error messages for UI display.
 * - Delegates to Timber for Android system log output.
 *
 * Usage:
 * ```
 * // Initialize early (Application.onCreate or DI setup)
 * VpnLogger.init(stateMachine)
 *
 * // Stage lifecycle (timeouts are automatic)
 * VpnLogger.logStageStart(ConnectionStage.SSTP_CONNECTING)
 * // ... do work ...
 * VpnLogger.logStageComplete(ConnectionStage.SSTP_CONNECTING)
 *
 * // General logging
 * VpnLogger.info(LogCategory.SSTP, "Starting handshake")
 * VpnLogger.error(LogCategory.PROXY, "Auth rejected", VpnError.AuthFailed("bad credentials"))
 *
 * // Observe entries
 * VpnLogger.entries.collect { entries -> ... }
 * ```
 */
object VpnLogger {

    private const val DEFAULT_MAX_SIZE = 200

    // ── Core ────────────────────────────────────────────────────

    private val repository = LogRepository(DEFAULT_MAX_SIZE)

    /** Observable list of all log entries in the circular buffer. */
    val entries get() = repository.entries

    /** Direct access to the repository for advanced queries. */
    val logRepository: LogRepository get() = repository

    /** The state machine reference for error transitions on timeout. */
    private var stateMachine: VpnStateMachine? = null

    // ── Timeout watchers ────────────────────────────────────────

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val timeoutJobs = ConcurrentHashMap<ConnectionStage, Job>()

    // ── Initialization ──────────────────────────────────────────

    /**
     * Initialize the logger with a reference to the state machine.
     * Also plants a Timber debug tree if none is already planted.
     *
     * @param machine The [VpnStateMachine] used for automatic error transitions.
     */
    fun init(machine: VpnStateMachine) {
        stateMachine = machine
        if (Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
            info(LogCategory.SYSTEM, "VpnLogger initialized — Timber DebugTree planted")
        } else {
            info(LogCategory.SYSTEM, "VpnLogger initialized")
        }
    }

    /**
     * Reinitialize with a new state machine after recreation.
     */
    fun reinit(machine: VpnStateMachine) {
        cancelAllTimeouts()
        stateMachine = machine
        info(LogCategory.SYSTEM, "VpnLogger reinitialized")
    }

    /**
     * Shutdown the logger, cancelling all timeout watchers and the coroutine scope.
     */
    fun shutdown() {
        cancelAllTimeouts()
        scope.cancel()
        info(LogCategory.SYSTEM, "VpnLogger shutdown")
    }

    // ── Stage lifecycle ─────────────────────────────────────────

    /**
     * Signal that a connection stage has started.
     * Records the event and starts a timeout watcher.
     */
    fun logStageStart(stage: ConnectionStage) {
        cancelStageTimeout(stage)
        info(stage.toLogCategory(), "[STAGE START] ${stage.displayName}")
        startStageTimeout(stage)
    }

    /**
     * Signal that a connection stage completed successfully.
     * Cancels the timeout watcher and records the success.
     */
    fun logStageComplete(stage: ConnectionStage) {
        cancelStageTimeout(stage)
        info(stage.toLogCategory(), "[STAGE OK] ${stage.displayName} completed")
    }

    /**
     * Manually cancel a stage timeout without logging completion.
     */
    fun cancelStageTimeout(stage: ConnectionStage) {
        timeoutJobs.remove(stage)?.cancel()
    }

    /** Cancel all pending stage timeouts. */
    fun cancelAllTimeouts() {
        timeoutJobs.values.forEach { it.cancel() }
        timeoutJobs.clear()
    }

    // ── General logging ─────────────────────────────────────────

    fun debug(category: LogCategory, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, category, message, throwable)
    }

    fun info(category: LogCategory, message: String) {
        log(LogLevel.INFO, category, message, null)
    }

    fun warn(category: LogCategory, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARN, category, message, throwable)
    }

    fun error(
        category: LogCategory,
        message: String,
        error: VpnError? = null,
        throwable: Throwable? = null
    ) {
        log(LogLevel.ERROR, category, message, throwable, error)
    }

    fun fatal(
        category: LogCategory,
        message: String,
        error: VpnError? = null,
        throwable: Throwable? = null
    ) {
        log(LogLevel.FATAL, category, message, throwable, error)
    }

    // ── Error handling helpers ──────────────────────────────────

    /**
     * Log an exception and classify it into a [VpnError].
     * If the error is fatal, transitions the state machine to the appropriate error state.
     *
     * @return The classified [VpnError] for further handling by the caller.
     */
    fun logErrorAndClassify(
        category: LogCategory,
        throwable: Throwable,
        stage: ConnectionStage? = null
    ): VpnError {
        val vpnError = VpnError.fromException(throwable, stage)
        log(LogLevel.ERROR, category, throwable.message ?: "Unknown error", throwable, vpnError)

        if (vpnError.classification == ErrorClassification.FATAL && stage != null) {
            scope.launch {
                stateMachine?.transition(stage.toErrorState(vpnError.userFriendlyMessage))
            }
        }

        return vpnError
    }

    /**
     * Log a typed [VpnError] and optionally trigger a state machine transition.
     *
     * @param stage If provided and error is fatal, the state machine transitions.
     */
    fun logVpnError(
        category: LogCategory,
        vpnError: VpnError,
        stage: ConnectionStage? = null
    ) {
        log(LogLevel.ERROR, category, vpnError.message, null, vpnError)

        if (vpnError.classification == ErrorClassification.FATAL && stage != null) {
            scope.launch {
                stateMachine?.transition(stage.toErrorState(vpnError.userFriendlyMessage))
            }
        } else if (vpnError.classification == ErrorClassification.RECOVERABLE && stage != null) {
            // Recoverable errors: log but don't transition — let ReconnectManager handle it
            scope.launch {
                val currentState = stateMachine?.getCurrentState()
                if (currentState != null && !currentState.isError) {
                    stateMachine?.transition(stage.toErrorState(vpnError.userFriendlyMessage))
                }
            }
        }
    }

    // ── Query helpers ───────────────────────────────────────────

    /** Get recent error entries. */
    fun getRecentErrors(n: Int = 20): List<LogEntry> =
        repository.getByMinLevel(LogLevel.ERROR).takeLast(n)

    /** Get recent entries for a specific category. */
    fun getRecentByCategory(category: LogCategory, n: Int = 50): List<LogEntry> =
        repository.getByCategory(category).takeLast(n)

    /** Get the most recent log entries. */
    fun getRecentEntries(n: Int = 50): List<LogEntry> =
        repository.getRecent(n)

    /** Clear the log buffer. */
    fun clearLogs() {
        repository.clear()
        info(LogCategory.SYSTEM, "Log buffer cleared")
    }

    /** Export all logs as a string (for clipboard / sharing). */
    fun dumpLogs(): String = repository.dumpToString()

    // ── Internal ────────────────────────────────────────────────

    private fun startStageTimeout(stage: ConnectionStage) {
        cancelStageTimeout(stage)
        timeoutJobs[stage] = scope.launch {
            delay(stage.timeoutMs)

            val error = VpnError.Timeout(stage)
            log(LogLevel.ERROR, stage.toLogCategory(), error.message, vpnError = error)

            stateMachine?.let { machine ->
                val errorState = stage.toErrorState(error.userFriendlyMessage)
                machine.transition(errorState)
            }
        }
    }

    private fun log(
        level: LogLevel,
        category: LogCategory,
        message: String,
        throwable: Throwable? = null,
        vpnError: VpnError? = null
    ) {
        val entry = LogEntry(
            level = level,
            category = category,
            message = message,
            error = vpnError,
            exception = throwable
        )
        repository.addBlocking(entry)

        // Timber output
        val tag = "UCF-${category.name}"
        when (level) {
            LogLevel.DEBUG -> Timber.tag(tag).d(message)
            LogLevel.INFO -> Timber.tag(tag).i(message)
            LogLevel.WARN -> Timber.tag(tag).w(throwable, message)
            LogLevel.ERROR -> Timber.tag(tag).e(throwable, message)
            LogLevel.FATAL -> Timber.tag(tag).e(throwable, message)
        }
    }

}
