/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.spookd.patches.instagram.misc.location

import app.spookd.patches.instagram.misc.settings.settingsPatch
import app.spookd.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.spookd.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.spookd.patches.instagram.utils.Constants.USER_SESSION_CLASS
import app.spookd.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags

private const val FAKE_LOCATION_DESCRIPTOR = "$PATCHES_DESCRIPTOR/location/FakeLocation;"
private const val LOCATION_PLUGIN_CLASS = "Lcom/instagram/location/impl/LocationPluginImpl;"
private const val LOCATION_CLASS = "Landroid/location/Location;"
private const val LOCATION_LISTENER_CLASS = "Landroid/location/LocationListener;"
private const val LOCATION_MANAGER_CLASS = "Landroid/location/LocationManager;"

// The immutable wrapper Instagram's location stack puts around every fix, from the system or its cache.
internal object ImmutableLocationConstructorFingerprint : Fingerprint(
    name = "<init>",
    returnType = "V",
    parameters = listOf(LOCATION_CLASS),
    strings = listOf("location must have latitude/longitude"),
)

// Instagram's own detours around LocationManager.
internal object LocationUpdatesDetourFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(LOCATION_LISTENER_CLASS, LOCATION_MANAGER_CLASS, "Ljava/lang/String;", "F", "J"),
    strings = listOf("LOCATION_MANAGER_DETOUR_requestLocationUpdates()"),
)

internal object RemoveLocationUpdatesDetourFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(LOCATION_LISTENER_CLASS, LOCATION_MANAGER_CLASS),
    strings = listOf("LOCATION_MANAGER_DETOUR_removeUpdates()"),
)

internal object LastKnownLocationDetourFingerprint : Fingerprint(
    classFingerprint = LocationUpdatesDetourFingerprint,
    returnType = LOCATION_CLASS,
    parameters = listOf(LOCATION_MANAGER_CLASS, "Ljava/lang/String;"),
    filters = listOf(
        methodCall(smali = "$LOCATION_MANAGER_CLASS->getLastKnownLocation(Ljava/lang/String;)$LOCATION_CLASS"),
    ),
)

// Every other getLastLocation overload calls this one.
internal object PluginLastLocationFingerprint : Fingerprint(
    definingClass = LOCATION_PLUGIN_CLASS,
    name = "getLastLocation",
    returnType = LOCATION_CLASS,
    parameters = listOf(USER_SESSION_CLASS, "J", "F", "Z", "Ljava/lang/String;", "L"),
)

// Where every update request of the plugin starts once permission is granted; 7000 and 5000 ms are its request timings.
internal object PluginRequestUpdatesFingerprint : Fingerprint(
    definingClass = LOCATION_PLUGIN_CLASS,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(LOCATION_PLUGIN_CLASS, USER_SESSION_CLASS, "L", "Ljava/lang/String;", "Z", "L"),
    filters = listOf(literal(7000L), literal(5000L)),
)

internal object PluginPlaceSignatureCollectionFingerprint : Fingerprint(
    definingClass = LOCATION_PLUGIN_CLASS,
    name = "setupPlaceSignatureCollection",
    returnType = "V",
)

internal object PluginForegroundCollectionFingerprint : Fingerprint(
    definingClass = LOCATION_PLUGIN_CLASS,
    name = "setupForegroundCollection",
    returnType = "V",
)

@Suppress("unused")
val fakeLocationPatch =
    patch(
        name = "Fake location",
        description = "Adds an option to show Instagram a saved place you choose instead of your real location.",
    ) {
        dependsOn(settingsPatch)
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            ImmutableLocationConstructorFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p1}, $FAKE_LOCATION_DESCRIPTOR->spoof($LOCATION_CLASS)$LOCATION_CLASS
                move-result-object p1
                """.trimIndent(),
            )

            LocationUpdatesDetourFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p0}, $FAKE_LOCATION_DESCRIPTOR->updatesListener($LOCATION_LISTENER_CLASS)$LOCATION_LISTENER_CLASS
                move-result-object p0
                """.trimIndent(),
            )

            RemoveLocationUpdatesDetourFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p0}, $FAKE_LOCATION_DESCRIPTOR->removalListener($LOCATION_LISTENER_CLASS)$LOCATION_LISTENER_CLASS
                move-result-object p0
                """.trimIndent(),
            )

            LastKnownLocationDetourFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p1}, $FAKE_LOCATION_DESCRIPTOR->lastKnown(Ljava/lang/String;)$LOCATION_CLASS
                    move-result-object v0
                    if-eqz v0, :fake_location_device
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            PluginLastLocationFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->current()$LOCATION_CLASS
                    move-result-object v0
                    if-eqz v0, :fake_location_device
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            // p2 is Instagram's location listener; the chosen place is delivered at once, as the plugin does with a cached fix.
            PluginRequestUpdatesFingerprint.method.apply {
                val listenerType = parameterTypes[2].toString()
                if (classDefBy(listenerType).methods.none {
                        it.name == "onLocationChanged" &&
                            it.parameterTypes.map(CharSequence::toString) == listOf(LOCATION_CLASS)
                    }
                ) {
                    throw PatchException("Expected the plugin's update listener to take onLocationChanged(Location)")
                }
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->current()$LOCATION_CLASS
                    move-result-object v0
                    if-eqz v0, :fake_location_device
                    move-object/from16 v1, p2
                    invoke-interface {v1, v0}, $listenerType->onLocationChanged($LOCATION_CLASS)V
                    return-void
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            // Both collect device signals such as Wi-Fi scans, which would give the real location away.
            listOf(PluginPlaceSignatureCollectionFingerprint, PluginForegroundCollectionFingerprint).forEach {
                it.method.apply {
                    addInstructionsWithLabels(
                        0,
                        """
                        invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->blocksCollection()Z
                        move-result v0
                        if-eqz v0, :fake_location_collect
                        return-void
                        """.trimIndent(),
                        ExternalLabel("fake_location_collect", getInstruction(0)),
                    )
                }
            }

            enableSettings("fakeLocation")
        }
    }
