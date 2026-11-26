package app.morphe.extension.instagram.patches.download.gallery.picker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.instagram.settings.ActivityHook;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
public final class GalleryPickerTest {
    @Test
    public void pickIntentOpensThePrivateGalleryInPickMode() {
        Intent intent = GalleryPicker.pickIntent("com.instagram.android", true);

        assertTrue(ActivityHook.isSpookdIntent(intent));
        assertEquals("com.instagram.android", intent.getComponent().getPackageName());
        assertEquals("com.instagram.modal.ModalActivity", intent.getComponent().getClassName());
        assertEquals("private_gallery", intent.getStringExtra("piko_launch_type"));
        assertTrue(intent.getBooleanExtra(GalleryPicker.EXTRA_PICK, false));
    }
}
