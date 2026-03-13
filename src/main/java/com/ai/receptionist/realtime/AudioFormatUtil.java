package com.ai.receptionist.realtime;

import java.util.Base64;

/**
 * Audio format conversion utilities for bridging Twilio (mu-law 8kHz) and
 * OpenAI Realtime API (PCM16 24kHz).
 *
 * Twilio Media Streams deliver mu-law 8-bit 8kHz mono audio.
 * OpenAI Realtime API expects and produces PCM16 (signed 16-bit little-endian) at 24kHz.
 */
public final class AudioFormatUtil {

    private AudioFormatUtil() {}

    /**
     * G.711 mu-law decode table.
     * Maps each 8-bit mu-law byte (0-255) to a signed 16-bit PCM sample.
     */
    private static final short[] MULAW_DECODE_TABLE = new short[256];

    static {
        for (int i = 0; i < 256; i++) {
            int mulaw = ~i & 0xFF;
            int sign = (mulaw & 0x80) != 0 ? -1 : 1;
            int exponent = (mulaw >> 4) & 0x07;
            int mantissa = mulaw & 0x0F;
            int magnitude = ((mantissa << 1) + 33) << (exponent + 2);
            magnitude -= 33;
            MULAW_DECODE_TABLE[i] = (short) (sign * magnitude);
        }
    }

    /**
     * Convert mu-law 8kHz audio to PCM16 8kHz.
     * Each input byte produces one 16-bit sample (2 output bytes, little-endian).
     *
     * @param mulawBytes mu-law encoded audio (8-bit, 8kHz, mono)
     * @return PCM16 audio (signed 16-bit little-endian, 8kHz, mono)
     */
    public static byte[] mulawToPcm16(byte[] mulawBytes) {
        byte[] pcm = new byte[mulawBytes.length * 2];
        for (int i = 0; i < mulawBytes.length; i++) {
            short sample = MULAW_DECODE_TABLE[mulawBytes[i] & 0xFF];
            pcm[i * 2] = (byte) (sample & 0xFF);           // low byte
            pcm[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF); // high byte
        }
        return pcm;
    }

    /**
     * Upsample PCM16 from 8kHz to 24kHz using linear interpolation (3x).
     * For each input sample, produces 3 output samples.
     *
     * @param pcm8kHz PCM16 audio at 8kHz (little-endian)
     * @return PCM16 audio at 24kHz (little-endian)
     */
    public static byte[] pcm16_8kTo24k(byte[] pcm8kHz) {
        int sampleCount = pcm8kHz.length / 2;
        byte[] result = new byte[sampleCount * 2 * 3]; // 3x samples

        for (int i = 0; i < sampleCount; i++) {
            short current = readSample(pcm8kHz, i);
            short next = (i + 1 < sampleCount) ? readSample(pcm8kHz, i + 1) : current;

            int outIdx = i * 3;
            writeSample(result, outIdx, current);
            writeSample(result, outIdx + 1, (short) (current + (next - current) / 3));
            writeSample(result, outIdx + 2, (short) (current + 2 * (next - current) / 3));
        }
        return result;
    }

    /**
     * Downsample PCM16 from 24kHz to 8kHz by taking every 3rd sample.
     *
     * @param pcm24kHz PCM16 audio at 24kHz (little-endian)
     * @return PCM16 audio at 8kHz (little-endian)
     */
    public static byte[] pcm16_24kTo8k(byte[] pcm24kHz) {
        int sampleCount = pcm24kHz.length / 2;
        int outSamples = sampleCount / 3;
        byte[] result = new byte[outSamples * 2];

        for (int i = 0; i < outSamples; i++) {
            short sample = readSample(pcm24kHz, i * 3);
            writeSample(result, i, sample);
        }
        return result;
    }

    /**
     * Convert PCM16 8kHz audio to mu-law 8kHz.
     * Each 16-bit PCM sample (2 bytes) produces one mu-law byte.
     *
     * @param pcm16Bytes PCM16 audio (signed 16-bit little-endian, 8kHz)
     * @return mu-law encoded audio (8-bit, 8kHz)
     */
    public static byte[] pcm16ToMulaw(byte[] pcm16Bytes) {
        int sampleCount = pcm16Bytes.length / 2;
        byte[] mulaw = new byte[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short sample = readSample(pcm16Bytes, i);
            mulaw[i] = encodeMulaw(sample);
        }
        return mulaw;
    }

    public static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] fromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    // --- Internal helpers ---

    private static short readSample(byte[] pcm, int sampleIndex) {
        int offset = sampleIndex * 2;
        return (short) ((pcm[offset] & 0xFF) | (pcm[offset + 1] << 8));
    }

    private static void writeSample(byte[] pcm, int sampleIndex, short value) {
        int offset = sampleIndex * 2;
        pcm[offset] = (byte) (value & 0xFF);
        pcm[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static final int MULAW_BIAS = 0x84;  // 132
    private static final int MULAW_MAX = 0x7FFF;  // 32767

    private static byte encodeMulaw(short sample) {
        int sign;
        int magnitude;

        // Get sign and magnitude
        if (sample < 0) {
            magnitude = -sample;
            sign = 0x80;
        } else {
            magnitude = sample;
            sign = 0;
        }

        // Clip to max
        if (magnitude > MULAW_MAX) magnitude = MULAW_MAX;

        // Add bias for encoding
        magnitude += MULAW_BIAS;

        // Find the segment (exponent)
        int exponent = 7;
        for (int expMask = 0x4000; (magnitude & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {
            // find leading 1
        }

        // Extract mantissa
        int mantissa = (magnitude >> (exponent + 3)) & 0x0F;

        // Combine sign, exponent, mantissa and complement
        return (byte) ~(sign | (exponent << 4) | mantissa);
    }
}
