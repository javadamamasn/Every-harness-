package com.harnesshub.termux

/**
 * Pure Termux onboarding logic (unit tested). Android-side detection feeds
 * [TermuxState]; this module decides which wizard step is next and what exact
 * command reproduces it — no Android dependencies here.
 *
 * Verified facts used here (docs/DECISIONS.md):
 * - Termux exposes the RUN_COMMAND intent (com.termux / com.termux.RUN_COMMAND)
 *   only when both: the app holds com.termux.permission.RUN_COMMAND AND the user
 *   enabled allow-external-apps=true in ~/.termux/termux.properties.
 * - The bridge lives at ~/.harnesshub/bridge/bridge.js and its launcher writes
 *   the port as BRIDGE_PORT=<n> on stdout; the token file is 0600.
 */
object TermuxGateway {

    const val TERMUX_PACKAGE = "com.termux"
    const val RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
    const val PENDING_NEEDED = "PENDING"
    const val STEP_NEEDED = 7

    enum class Status { DONE, ACTION, PENDING }

    data class TermuxState(
        val termuxInstalled: Boolean? = null,
        val runCommandPermission: Boolean? = null,
        /** tri-state: user flag exists in ~/.termux/termux.properties (probed via bridge or manual confirm). */
        val externalAppsEnabled: Boolean? = null,
        val bridgeReachable: Boolean? = null,
    )

    data class WizardStepResult(
        val step: WizardStep,
        val status: Status,
        /** Exact shell command the user (or the RUN_COMMAND intent) will run. */
        val command: String? = null,
    )

    enum class WizardStep {
        INSTALL_TERMUX,
        GRANT_PERMISSION,
        ENABLE_EXTERNAL_APPS,
        INSTALL_BRIDGE,
        START_BRIDGE,
        WAKE_LOCK,
        HEALTH_TEST,
    }

    fun computeSteps(state: TermuxState): List<WizardStepResult> {
        return listOf(
            WizardStepResult(
                WizardStep.INSTALL_TERMUX,
                if (state.termuxInstalled == true) Status.DONE else Status.ACTION,
            ),
            WizardStepResult(
                WizardStep.GRANT_PERMISSION,
                if (state.runCommandPermission == true) Status.DONE else Status.ACTION,
            ),
            WizardStepResult(
                WizardStep.ENABLE_EXTERNAL_APPS,
                statusOf(state.externalAppsEnabled),
            ),
            WizardStepResult(
                WizardStep.INSTALL_BRIDGE,
                statusOf(backed(state.externalAppsEnabled, state.bridgeReachable)),
                command = "bash ~/.harnesshub/install-bridge.sh",
            ),
            WizardStepResult(
                WizardStep.START_BRIDGE,
                statusOf(backed(state.externalAppsEnabled, state.bridgeReachable)),
                command = "\$HOME/bin/harness-hub-bridge-start",
            ),
            WizardStepResult(
                WizardStep.WAKE_LOCK,
                statusOf(state.bridgeReachable),
                command = "termux-wake-lock",
            ),
            WizardStepResult(
                WizardStep.HEALTH_TEST,
                statusOf(state.bridgeReachable),
            ),
        )
    }

    private fun statusOf(v: Boolean?): Status = when (v) {
        true -> Status.DONE
        false -> Status.ACTION
        null -> Status.PENDING
    }

    private fun backed(first: Boolean?, second: Boolean?): Boolean? = when {
        first == true && second == true -> true
        first == false || second == false -> false
        else -> null
    }

    /** Builds the extra bundle the RUN_COMMAND intent expects (verified key names). */
    fun runCommandExtras(command: List<String>, workdir: String = "/data/data/com.termux/files/home"): Map<String, String> =
        mapOf(
            "com.termux.RUN_COMMAND_PATH" to "/data/data/com.termux/files/usr/bin/bash",
            "com.termux.RUN_COMMAND_ARGUMENTS" to command.joinToString("\u0000"),
            "com.termux.RUN_COMMAND_WORKDIR" to workdir,
            "com.termux.RUN_COMMAND_BACKGROUND" to "true",
        )

    fun onFailure(state: TermuxState): List<String> {
        return buildList {
            if (state.termuxInstalled == false) add("termux_missing")
            if (state.runCommandPermission == false) add("permission_missing")
            if (state.externalAppsEnabled == false) add("allow_external_apps")
            if (state.bridgeReachable == false) add("bridge_down")
        }
    }
}
