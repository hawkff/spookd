package app.spookd.patches.instagram.misc.profile

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

// Tap on the profile Reels tab, which opens its sort menu.
internal object ClipsProfileTabClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Z"),
    strings = listOf("android_purge_26_q2_ClipsProfileTabProvider_onTabClicked"),
)

// Whether the Reels tab offers the Most viewed sort; Instagram works it out once per profile.
internal object ProfileReelsMostViewedFingerprint : Fingerprint(
    classFingerprint = ClipsProfileTabClickFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("L"),
    filters = listOf(methodCall(smali = "Ljava/lang/Boolean;->booleanValue()Z")),
    custom = { method, classDef -> method.parameterTypes.single().toString() == classDef.type },
)

@Suppress("unused")
val profileReelsSortPatch =
    patch(
        name = "Profile Reels sort",
        description = "Adds an option to keep or remove the Most viewed sort on the Reels tab of profiles.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ProfileReelsMostViewedFingerprint.method.apply {
                val returnIndex = instructions.indexOfLast { it.opcode == Opcode.RETURN }
                if (returnIndex < 0) throw PatchException("Most viewed sort check has no return")
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                    invoke-static {v$register}, $PROFILE_EXTRAS->profileReelsMostViewed(Z)Z
                    move-result v$register
                    """.trimIndent(),
                )
            }

            enableSettings("profileReelsSort")
        }
    }
