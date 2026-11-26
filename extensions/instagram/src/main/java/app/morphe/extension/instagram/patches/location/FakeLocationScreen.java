/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.location;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;

import java.util.List;

import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.instagram.utils.Pref;

/** The saved places dialogs in the Ghost settings, on the main thread: choose, add, rename and delete places. */
public final class FakeLocationScreen {
    private FakeLocationScreen() {
    }

    /** The places row's summary: the chosen place, or that a Mojave Desert point is used until one is chosen. */
    public static String summary() {
        FakePlaces.Place active = FakePlaces.active();
        return active == null
                ? str("spookd_fake_location_no_place")
                : str("spookd_fake_location_chosen", active.label, FakePlaces.format(active.latitude, active.longitude));
    }

    /** The list of saved places; {@code changed} runs after every edit. */
    public static void showPlaces(Context context, Runnable changed) {
        List<FakePlaces.Place> places = FakePlaces.all();
        FakePlaces.Place active = FakePlaces.active();
        CharSequence[] rows = new CharSequence[places.size()];
        for (int i = 0; i < rows.length; i++) {
            FakePlaces.Place place = places.get(i);
            rows[i] = (place == active ? "✓ " : "") + place.label + "\n"
                    + FakePlaces.format(place.latitude, place.longitude);
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(str("spookd_fake_location_places"))
                .setNegativeButton(str("spookd_close"), null)
                .setNeutralButton(str("spookd_fake_location_add"), (d, which) -> showEditor(context, null, changed));
        if (places.isEmpty()) {
            dialog.setMessage(str("spookd_fake_location_places_empty"));
        } else {
            dialog.setItems(rows, (d, which) -> showActions(context, places.get(which), changed));
        }
        dialog.show();
    }

    private static void showActions(Context context, FakePlaces.Place place, Runnable changed) {
        CharSequence[] actions = {
                str("spookd_fake_location_use"), str("spookd_fake_location_rename"), str("spookd_delete")
        };
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setTitle(place.label)
                .setItems(actions, (d, which) -> {
                    if (which == 0) {
                        FakePlaces.choose(place.id);
                        changed.run();
                        showPlaces(context, changed);
                    } else if (which == 1) {
                        showEditor(context, place, changed);
                    } else {
                        confirmDelete(context, place, changed);
                    }
                })
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showPlaces(context, changed))
                .show();
    }

    private static void confirmDelete(Context context, FakePlaces.Place place, Runnable changed) {
        new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                .setMessage(str("spookd_fake_location_delete_confirm", place.label))
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showPlaces(context, changed))
                .setPositiveButton(str("spookd_delete"), (d, which) -> {
                    FakePlaces.remove(place.id, Pref.fakeLocation());
                    changed.run();
                    showPlaces(context, changed);
                })
                .show();
    }

    /** Adds a place from coordinates or a maps link, or renames {@code place} when it is not null. */
    private static void showEditor(Context context, FakePlaces.Place place, Runnable changed) {
        Context dialogContext = InstagramPreferenceStyle.dialogContext(context);
        int padding = InstagramPreferenceStyle.dp(context, 20);
        LinearLayout form = new LinearLayout(dialogContext);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(padding, padding / 2, padding, 0);

        double[] fallback = place == null ? FakePlaces.defaultPoint() : null;
        EditText coordinates = new EditText(dialogContext);
        if (fallback != null) {
            coordinates.setHint(str("spookd_fake_location_coordinates_hint", FakePlaces.format(fallback[0], fallback[1])));
            form.addView(coordinates);
        }
        EditText label = new EditText(dialogContext);
        label.setSingleLine(true);
        label.setHint(str("spookd_fake_location_label_hint"));
        if (place != null) label.setText(place.label);
        form.addView(label);

        AlertDialog dialog = new AlertDialog.Builder(dialogContext)
                .setTitle(str(place == null ? "spookd_fake_location_add" : "spookd_fake_location_rename"))
                .setView(form)
                .setNegativeButton(str("spookd_cancel"), (d, which) -> showPlaces(context, changed))
                .setPositiveButton(str("spookd_fake_location_save"), null)
                .create();
        // Set after show so an invalid entry keeps the dialog open.
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (place == null) {
                String typed = coordinates.getText().toString().trim();
                double[] point = typed.isEmpty() ? fallback : FakePlaces.parseCoordinates(typed);
                if (point == null) {
                    coordinates.setError(str("spookd_fake_location_invalid"));
                    return;
                }
                String name = label.getText().toString().trim();
                FakePlaces.add(name.isEmpty() && point == fallback ? FakePlaces.DEFAULT_LABEL : name, point[0], point[1]);
            } else {
                FakePlaces.rename(place.id, label.getText().toString());
            }
            dialog.dismiss();
            changed.run();
            showPlaces(context, changed);
        }));
        dialog.show();
    }
}
