package app.morphe.extension.instagram.patches.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class HiddenAccountPrivacyOptionsTest {
    @Test
    public void onlyTheFollowListEligibilityIsAnsweredWhileOn() {
        assertEquals(Boolean.TRUE, HiddenAccountPrivacyOptions.serverValue("is_uk_osa_enrolled_late_teen", true));
        assertNull(HiddenAccountPrivacyOptions.serverValue("is_uk_osa_enrolled_late_teen", false));
    }

    @Test
    public void otherServerValuesKeepTheServersAnswer() {
        assertNull(HiddenAccountPrivacyOptions.serverValue("is_uk_osa_enrolled_early_teen", true));
        assertNull(HiddenAccountPrivacyOptions.serverValue("is_uk_osa_enrolled_supervised_teen", true));
        assertNull(HiddenAccountPrivacyOptions.serverValue("is_feta_account", true));
        assertNull(HiddenAccountPrivacyOptions.serverValue(null, true));
    }
}
