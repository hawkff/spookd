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
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

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

// Its factory for device fixes, which returns null for a fix without coordinates.
internal object ImmutableLocationFactoryFingerprint : Fingerprint(
    classFingerprint = ImmutableLocationConstructorFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "L",
    parameters = listOf(LOCATION_CLASS),
)

// The location cache's read, which can hand out the fix it holds without asking the device.
internal object LocationCacheReadFingerprint : Fingerprint(
    returnType = "L",
    parameters = listOf("Ljava/lang/String;", "L", "F", "J", "Z"),
    strings = listOf(
        "app in background. returning saved location",
        "minimize location access and should not allow access to OS cache",
    ),
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
            val replacesDevice = """
                invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->replacesDevice()Z
                move-result v0
                if-eqz v0, :fake_location_device
            """.trimIndent()

            ImmutableLocationConstructorFingerprint.method.addInstructions(
                0,
                """
                invoke-static {p1}, $FAKE_LOCATION_DESCRIPTOR->spoof($LOCATION_CLASS)$LOCATION_CLASS
                move-result-object p1
                """.trimIndent(),
            )

            ImmutableLocationFactoryFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->blocksFix()Z
                    move-result v0
                    if-eqz v0, :fake_location_device
                    const/4 v0, 0x0
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            // Right after the cached fix is read, so every way out of the method sees the replaced one.
            LocationCacheReadFingerprint.method.apply {
                val wrapperType = returnType
                val readIndex = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IGET_OBJECT && getReference<FieldReference>()?.let {
                        it.type == wrapperType && it.definingClass == definingClass
                    } == true
                }
                val register = getInstruction<TwoRegisterInstruction>(readIndex).registerA
                addInstructions(
                    readIndex + 1,
                    """
                    invoke-static/range {v$register .. v$register}, $FAKE_LOCATION_DESCRIPTOR->cachedFix(Ljava/lang/Object;)Ljava/lang/Object;
                    move-result-object v$register
                    check-cast v$register, $wrapperType
                    """.trimIndent(),
                )
            }

            // p0 is the listener and p2 the provider; null means the request was answered with the chosen place.
            LocationUpdatesDetourFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    invoke-static {p0, p2}, $FAKE_LOCATION_DESCRIPTOR->requestUpdates(${LOCATION_LISTENER_CLASS}Ljava/lang/String;)$LOCATION_LISTENER_CLASS
                    move-result-object p0
                    if-nez p0, :fake_location_device
                    return-void
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

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
                    $replacesDevice
                    invoke-static {p1}, $FAKE_LOCATION_DESCRIPTOR->lastKnown(Ljava/lang/String;)$LOCATION_CLASS
                    move-result-object v0
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            PluginLastLocationFingerprint.method.apply {
                addInstructionsWithLabels(
                    0,
                    """
                    $replacesDevice
                    invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->current()$LOCATION_CLASS
                    move-result-object v0
                    return-object v0
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                )
            }

            // p2 is Instagram's location listener; the chosen place is delivered at once, as the plugin does with a
            // cached fix, and the device is never asked. With no place yet, the method's first return ends it.
            PluginRequestUpdatesFingerprint.method.apply {
                val listenerType = parameterTypes[2].toString()
                if (classDefBy(listenerType).methods.none {
                        it.name == "onLocationChanged" &&
                            it.parameterTypes.map(CharSequence::toString) == listOf(LOCATION_CLASS)
                    }
                ) {
                    throw PatchException("Expected the plugin's update listener to take onLocationChanged(Location)")
                }
                val returnInstruction = getInstruction(indexOfFirstInstructionOrThrow(Opcode.RETURN_VOID))
                addInstructionsWithLabels(
                    0,
                    """
                    $replacesDevice
                    invoke-static {}, $FAKE_LOCATION_DESCRIPTOR->current()$LOCATION_CLASS
                    move-result-object v0
                    if-eqz v0, :fake_location_none
                    move-object/from16 v1, p2
                    invoke-interface {v1, v0}, $listenerType->onLocationChanged($LOCATION_CLASS)V
                    return-void
                    """.trimIndent(),
                    ExternalLabel("fake_location_device", getInstruction(0)),
                    ExternalLabel("fake_location_none", returnInstruction),
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
