/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.confirm

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.classDefByOrNull
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.writeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

// The feed repost button (reposts_ufi_icon) and the story heart (toolbar_like_button) are both this class.
private const val UFI_BUTTON = "Lcom/instagram/ui/widget/bouncyufibutton/IgBouncyUfiButtonImageView;"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

// The story heart component's name.
private object StoryLikeComponentNameFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    filters = listOf(string("StoryLikeButtonViewComponent")),
)

/**
 * Reports the story heart's presenter from the component's constructor, and every presenter bind of
 * that presenter's base class, so a prompt knows which story it was asked for.
 */
private fun hookStoryLikeBinds(component: String): Boolean {
    val componentClass = mutableClassDefBy(component)
    val button = componentClass.fields.singleOrNull {
        it.type == UFI_BUTTON && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return false
    val init = componentClass.methods.singleOrNull { it.name == "<init>" } ?: return false
    val instructions = init.implementation?.instructions ?: return false
    if (init.parameterTypes.any { it.toString() == "J" || it.toString() == "D" }) return false
    val self = init.implementation!!.registerCount - init.parameterTypes.size - 1

    // The superclass keeps the presenter passed to its constructor.
    val superCall = instructions.indexOfFirst {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_DIRECT && call?.name == "<init>" && call?.definingClass == componentClass.superclass
    }
    val superArgs = instructions.getOrNull(superCall) as? FiveRegisterInstruction ?: return false
    if (superArgs.registerCount != 2) return false
    val presenter = superArgs.registerD
    if (presenter <= self || presenter > 15 || instructions.any { it.writeRegister == presenter }) return false
    val presenterType = init.parameterTypes[presenter - self - 1].toString()

    // The button store, which nothing branches past.
    val store = instructions.indexOfFirst {
        val field = (it as? ReferenceInstruction)?.reference as? FieldReference
        it.opcode == Opcode.IPUT_OBJECT && field?.name == button.name && field?.definingClass == component
    }
    if (store < 0 || instructions.any { (it as? BuilderOffsetInstruction)?.target?.location?.index == store + 1 }) return false
    val buttonRegister = (instructions[store] as TwoRegisterInstruction).registerA
    if (buttonRegister > 15) return false

    // The presenter base binds one model, which holds the ReelItem.
    val base = classDefByOrNull(presenterType)?.superclass ?: return false
    val baseClass = mutableClassDefBy(base)
    val model = baseClass.fields.singleOrNull { field ->
        !AccessFlags.STATIC.isSet(field.accessFlags) &&
            classDefByOrNull(field.type)?.fields?.any { it.type == REEL_ITEM } == true
    } ?: return false
    val bind = baseClass.methods.singleOrNull {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(model.type)
    } ?: return false

    bind.addInstructions(
        0,
        "invoke-static/range {p0 .. p1}, $ACTION_CONFIRM->onStoryItemBound(Ljava/lang/Object;Ljava/lang/Object;)V",
    )
    init.addInstructions(
        store + 1,
        "invoke-static {v$buttonRegister, v$presenter}, $ACTION_CONFIRM->rememberStoryLikePresenter(Landroid/view/View;Ljava/lang/Object;)V",
    )
    return true
}

@Suppress("unused")
val confirmUfiButtonPatch =
    patch(
        name = "Confirm feed reposts and story likes",
        description = "Adds an option to ask before reposting a post from the feed or liking a story with its heart.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val added = addConfirmedPerformClick(
                UFI_BUTTON,
                """
                invoke-static {p0}, $ACTION_CONFIRM->allowUfiClick(Landroid/view/View;)Z
                move-result v0
                """,
            )
            if (!added) return@execute
            enableSettings("confirmFeedRepost")
            // Without it a prompt cannot tell the story it asked about from the next one, so the heart is not asked.
            val storyLike = StoryLikeComponentNameFingerprint.matchOrNull()?.method?.definingClass
                ?.let(::hookStoryLikeBinds) == true
            if (storyLike) enableSettings("confirmStoryLike")
        }
    }
