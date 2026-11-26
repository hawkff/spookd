/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.literal
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

// Shows or hides the inbox Instants stack; inflates and binds it the first time it is shown.
private object InboxInstantsVisibilityFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Z"),
    filters = listOf(
        // A quicksnap config read on first show, specifier 0x810cb5001a464d.
        literal(36324243526010445L),
        string("recreated"),
    ),
)

@Suppress("unused")
val hideInboxInstantsPatch =
    patch(
        name = "Hide Instants in inbox",
        description = "Adds an option to hide the Instants stack in the inbox.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            // p2 is whether to show the stack; false is what Instagram passes when there is nothing to show.
            InboxInstantsVisibilityFingerprint.method.addInstructions(
                0,
                """
                invoke-static/range {p2 .. p2}, $DISTRACTION_FREE_HIDES->showInboxInstants(Z)Z
                move-result p2
                """.trimIndent(),
            )

            enableSettings("hideInboxInstants")
        }
    }
