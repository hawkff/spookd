/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.links

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch

// Feed captions are drawn from a prebuilt text layout set on this view.
private object IgTextLayoutViewSetTextLayoutFingerprint : Fingerprint(
    definingClass = "Lcom/instagram/ui/widget/textview/IgTextLayoutView;",
    name = "setTextLayout",
    returnType = "V",
    parameters = listOf("Landroid/text/Layout;"),
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
            IgTextLayoutViewSetTextLayoutFingerprint.method.addInstruction(
                0,
                "invoke-static { p1 }, $PATCHES_DESCRIPTOR/TextLinks;->onTextLayout(Landroid/text/Layout;)V",
            )

            enableSettings("detectLinksInText")
        }
    }
