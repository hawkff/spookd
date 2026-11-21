package app.morphe.extension.instagram.patches.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GlassTabBarModeTest {
    private static int choose(boolean enabled, int sdk, boolean hardware, boolean systemBlur,
                              boolean failed, boolean away) {
        return GlassTabBarMode.choose(enabled, sdk, hardware, systemBlur, failed, away);
    }

    @Test
    public void blursOnAndroid12WithHardwareCanvasAndSystemBlur() {
        assertEquals(GlassTabBarMode.BLUR, choose(true, 31, true, true, false, false));
        assertEquals(GlassTabBarMode.BLUR, choose(true, 35, true, true, false, false));
    }

    @Test
    public void fallsBackToOpaqueTintWhenBlurIsUnavailable() {
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 30, true, true, false, false));
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 28, true, true, false, false));
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 31, false, true, false, false));
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 31, true, false, false, false));
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 31, true, true, true, false));
    }

    @Test
    public void skipsBlurWhileTheBarIsFullyAway() {
        assertEquals(GlassTabBarMode.OPAQUE, choose(true, 34, true, true, false, true));
    }

    @Test
    public void drawsInstagramsPillWhenDisabled() {
        assertEquals(GlassTabBarMode.OFF, choose(false, 34, true, true, false, false));
        assertEquals(GlassTabBarMode.OFF, choose(false, 28, false, false, true, true));
    }
}
