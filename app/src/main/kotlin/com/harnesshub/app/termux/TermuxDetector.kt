package com.harnesshub.app.termux

import android.content.Context
import android.content.pm.PackageManager
import com.harnesshub.termux.TermuxGateway

/**
 * Live detection feeding the pure [TermuxGateway] state machine. Everything the
 * app can detect without the bridge is probed here; the rest stays PENDING
 * until the bridge itself answers (docs/DECISIONS.md).
 */
object TermuxDetector {

    data class Detected(
        val termuxInstalled: Boolean = false,
        val runCommandPermission: Boolean = false,
    )

    fun detect(context: Context): Detected {
        val installed = try {
            context.packageManager.getPackageInfo(TermuxGateway.TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        val permission = context.checkSelfPermission(TermuxGateway.RUN_COMMAND_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        return Detected(termuxInstalled = installed, runCommandPermission = permission)
    }

    fun state(detected: Detected): TermuxGateway.TermuxState = TermuxGateway.TermuxState(
        termuxInstalled = detected.termuxInstalled,
        runCommandPermission = detected.runCommandPermission,
    )
}
