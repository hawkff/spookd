package app.morphe.extension.instagram.settings.preference.widgets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.widget.Button;
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
    public void platformDialogsUseTheTypeScale() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AlertDialog dialog = new AlertDialog.Builder(
                SpookdType.dialogContext(activity, android.R.style.Theme_DeviceDefault))
                .setTitle("Title")
                .setMessage("Message")
                .setPositiveButton("Done", null)
                .create();
        dialog.show();

        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(sp(16), message.getTextSize(), 0.01f);
        Button done = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        assertEquals(sp(14), done.getTextSize(), 0.01f);
        assertFalse(done.isAllCaps());
    }
}
