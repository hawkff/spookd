/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.entity;

import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility;
import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility.Kind;
import app.morphe.extension.instagram.patches.dm.vault.MediaEligibility.WrapperField;
import app.morphe.extension.shared.Logger;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the visual and voice media wrappers of a DM item for {@code MediaEligibility}.
 *
 * <p>The placeholder names below are rewritten at patch time by the "Save DM media" patch.
 */
public class DirectItemWrappers extends Entity {
    public DirectItemWrappers(Object item) {
        super(item);
    }

    /** Binary name of the item class that declares the wrapper fields. */
    private String itemClassName() { return "className"; }

    // Comma-separated item fields of each wrapper type, and the view_mode field of that wrapper.
    private String visualFields()    { return "fieldNames"; }
    private String voiceFields()     { return "fieldNames"; }
    private String visualModeField() { return "fieldName"; }
    private String voiceModeField()  { return "fieldName"; }

    /** Every wrapper field of the item, or null when the item class cannot be resolved. */
    public List<WrapperField> read() {
        Class<?> itemClass;
        try {
            itemClass = Class.forName(this.itemClassName());
        } catch (Exception e) {
            Logger.printDebug(() -> "DirectItemWrappers: item class not resolved", e);
            return null;
        }
        List<WrapperField> fields = new ArrayList<>();
        this.readKind(itemClass, Kind.VISUAL, this.visualFields(), this.visualModeField(), fields);
        this.readKind(itemClass, Kind.VOICE, this.voiceFields(), this.voiceModeField(), fields);
        return fields;
    }

    private void readKind(Class<?> itemClass, Kind kind, String fieldNames, String modeField, List<WrapperField> out) {
        for (String name : fieldNames.split(",")) {
            try {
                Object wrapper = super.getField(itemClass, this.obj, name);
                String original = wrapper == null ? null : MediaEligibility.originalMode(wrapper);
                out.add(wrapper == null
                        ? WrapperField.absent(kind)
                        : WrapperField.present(kind, original != null ? original
                                : (String) super.getField(wrapper.getClass(), wrapper, modeField)));
            } catch (Exception e) {
                Logger.printDebug(() -> "DirectItemWrappers: cannot read " + kind + " field " + name, e);
                out.add(WrapperField.unreadable(kind));
            }
        }
    }
}
