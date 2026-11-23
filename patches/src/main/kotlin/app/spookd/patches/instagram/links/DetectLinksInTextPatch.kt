/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links

import app.crimera.utils.changeFirstString
import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch

private const val TEXT_LINKS_CLASS = "$PATCHES_DESCRIPTOR/TextLinks;"

// Feed captions are drawn from a prebuilt text layout set on this view.
private object IgTextLayoutViewSetTextLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/ui/widget/textview/IgTextLayoutView;",
    name = "setTextLayout",
    returnType = "V",
    parameters = listOf("Landroid/text/Layout;"),
)

private object CaptionTapSpanClassesExtensionFingerprint : Fingerprint(
    definingClass = TEXT_LINKS_CLASS,
    name = "captionTapSpanClasses",
)

// The caption builder's span over the caption text logs its taps as the text area; two classes implement it.
private fun captionTextAreaSpanFingerprint(excludedClass: String?) = Fingerprint(
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf("tap_area", "text_area"),
    custom = { _, classDef ->
        classDef.type != excludedClass && classDef.methods.any { method ->
            method.name == "<init>" &&
                method.parameterTypes.any { it.toString() == "Lcom/instagram/search/common/analytics/SearchContext;" }
        }
    },
)

@Suppress("unused")
val detectLinksInTextPatch =
    patch(
        name = "Detect links in captions",
        description = "Adds an option to make plain web addresses in feed captions tappable.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            val first = captionTextAreaSpanFingerprint(null).classDef.type
            val second = captionTextAreaSpanFingerprint(first).classDef.type
            CaptionTapSpanClassesExtensionFingerprint.changeFirstString(
                listOf(first, second).joinToString(",") { it.substring(1, it.length - 1).replace('/', '.') },
            )

            IgTextLayoutViewSetTextLayoutFingerprint.method.addInstruction(
                0,
                "invoke-static { p1 }, $TEXT_LINKS_CLASS->onTextLayout(Landroid/text/Layout;)V",
            )

            enableSettings("detectLinksInText")
        }
    }
