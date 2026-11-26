/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.distractionFree

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.findFreeRegister
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

internal const val DISTRACTION_FREE_HIDES = "$PATCHES_DESCRIPTOR/distractionFree/DistractionFreeHides;"

internal const val RECENT_SEARCH_DATA_SOURCE = "Lcom/instagram/search/typeahead/data/RecentSearchDataSource;"

// The recent searches the search screen lists, for an empty query or the one being typed.
internal object RecentSearchResultsFingerprint : Fingerprint(
    definingClass = RECENT_SEARCH_DATA_SOURCE,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Ljava/util/List;",
    parameters = listOf("L", "L", RECENT_SEARCH_DATA_SOURCE),
)

@Suppress("unused")
val hideRecentSearchesPatch =
    patch(
        name = "Hide recent searches",
        description = "Adds an option to hide your recent searches in search.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            RecentSearchResultsFingerprint.method.apply {
                val free = findFreeRegister(0)
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $DISTRACTION_FREE_HIDES->hideRecentSearches()Z
                    move-result v$free
                    if-eqz v$free, :spookd_recent_searches
                    invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                    move-result-object v$free
                    return-object v$free
                    """.trimIndent(),
                    ExternalLabel("spookd_recent_searches", getInstruction(0)),
                )
            }

            enableSettings("hideRecentSearches")
        }
    }
