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
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val CLIPS_VIEWER_ACTION_BAR = "Linstagram/features/clips/viewer/actionbar/ClipsViewerActionBar;"

// Binds the Reels header to its viewer's model, which names the surface the viewer was opened from.
private object ReelsHeaderBindFingerprint : Fingerprint(
    definingClass = CLIPS_VIEWER_ACTION_BAR,
    returnType = "V",
    filters = listOf(
        string("android_purge_26_q2_ClipsViewerActionBar_bindComponents"),
        // The header's current model, read from the header.
        fieldAccess(opcode = Opcode.IGET_OBJECT, definingClass = CLIPS_VIEWER_ACTION_BAR),
        fieldAccess(opcode = Opcode.IGET_OBJECT, type = "Lcom/instagram/clips/intf/ClipsViewerSource;"),
    ),
)

@Suppress("unused")
val hideReelsTabHeaderPatch =
    patch(
        name = "Hide Reels tab header",
        description = "Adds an option to hide the header at the top of the Reels tab.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            ReelsHeaderBindFingerprint.let {
                val header = it.instructionMatches[1].getInstruction<TwoRegisterInstruction>().registerB
                val sourceRead = it.instructionMatches[2]
                val source = sourceRead.getInstruction<TwoRegisterInstruction>().registerA
                if (header > 15 || source > 15) throw PatchException("Unexpected Reels header registers")
                it.method.addInstruction(
                    sourceRead.index + 1,
                    "invoke-static {v$header, v$source}, " +
                        "$DISTRACTION_FREE_HIDES->bindReelsHeader(Landroid/view/View;Ljava/lang/Object;)V",
                )
            }

            // Instagram shows and hides the header from several places, so the header itself keeps a hidden one hidden.
            mutableClassDefBy(CLIPS_VIEWER_ACTION_BAR).methods.apply {
                if (any { it.name == "setVisibility" }) throw PatchException("Reels header already overrides setVisibility")
                add(
                    ImmutableMethod(
                        CLIPS_VIEWER_ACTION_BAR,
                        "setVisibility",
                        listOf(ImmutableMethodParameter("I", null, null)),
                        "V",
                        AccessFlags.PUBLIC.value,
                        emptySet(),
                        emptySet(),
                        MutableMethodImplementation(2),
                    ).toMutable().apply {
                        addInstructions(
                            0,
                            """
                            invoke-static {p0, p1}, $DISTRACTION_FREE_HIDES->reelsHeaderVisibility(Landroid/view/View;I)I
                            move-result p1
                            invoke-super {p0, p1}, Landroid/widget/RelativeLayout;->setVisibility(I)V
                            return-void
                            """.trimIndent(),
                        )
                    },
                )
            }

            enableSettings("hideReelsTabHeader")
        }
    }
