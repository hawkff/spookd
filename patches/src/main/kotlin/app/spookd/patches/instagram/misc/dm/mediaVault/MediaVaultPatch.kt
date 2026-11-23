/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.dm.mediaVault

import app.spookd.patches.instagram.entity.directItem.DirectItemDispatchFingerprint
import app.spookd.patches.instagram.entity.directItem.directItemEntity
import app.spookd.patches.instagram.entity.directItem.jsonKeyFieldFingerprint
import app.spookd.patches.instagram.misc.dm.saveMessages.saveDeletedMessagesPatch
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.crimera.utils.changeFirstString
import app.crimera.utils.classNameToExtension
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch

@Suppress("unused")
val mediaVaultPatch =
    patch(
        name = "Save DM media",
        description = "Adds an option to save received DM photos, videos and voice messages encrypted on the device, so unsent ones can be viewed behind the screen lock. View-once media is never saved.",
    ) {
        dependsOn(saveDeletedMessagesPatch, directItemEntity, settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val parserClass = DirectItemDispatchFingerprint.originalClassDef.type
            val visualField = jsonKeyFieldFingerprint("visual_media", parserClass).instructionMatches.last().fieldReference
            val voiceField = jsonKeyFieldFingerprint("voice_media", parserClass).instructionMatches.last().fieldReference

            val itemClass = visualField.definingClass
            if (voiceField.definingClass != itemClass) {
                throw PatchException("Wrapper fields are declared on $itemClass and ${voiceField.definingClass}")
            }
            ItemClassNameExtension.changeFirstString(classNameToExtension(itemClass))

            // Every item field of a wrapper type, so message_item_dict is covered along with visual_media.
            val itemFields = classDefBy(itemClass).fields
            listOf(
                Triple(visualField.type, VisualFieldsExtension, VisualModeFieldExtension),
                Triple(voiceField.type, VoiceFieldsExtension, VoiceModeFieldExtension),
            ).forEach { (wrapperType, fieldsExtension, modeExtension) ->
                fieldsExtension.changeFirstString(
                    itemFields.filter { it.type == wrapperType }.joinToString(",") { it.name },
                )
                modeExtension.changeFirstString(
                    wrapperSerializerFingerprint(wrapperType).instructionMatches[VIEW_MODE_FIELD_FILTER].fieldReference.name,
                )
            }

            enableSettings("dmMediaVault")
        }
    }
