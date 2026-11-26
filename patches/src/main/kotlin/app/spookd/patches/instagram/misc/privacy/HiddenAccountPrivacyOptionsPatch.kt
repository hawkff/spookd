/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.privacy

import app.spookd.patches.instagram.misc.hookFlags.hookFlagsPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.addFlags
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private const val HIDDEN_ACCOUNT_PRIVACY_OPTIONS_DESCRIPTOR =
    "$PATCHES_DESCRIPTOR/privacy/HiddenAccountPrivacyOptions;"

// Fetches one server value an Instagram settings screen depends on, such as whether a row is offered to the account.
internal object SettingsServerValueFetchFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/settings2/core/model/ServerValue;",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(USER_SESSION_CLASS, "L"),
    strings = listOf("SettingsAPIGetScreenDependencies"),
)

@Suppress("unused")
val hiddenAccountPrivacyOptionsPatch =
    patch(
        name = "Hidden account privacy options",
        description = "Adds an option to show Instagram's own options to hide your followers and following lists " +
            "and to hide you from other people's lists in Account privacy.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch, hookFlagsPatch)

        execute {
            SettingsServerValueFetchFingerprint.method.apply {
                // The value's name, the class's only String field.
                val nameField = SettingsServerValueFetchFingerprint.classDef.fields.single {
                    it.type == "Ljava/lang/String;" && !AccessFlags.STATIC.isSet(it.accessFlags)
                }
                addInstructionsWithLabels(
                    0,
                    """
                    iget-object v0, p0, $nameField
                    invoke-static {v0}, $HIDDEN_ACCOUNT_PRIVACY_OPTIONS_DESCRIPTOR->serverValue(Ljava/lang/String;)Ljava/lang/Boolean;
                    move-result-object v0
                    if-eqz v0, :spookd_server_value
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("spookd_server_value", getInstruction(0)),
                )
            }

            addFlags("hiddenAccountPrivacyOptionsFlags")
            enableSettings("hiddenAccountPrivacyOptions")
        }
    }
