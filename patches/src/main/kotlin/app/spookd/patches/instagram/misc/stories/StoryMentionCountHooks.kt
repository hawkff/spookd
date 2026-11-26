/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.stories

import app.spookd.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.spookd.patches.instagram.misc.download.parameterRegister
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val REEL_ITEM_CLASS = "Lcom/instagram/model/reels/ReelItem;"

/**
 * Hands each story menu to the extension as it is built, so the mentions entry can show a count.
 * Optional: without these hooks the entry keeps its plain label.
 */
internal fun addStoryMentionCountHooks(menuBuilders: List<MutableMethod>, storyButtonClass: String) {
    val menuClass = menuBuilders.first().definingClass
    val itemField = classDefBy(menuClass).fields.singleOrNull { it.type == REEL_ITEM_CLASS } ?: return
    val mediaField = menuBuilders
        .flatMap { builder -> builder.instructions.mapNotNull { it.getReference<FieldReference>() } }
        .filter { it.definingClass == REEL_ITEM_CLASS && it.type == MEDIA_CLASS_NAME }
        .distinctBy { it.toString() }
        .singleOrNull() ?: return

    Fingerprint(definingClass = storyButtonClass, name = "menuMediaNative").method.addInstructions(
        0,
        """
        check-cast p0, $menuClass
        iget-object p0, p0, $itemField
        iget-object p0, p0, $mediaField
        return-object p0
        """.trimIndent(),
    )
    menuBuilders
        .filter { builder -> builder.parameterTypes.any { it.toString() == menuClass } }
        .forEach { builder ->
            val menuRegister = builder.parameterRegister(menuClass)
            builder.addInstruction(
                0,
                "invoke-static/range {v$menuRegister .. v$menuRegister}, $storyButtonClass->captureMenu(Ljava/lang/Object;)V",
            )
        }
}
