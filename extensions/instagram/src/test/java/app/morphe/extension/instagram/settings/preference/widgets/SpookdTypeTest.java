package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class SpookdTypeTest {
    private static float sp(float value) {
        DisplayMetrics metrics = RuntimeEnvironment.getApplication().getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics);
    }

    @Test
    public void platformWeightsMapToTheScaleWeights() {
        assertEquals(400, SpookdType.interfaceWeight(300));
        assertEquals(400, SpookdType.interfaceWeight(400));
        assertEquals(600, SpookdType.interfaceWeight(500));
        assertEquals(600, SpookdType.interfaceWeight(600));
        assertEquals(700, SpookdType.interfaceWeight(700));
        assertEquals(700, SpookdType.interfaceWeight(900));
    }

    @Test
    public void aMissingInterfaceFontFallsBackToThreeWeights() {
        Typeface[] faces = SpookdType.load(RuntimeEnvironment.getApplication());
        assertEquals(3, faces.length);
        for (Typeface face : faces) assertNotNull(face);
    }

    @Test
    public void rolesSetTheirSizeInSpAndLineHeight() {
        TextView summary = new TextView(RuntimeEnvironment.getApplication());
        SpookdType.apply(summary, SpookdType.SECONDARY);
        assertEquals(sp(14), summary.getTextSize(), 0.01f);
        assertEquals(1.1f, summary.getLineSpacingMultiplier(), 0.001f);
        assertNotNull(summary.getTypeface());

        TextView title = new TextView(RuntimeEnvironment.getApplication());
        SpookdType.apply(title, SpookdType.TITLE);
        assertEquals(sp(18), title.getTextSize(), 0.01f);
        assertEquals(1f, title.getLineSpacingMultiplier(), 0.001f);
    }

    @Test
    @Config(sdk = 27)
    public void beforeApi28SemiboldStaysApartFromBold() {
        Typeface[] faces = SpookdType.load(RuntimeEnvironment.getApplication());
        assertEquals(3, faces.length);
        for (Typeface face : faces) assertNotNull(face);
        assertNotSame(faces[1], faces[2]);
    }

    private static final int PRIMARY = 0xff010203;
    private static final int SECONDARY = 0xff040506;
    private static final int ACCENT = 0xff0095f6;

    private static AlertDialog.Builder builder(int theme) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        return new AlertDialog.Builder(
                SpookdType.dialogContext(activity, theme, PRIMARY, SECONDARY, ACCENT));
    }

    @Test
    public void platformDialogsUseTheTypeScaleAndPalette() {
        for (int theme : new int[]{
                android.R.style.Theme_DeviceDefault, android.R.style.Theme_DeviceDefault_Light}) {
            AlertDialog dialog = builder(theme)
                    .setTitle("Title")
                    .setMessage("Message")
                    .setPositiveButton("Done", null)
                    .create();
            dialog.show();

            TextView message = dialog.findViewById(android.R.id.message);
            assertEquals(sp(16), message.getTextSize(), 0.01f);
            assertEquals(SECONDARY, message.getCurrentTextColor());
            Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals(sp(14), done.getTextSize(), 0.01f);
            assertFalse(done.isAllCaps());
            assertEquals(ACCENT, done.getCurrentTextColor());
            done.setEnabled(false);
            assertNotEquals(ACCENT, done.getCurrentTextColor());
            dialog.dismiss();
        }
    }

    @Test
    public void darkDialogsGetTheLiftedSurfaceAndLightOnesKeepTheirCard() {
        int surface = 0xff212328;
        int panelId = android.content.res.Resources.getSystem()
                .getIdentifier("parentPanel", "id", "android");
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog dark = new AlertDialog.Builder(SpookdType.dialogContext(activity,
                android.R.style.Theme_DeviceDefault, PRIMARY, SECONDARY, ACCENT, surface))
                .setMessage("Message")
                .create();
        dark.show();
        android.view.View panel = dark.findViewById(panelId);
        assertTrue(panel.getBackground() instanceof android.graphics.drawable.GradientDrawable);
        assertEquals(surface, ((android.graphics.drawable.GradientDrawable) panel.getBackground())
                .getColor().getDefaultColor());

        AlertDialog light = builder(android.R.style.Theme_DeviceDefault_Light)
                .setMessage("Message")
                .create();
        light.show();
        assertFalse(light.findViewById(panelId).getBackground()
                instanceof android.graphics.drawable.GradientDrawable);
    }

    @Test
    public void choiceRowsUseTheBodyRoleAndAccent() {
        AlertDialog dialog = builder(android.R.style.Theme_DeviceDefault)
                .setSingleChoiceItems(new CharSequence[]{"One", "Two"}, 0, null)
                .create();
        dialog.show();
        ListView list = dialog.getListView();
        TextView row = (TextView) list.getAdapter().getView(0, null, list);
        assertEquals(sp(16), row.getTextSize(), 0.01f);
        assertEquals(PRIMARY, row.getCurrentTextColor());
        assertTrue(row instanceof CheckedTextView);
        assertEquals(ACCENT, ((CheckedTextView) row).getCheckMarkTintList().getDefaultColor());
    }

    @Test
    public void preferenceEditorsUseTheBodyRoleAndPrimaryColor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        EditText editor = new EditText(SpookdType.dialogContext(
                activity, android.R.style.Theme_DeviceDefault, PRIMARY, SECONDARY, ACCENT));
        SpookdType.applyDialogInput(editor);
        assertEquals(sp(16), editor.getTextSize(), 0.01f);
        assertEquals(PRIMARY, editor.getCurrentTextColor());
    }
}
