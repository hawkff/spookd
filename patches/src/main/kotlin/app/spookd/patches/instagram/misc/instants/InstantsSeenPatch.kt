/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.instants

import app.spookd.patches.instagram.entity.mediadata.mediaDataEntity
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.INSTANTS_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel

private const val INSTANTS_SEEN_DESCRIPTOR = "$INSTANTS_DESCRIPTOR/InstantsSeen;"

@Suppress("unused")
val instantsSeenPatch =
    patch(
        name = "Instants seen",
        description = "Adds options to keep Instants unseen and to choose senders whose Instants are always or never " +
            "marked seen, with quick toggles in the Home long-press menu.",
    ) {
        dependsOn(settingsPatch, mediaDataEntity)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // The seen report carries only media pks, so each Instant's sender is noted as its item is built.
            instantItemConstructor().addInstruction(
                0,
                "invoke-static/range {p1 .. p1}, $INSTANTS_SEEN_DESCRIPTOR->noteInstant(Ljava/lang/Object;)V",
            )

            // p1 is the media ids; Instagram passes null when it resumes the call, which goes on untouched.
            // A null result reports none, which Instagram handles as a failed report.
            val report = InstantsSeenReportFingerprint.method
            report.addInstructionsWithLabels(
                0,
                """
                if-eqz p1, :instants_seen_report
                invoke-static/range {p1 .. p1}, $INSTANTS_SEEN_DESCRIPTOR->keptSeenIds(Ljava/util/List;)Ljava/util/List;
                move-result-object p1
                if-nez p1, :instants_seen_report
                return-object p1
                """.trimIndent(),
                ExternalLabel("instants_seen_report", report.getInstruction(0)),
            )

            enableSettings("instantsSeen")
        }
    }
