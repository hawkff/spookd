package app.morphe.extension.instagram.patches.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

public final class DownloadFileNameFormatterTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    // 2024-03-05 07:08:09 UTC
    private static final long POSTED_AT = 1709622489000L;

    private static DownloadFileNameFormatter.Values post(Long postedAt) {
        return new DownloadFileNameFormatter.Values("alice", "123", "ABC", postedAt, "image", 0, "");
    }

    @Test
    public void postDateAndTimeComeFromThePost() {
        assertEquals("alice_2024-03-05_07-08.jpg",
                DownloadFileNameFormatter.format("{username}_{post_date}_{post_time}", post(POSTED_AT), ".jpg", UTC));
    }

    @Test
    public void downloadDateIsToday() {
        LocalDate before = LocalDate.now(UTC);
        String name = DownloadFileNameFormatter.format("{download_date}", post(POSTED_AT), ".jpg", UTC);
        // The day may roll over while formatting.
        assertTrue(name, name.equals(before + ".jpg") || name.equals(LocalDate.now(UTC) + ".jpg"));
    }

    @Test
    public void aMissingPostTimeDropsItsSeparator() {
        assertEquals("alice.jpg", DownloadFileNameFormatter.format("{username}_{post_date}", post(null), ".jpg", UTC));
    }

    @Test
    public void existingTokensAndTheDefaultTemplateStillFormat() {
        assertTrue(DownloadFileNameFormatter.isValidTemplate("{upload_date}_{upload_time}_{post_date}_{download_date}"));
        assertEquals("alice_123.jpg",
                DownloadFileNameFormatter.format(DownloadFileNameFormatter.DEFAULT_TEMPLATE, post(POSTED_AT), ".jpg", UTC));
        assertEquals("2024-03-05_07-08-09.jpg",
                DownloadFileNameFormatter.format("{upload_date}_{upload_time}", post(POSTED_AT), ".jpg", UTC));
    }
}
