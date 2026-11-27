package app.morphe.extension.instagram.patches.dm.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.media.MediaCodecInfo;
import android.media.MediaRecorder;

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
        short[] samples = new short[4410 * 2 + 1];
        samples[5] = 1000;
        samples[6] = -2000;
        samples[4410 * 2] = Short.MIN_VALUE;
        List<Float> waveform = VoiceAudio.waveform(samples, samples.length, 44100);
        assertEquals(Arrays.asList(VoiceAudio.level(2000), 0f, 1f), waveform);
        // Samples past the size are not part of the audio.
        assertEquals(1, VoiceAudio.waveform(samples, 10, 44100).size());
        assertEquals(0, VoiceAudio.waveform(samples, 0, 44100).size());
        // At 48 kHz a window is 4800 samples.
        assertEquals(2, VoiceAudio.waveform(samples, samples.length, 48000).size());
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
        assertArrayEquals(new short[]{0, 5, 10, 15}, resample(22050, 44100, new short[]{0, 10}, new short[]{20}));
    }

    @Test
    public void downsamplingRemovesWhatTheLowerRateCannotHold() {
        // 23 kHz is above 44.1 kHz's Nyquist frequency and would fold back to 21.1 kHz.
        assertTrue(peakAfter(resampleTone(23_000)) < 100);
        assertTrue(Math.abs(peakAfter(resampleTone(1_000)) - 10_000) < 300);
    }

    @Test
    public void theLowPassHasUnitGain() {
        float sum = 0;
        for (float tap : VoiceAudio.lowPassTaps(48000, 44100)) sum += tap;
        assertEquals(1f, sum, 1e-4f);
    }

    /** A 10000 peak tone at 48 kHz, fed in two chunks, at 44.1 kHz. */
    private static short[] resampleTone(int frequency) {
        short[] tone = new short[4800];
        for (int i = 0; i < tone.length; i++) {
            tone[i] = (short) Math.round(10_000 * Math.sin(2 * Math.PI * frequency * i / 48000.0));
        }
        return resample(48000, 44100, Arrays.copyOfRange(tone, 0, 1000), Arrays.copyOfRange(tone, 1000, tone.length));
    }

    /** The peak after the filter's start-up. */
    private static int peakAfter(short[] samples) {
        int peak = 0;
        for (int i = 200; i < samples.length; i++) peak = Math.max(peak, Math.abs(samples[i]));
        return peak;
    }

    @Test
    public void floatSamplesAreRoundedAndClampedTo16Bits() {
        assertEquals(12, VoiceAudio.pcm16(11.6f));
        assertEquals(Short.MAX_VALUE, VoiceAudio.pcm16(40_000f));
        assertEquals(Short.MIN_VALUE, VoiceAudio.pcm16(-40_000f));
    }

    @Test
    public void durationIsTheSampleCountAtTheRecordersRate() {
        assertEquals(1000, VoiceAudio.durationMs(44100, 44100));
        assertEquals(750, VoiceAudio.durationMs(33_075, 44100));
        assertEquals(750, VoiceAudio.durationMs(36_000, 48000));
    }

    @Test
    public void theFormatIsTheRecordersAacSettingsWithItsPixel4SampleRate() {
        int[] settings = {MediaRecorder.AudioEncoder.HE_AAC, 32_000, 44100, 48000};
        VoiceAudio.Format format = VoiceAudio.Format.of(settings, false);
        assertEquals(44100, format.sampleRate);
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectHE, format.aacProfile);
        assertEquals(32_000, format.bitRate);
        assertEquals(48000, VoiceAudio.Format.of(settings, true).sampleRate);
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectLC, VoiceAudio.aacProfile(MediaRecorder.AudioEncoder.AAC));
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectELD, VoiceAudio.aacProfile(MediaRecorder.AudioEncoder.AAC_ELD));
    }

    @Test
    public void recordersThatDoNotWriteAacAreRefused() {
        assertNull(VoiceAudio.Format.of(new int[]{MediaRecorder.AudioEncoder.AMR_NB, 32_000, 44100, 48000}, false));
        assertNull(VoiceAudio.Format.of(new int[]{MediaRecorder.AudioEncoder.DEFAULT, 32_000, 44100, 48000}, false));
        assertNull(VoiceAudio.Format.of(new int[]{MediaRecorder.AudioEncoder.AAC, 0, 44100, 48000}, false));
        assertNull(VoiceAudio.Format.of(null, false));
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
