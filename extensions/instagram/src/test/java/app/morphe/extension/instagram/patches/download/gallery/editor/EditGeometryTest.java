package app.morphe.extension.instagram.patches.download.gallery.editor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EditGeometryTest {
    private static final double[] FULL = {-200, -150, 200, 150};

    private static double width(double[] r) {
        return r[2] - r[0];
    }

    private static double height(double[] r) {
        return r[3] - r[1];
    }

    @Test
    public void straighteningScalesTheCropJustInsideTheTurnedImage() {
        double[] fitted = EditGeometry.fit(FULL, 400, 300, 10);
        assertTrue(EditGeometry.inside(fitted, 400, 300, 10));
        double[] larger = {fitted[0] * 1.01, fitted[1] * 1.01, fitted[2] * 1.01, fitted[3] * 1.01};
        assertFalse(EditGeometry.inside(larger, 400, 300, 10));
        assertEquals(4 / 3.0, width(fitted) / height(fitted), 1e-9);
        assertFalse(EditGeometry.inside(FULL, 400, 300, 10));
    }

    @Test
    public void aCropThatFitsTheStraightenedImageStaysAsIs() {
        double[] small = {-40, -30, 60, 20};
        assertArrayEquals(small, EditGeometry.fit(small, 400, 300, -30), 1e-9);
        assertArrayEquals(FULL, EditGeometry.fit(FULL, 400, 300, 0), 1e-9);
    }

    @Test
    public void anOffCenterCropIsPulledInsideWhenStraightened() {
        double[] corner = {100, 50, 200, 150};
        double[] fitted = EditGeometry.fit(corner, 400, 300, 45);
        assertTrue(EditGeometry.inside(fitted, 400, 300, 45));
        assertEquals(1, width(fitted) / height(fitted), 1e-9);
    }

    @Test
    public void aFixedRatioGetsTheLargestCenteredCrop() {
        assertArrayEquals(new double[]{-150, -150, 150, 150}, EditGeometry.largest(1, 400, 300, 0), 1e-9);
        assertArrayEquals(new double[]{-120, -150, 120, 150}, EditGeometry.largest(0.8, 400, 300, 0), 1e-9);
        double half = 150 / Math.sqrt(2);
        assertArrayEquals(new double[]{-half, -half, half, half}, EditGeometry.largest(1, 300, 300, 45), 1e-9);
        double[] wide = EditGeometry.largest(16 / 9.0, 400, 300, 20);
        assertTrue(EditGeometry.inside(wide, 400, 300, 20));
        assertEquals(16 / 9.0, width(wide) / height(wide), 1e-9);
    }

    @Test
    public void aCornerKeepsTheRatioAndItsOppositeCorner() {
        double[] start = EditGeometry.largest(0.8, 400, 300, 0);
        double[] dragged = EditGeometry.drag(start, EditGeometry.RIGHT | EditGeometry.BOTTOM, -50, -10, 0.8, 30,
                400, 300, 0);
        assertEquals(0.8, width(dragged) / height(dragged), 1e-9);
        assertEquals(start[0], dragged[0], 1e-9);
        assertEquals(start[1], dragged[1], 1e-9);
        assertTrue(width(dragged) < width(start));
    }

    @Test
    public void anEdgeWithARatioResizesAboutTheOtherAxisMiddle() {
        double[] start = EditGeometry.largest(0.8, 400, 300, 0);
        double[] dragged = EditGeometry.drag(start, EditGeometry.LEFT, 40, 0, 0.8, 30, 400, 300, 0);
        assertArrayEquals(new double[]{-80, -125, 120, 125}, dragged, 1e-9);
    }

    @Test
    public void aRatioCropStopsAtTheImage() {
        double[] start = EditGeometry.largest(1, 400, 300, 0);
        double[] dragged = EditGeometry.drag(start, EditGeometry.RIGHT, 500, 0, 1, 30, 400, 300, 0);
        assertTrue(EditGeometry.inside(dragged, 400, 300, 0));
        assertEquals(1, width(dragged) / height(dragged), 1e-9);
        assertEquals(300, width(dragged), 0.01);
    }

    @Test
    public void aFreeEdgeStopsAtTheImageAndAtTheSmallestSize() {
        double[] out = EditGeometry.drag(FULL, EditGeometry.RIGHT, 100, 0, 0, 30, 400, 300, 0);
        assertEquals(200, out[2], 0.01);
        double[] in = EditGeometry.drag(FULL, EditGeometry.LEFT, 1000, 0, 0, 30, 400, 300, 0);
        assertEquals(30, width(in), 0.01);
        assertEquals(200, in[2], 1e-9);
    }

    @Test
    public void aFreeCornerStaysInsideTheStraightenedImage() {
        double[] start = EditGeometry.fit(FULL, 400, 300, 20);
        double[] small = EditGeometry.drag(start, EditGeometry.RIGHT | EditGeometry.BOTTOM, -100, -80, 0, 30,
                400, 300, 20);
        double[] dragged = EditGeometry.drag(small, EditGeometry.RIGHT | EditGeometry.BOTTOM, 500, 500, 0, 30,
                400, 300, 20);
        assertTrue(EditGeometry.inside(dragged, 400, 300, 20));
        assertTrue(dragged[2] > small[2]);
        assertTrue(dragged[3] > small[3]);
        assertEquals(small[0], dragged[0], 1e-9);
        assertEquals(small[1], dragged[1], 1e-9);
    }

    @Test
    public void aPanoramaStraightenedThinStaysEditable() {
        double[] thin = EditGeometry.fit(new double[]{-5000, -50, 5000, 50}, 10000, 100, 45);
        assertTrue(height(thin) < 10);
        double[] narrower = EditGeometry.drag(thin, EditGeometry.LEFT, 60, 0, 0, 10, 10000, 100, 45);
        assertEquals(width(thin) - 60, width(narrower), 1e-6);
        double[] taller = EditGeometry.drag(narrower, EditGeometry.BOTTOM, 0, 30, 0, 10, 10000, 100, 45);
        assertTrue(height(taller) > height(narrower));
        assertTrue(EditGeometry.inside(taller, 10000, 100, 45));
    }

    @Test
    public void accessibilityResizeStepsKeepTheMiddleAndTheLimits() {
        double[] r = {-50, -40, 50, 40};
        assertArrayEquals(new double[]{-60, -40, 60, 40}, EditGeometry.grown(r, 20, 0, 0, 30, 400, 300, 0), 1e-9);
        assertArrayEquals(new double[]{-50, -30, 50, 30}, EditGeometry.grown(r, 0, -20, 0, 30, 400, 300, 0), 1e-9);
        double[] square = EditGeometry.grown(new double[]{-50, -50, 50, 50}, 20, 0, 1, 30, 400, 300, 0);
        assertEquals(1, width(square) / height(square), 1e-9);
        assertEquals(0, square[0] + square[2], 1e-9);
        assertEquals(30, width(EditGeometry.grown(r, -500, 0, 0, 30, 400, 300, 0)), 0.01);
        assertEquals(400, width(EditGeometry.grown(r, 1000, 0, 0, 30, 400, 300, 0)), 0.01);
    }

    @Test
    public void movingSlidesAlongTheEdgeItHits() {
        double[] moved = EditGeometry.drag(new double[]{-50, -50, 50, 50}, EditGeometry.MOVE, 1000, 10, 1, 30,
                400, 300, 0);
        assertArrayEquals(new double[]{100, -40, 200, 60}, moved, 0.01);
    }

    @Test
    public void turnsAndMirrorsKeepTheCropOnTheSamePixels() {
        double[] r = {-10, -20, 30, 40};
        assertArrayEquals(new double[]{-40, -10, 20, 30}, EditGeometry.turned(r), 1e-9);
        double[] back = r;
        for (int i = 0; i < 4; i++) back = EditGeometry.turned(back);
        assertArrayEquals(r, back, 1e-9);
        assertArrayEquals(new double[]{-30, -20, 10, 40}, EditGeometry.mirrored(r), 1e-9);
        double[] fitted = EditGeometry.fit(new double[]{0, 0, 200, 150}, 400, 300, 10);
        assertTrue(EditGeometry.inside(EditGeometry.turned(fitted), 300, 400, 10));
        assertTrue(EditGeometry.inside(EditGeometry.mirrored(fitted), 400, 300, -10));
    }

    @Test
    public void exifOrientationsBecomeTurnsAndAMirror() {
        assertArrayEquals(new int[]{0, 0}, EditGeometry.orientation(1));
        assertArrayEquals(new int[]{0, 0}, EditGeometry.orientation(0));
        assertArrayEquals(new int[]{0, 1}, EditGeometry.orientation(2));
        assertArrayEquals(new int[]{2, 0}, EditGeometry.orientation(3));
        assertArrayEquals(new int[]{2, 1}, EditGeometry.orientation(4));
        assertArrayEquals(new int[]{1, 1}, EditGeometry.orientation(5));
        assertArrayEquals(new int[]{1, 0}, EditGeometry.orientation(6));
        assertArrayEquals(new int[]{3, 1}, EditGeometry.orientation(7));
        assertArrayEquals(new int[]{3, 0}, EditGeometry.orientation(8));
    }

    @Test
    public void thePreviewIsSampledDownOnlyWhileItStillCoversTheScreen() {
        assertEquals(2, EditGeometry.previewSample(4000, 3000, 1080, 2400));
        assertEquals(1, EditGeometry.previewSample(1080, 1350, 1080, 2400));
        assertEquals(4, EditGeometry.previewSample(8000, 8000, 1080, 2400));
    }

    @Test
    public void theFullSizeDecodeIsHalvedUntilItFitsTheBudget() {
        assertEquals(1, EditGeometry.budgetSample(8000, 6000, 48_000_000));
        assertEquals(2, EditGeometry.budgetSample(8000, 6000, 12_000_000));
        assertEquals(4, EditGeometry.budgetSample(8000, 6000, 11_999_999));
    }

    @Test
    public void aSampledSaveStaysInsideTheDecodedPhotoOnBothAxes() {
        // 1001 by 20000 decoded at half size is 501 by 10000, so the two sides scale differently.
        double scale = Math.min(501 / 1001.0, 10000 / 20000.0);
        double[] turnedFull = EditGeometry.turned(new double[]{-500.5, -10000, 500.5, 10000});
        assertArrayEquals(new int[]{0, 0, 10000, 500}, EditGeometry.outputRect(turnedFull, 20000, 1001, scale));
        assertArrayEquals(new int[]{10, 20, 133, 30},
                EditGeometry.outputRect(new double[]{-189.6, -130, -66.1, -120}, 400, 300, 1));
    }

    @Test
    public void anEditIsNamedAfterItsOriginalOnce() {
        assertEquals("post_edited.jpg", EditGeometry.editedName("post.webp", false));
        assertEquals("post_edited.png", EditGeometry.editedName("post_edited.png", true));
        assertEquals("photo_edited.jpg", EditGeometry.editedName(null, false));
        assertEquals("a.b_edited.jpg", EditGeometry.editedName("a.b.jpeg", false));
    }
}
