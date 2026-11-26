/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.download

import app.spookd.patches.instagram.entity.originalSoundDataIntf.originalSoundDataIntfEntity
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.DOWNLOAD_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

private const val IGDS_ACTION_BAR_CLASS = "Lcom/instagram/igds/components/actionbar/IgdsActionBar;"

/**
 * Binds the original audio page's action bar: its action buttons and its trending pill.
 */
internal object AudioPageActionBarBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(
        string("android_purge_26_q2_AudioPageActionBarHandler_bindIgdsActionBar"),
        // The page's action bar.
        fieldAccess(type = IGDS_ACTION_BAR_CLASS, opcode = Opcode.IGET_OBJECT),
    ),
)

val audioPageDownloadButtonPatch =
    patch(
        description = "Adds a download button to the action bar of original audio pages.",
    ) {
        dependsOn(originalSoundDataIntfEntity)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Optional: without the hook the setting stays hidden and nothing else changes.
            AudioPageActionBarBindFingerprint.matchOrNull()?.method?.apply {
                addInstruction(
                    0,
                    "invoke-static/range { p0 .. p0 }, $DOWNLOAD_DESCRIPTOR/AudioPageDownload;->onActionBarBound(Ljava/lang/Object;)V",
                )
                enableSettings("audioPageDownloadButton")
            }
        }
    }
