package app.morphe.extension.instagram.patches.dm.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class VoiceAudioTest {
    @Test
    public void levelsFollowTheRecordersLogarithmicScaleClampedToOne() {
        assertEquals(0f, VoiceAudio.level(0), 0f);
        assertEquals(0f, VoiceAudio.level(1), 0f);
        assertEquals((float) (Math.log(1000) * 0.25 - 1.5807000398635864), VoiceAudio.level(1000), 1e-6f);
        assertEquals(1f, VoiceAudio.level(32768), 0f);
    }

    @Test
    public void theWaveformHasOneLevelPerHundredMillisecondsFromEachWindowsPeak() {
        short[] samples = new short[VoiceAudio.WAVEFORM_SAMPLES * 2 + 1];
        samples[5] = 1000;
        samples[6] = -2000;
        samples[VoiceAudio.WAVEFORM_SAMPLES * 2] = Short.MIN_VALUE;
        List<Float> waveform = VoiceAudio.waveform(samples, samples.length);
        assertEquals(Arrays.asList(VoiceAudio.level(2000), 0f, 1f), waveform);
        // Samples past the size are not part of the audio.
        assertEquals(1, VoiceAudio.waveform(samples, 10).size());
        assertEquals(0, VoiceAudio.waveform(samples, 0).size());
    }

    @Test
    public void onlyFramesInsideTheSelectionAreKept() {
        // 1000 frames per second, so frame i is at i ms.
        assertArrayEquals(new int[]{10, 50}, VoiceAudio.keptFrames(0, 100, 1000, 10_000, 50_000));
        assertArrayEquals(new int[]{0, 100}, VoiceAudio.keptFrames(20_000, 100, 1000, 10_000, 500_000));
        assertArrayEquals(new int[]{0, 0}, VoiceAudio.keptFrames(60_000, 100, 1000, 10_000, 50_000));
        assertArrayEquals(new int[]{100, 100}, VoiceAudio.keptFrames(0, 100, 1000, 200_000, 300_000));
        // A frame starting a microsecond before the start is left out.
        assertArrayEquals(new int[]{1, 2}, VoiceAudio.keptFrames(0, 2, 44100, 1, 1_000_000));
    }

    @Test
    public void theResamplerInterpolatesAcrossChunks() {
        assertArrayEquals(new short[]{10, 20, 30, 40}, resample(44100, 44100, new short[]{10, 20, 30}, new short[]{40, 50}));
        assertArrayEquals(new short[]{0, 2, 4, 6}, resample(88200, 44100, new short[]{0, 1, 2, 3, 4, 5}, new short[]{6, 7}));
        assertArrayEquals(new short[]{0, 5, 10, 15}, resample(22050, 44100, new short[]{0, 10}, new short[]{20}));
    }

    @Test
    public void floatSamplesAreRoundedAndClampedTo16Bits() {
        assertEquals(12, VoiceAudio.pcm16(11.6f));
        assertEquals(Short.MAX_VALUE, VoiceAudio.pcm16(40_000f));
        assertEquals(Short.MIN_VALUE, VoiceAudio.pcm16(-40_000f));
    }

    @Test
    public void durationIsTheSampleCountAtTheRecordersRate() {
        assertEquals(1000, VoiceAudio.durationMs(VoiceAudio.SAMPLE_RATE));
        assertEquals(750, VoiceAudio.durationMs(33_075));
    }

    @Test
    public void theSelectionStaysBetweenTheVoiceMessageLimits() {
        long duration = 600_000;
        // Moving the start past the end pushes the end out.
        assertArrayEquals(new long[]{10_000, 10_750}, VoiceAudio.clampRange(10_000, 5_000, duration, true));
        // Moving the start away keeps at most five minutes.
        assertArrayEquals(new long[]{0, 300_000}, VoiceAudio.clampRange(0, 400_000, duration, true));
        // The start stays far enough from the end of the file.
        assertArrayEquals(new long[]{599_250, 600_000}, VoiceAudio.clampRange(600_000, 600_000, duration, true));
        // Moving the end before the start pulls the start back.
        assertArrayEquals(new long[]{4_250, 5_000}, VoiceAudio.clampRange(10_000, 5_000, duration, false));
        assertArrayEquals(new long[]{0, 750}, VoiceAudio.clampRange(0, 100, duration, false));
        assertArrayEquals(new long[]{200_000, 500_000}, VoiceAudio.clampRange(0, 500_000, duration, false));
        // A short file is selected whole.
        assertArrayEquals(new long[]{0, 2_000}, VoiceAudio.clampRange(0, VoiceAudio.MAX_MS, 2_000, false));
    }

    @Test
    public void timesShowMinutesSecondsAndTenths() {
        assertEquals("0:00.0", VoiceAudio.time(0));
        assertEquals("0:12.3", VoiceAudio.time(12_345));
        assertEquals("5:00.0", VoiceAudio.time(300_000));
    }

    private static short[] resample(int from, int to, short[]... chunks) {
        VoiceAudio.Resampler resampler = new VoiceAudio.Resampler(from, to);
        VoiceAudio.Pcm out = new VoiceAudio.Pcm(0);
        for (short[] chunk : chunks) resampler.add(chunk, chunk.length, out);
        return Arrays.copyOf(out.data, out.size);
    }
}
