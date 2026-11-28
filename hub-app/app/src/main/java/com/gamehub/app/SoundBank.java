package com.gamehub.app;

import java.util.Random;

/**
 * Every NEXUS sound, made up in code from scratch (no recordings, nobody else's sounds): the clicks for scrolling
 * and tapping, the sounds for opening an app, and the home screen's background music loops. Plain Java with no
 * Android parts, so each sound can be built and checked off the phone too.
 *
 * Each sound is 16-bit mono at {@link #RATE}. Clicks and opening sounds are short one-shots; background music is a
 * seamless loop of {@link #LOOP_S} seconds (its end is blended into its start).
 */
public final class SoundBank {
    private SoundBank() {}

    public static final int RATE = 22050;
    public static final int LOOP_S = 16;
    private static final double TAU = 2 * Math.PI;

    // ------------------------------------------------------------------ the choices (ids are saved; names are shown)

    /** Scrolling and tapping sounds (the same set for both, so each can be its own). */
    public static final String[] CLICK_IDS = {"glass", "soft", "wood", "bubble", "crystal", "pulse", "retro", "drop", "thock", "off"};
    public static final String[] CLICK_NAMES = {"Glass", "Soft click", "Wood", "Bubble", "Crystal", "Pulse", "Retro", "Water drop", "Key thock", "Off"};

    /** Opening an app. */
    public static final String[] OPEN_IDS = {"rise", "chord", "swell", "whoosh", "bell", "arcade", "harp", "sparkle", "portal", "off"};
    public static final String[] OPEN_NAMES = {"Rising glass", "Bright chord", "Warm swell", "Whoosh", "Deep bell", "Arcade", "Harp", "Sparkle", "Portal", "Off"};

    /** The home screen's background music. */
    public static final String[] BG_IDS = {"drone", "pad", "night", "ocean", "stars", "rain", "pulse", "musicbox"};
    public static final String[] BG_NAMES = {"Deep drone", "Warm pad", "Night wind", "Ocean", "Starlight", "Soft rain", "Slow pulse", "Music box"};

    public static String name(String[] ids, String[] names, String id) {
        for (int i = 0; i < ids.length; i++) if (ids[i].equals(id)) return names[i];
        return names[0];
    }

    // ------------------------------------------------------------------ clicks

    /** A scrolling or tapping sound, or null for "off" (or an unknown id). */
    public static short[] click(String id) {
        switch (id) {
            case "glass": return glassTick();
            case "soft": return softClick();
            case "wood": return wood();
            case "bubble": return bubble();
            case "crystal": return crystal();
            case "pulse": return pulse();
            case "retro": return retroBlip();
            case "drop": return waterDrop();
            case "thock": return thock();
            default: return null;
        }
    }

    /** A soft glassy "tink": a bell-like tone with glass overtones and a breath of air, ringing out in a dark space. */
    private static short[] glassTick() {
        float len = 0.5f; int n = (int) (RATE * len);
        Random r = new Random(5);
        float[] dry = new float[n];
        float hp = 0, px = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float att = Math.min(1f, t / 0.0015f);
            float f = 1720f;
            float body = (float) (Math.sin(TAU * f * t) * Math.exp(-t * 48f) * 0.55f);
            float glass = (float) (Math.sin(TAU * f * 2.76f * t) * Math.exp(-t * 95f) * 0.22f
                + Math.sin(TAU * f * 5.40f * t) * Math.exp(-t * 160f) * 0.08f);
            float nz = r.nextFloat() * 2f - 1f;
            hp = 0.7f * hp + nz - px; px = nz;
            float air = (float) (hp * Math.exp(-t * 260f) * 0.10f);
            dry[i] = (body + glass + air) * att;
        }
        return finish(space(dry, 0.70f, 0.4f, 0.36f), len, 0.08f, 0.12f);
    }

    /** A quiet, rounded click, like a soft button. */
    private static short[] softClick() {
        float len = 0.14f; int n = (int) (RATE * len);
        Random r = new Random(8);
        float[] y = new float[n];
        float lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float nz = (r.nextFloat() * 2f - 1f) * (float) Math.exp(-t * 900f);
            lp += 0.35f * (nz - lp); lp2 += 0.35f * (lp - lp2);
            float tone = (float) (Math.sin(TAU * 1050 * t) * Math.exp(-t * 220f)) * Math.min(1f, t / 0.0008f);
            y[i] = lp2 * 1.6f + tone * 0.55f;
        }
        return finish(space(y, 0.35f, 0.5f, 0.10f), len, 0.04f, 0.12f);
    }

    /** A small hollow woodblock knock. */
    private static short[] wood() {
        float len = 0.22f; int n = (int) (RATE * len);
        Random r = new Random(3);
        float[] y = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float att = Math.min(1f, t / 0.0006f);
            float body = (float) (Math.sin(TAU * 820 * t) * Math.exp(-t * 62f) * 0.6f
                + Math.sin(TAU * 820 * 2.42 * t) * Math.exp(-t * 115f) * 0.25f
                + Math.sin(TAU * 820 * 3.9 * t) * Math.exp(-t * 190f) * 0.08f);
            float nz = (r.nextFloat() * 2f - 1f) * (float) Math.exp(-t * 1400f);
            lp += 0.5f * (nz - lp);
            y[i] = (body + lp * 0.5f) * att;
        }
        return finish(space(y, 0.45f, 0.45f, 0.14f), len, 0.05f, 0.12f);
    }

    /** A round, low "blop" that rises a little, like a bubble. */
    private static short[] bubble() {
        float len = 0.2f; int n = (int) (RATE * len);
        float[] y = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            double f = 320 + 520 * Math.min(1.0, t / 0.055);
            ph += f / RATE;
            float env = Math.min(1f, t / 0.002f) * (float) Math.exp(-t * 38f);
            y[i] = (float) Math.sin(TAU * ph) * env;
        }
        return finish(space(y, 0.5f, 0.5f, 0.18f), len, 0.05f, 0.12f);
    }

    /** A tiny high crystal bell with a long, airy ring. */
    private static short[] crystal() {
        float len = 0.7f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float f = 2637f;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float att = Math.min(1f, t / 0.001f);
            y[i] = (float) (Math.sin(TAU * f * t) * Math.exp(-t * 26f) * 0.5f
                + Math.sin(TAU * f * 2.0 * t) * Math.exp(-t * 50f) * 0.16f
                + Math.sin(TAU * f * 3.11 * t) * Math.exp(-t * 80f) * 0.07f) * att;
        }
        return finish(space(y, 0.78f, 0.35f, 0.42f), len, 0.12f, 0.10f);
    }

    /** A clean, simple sine pip with no echo. */
    private static short[] pulse() {
        float len = 0.12f; int n = (int) (RATE * len);
        float[] y = new float[n];
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            y[i] = (float) (Math.sin(TAU * 660 * t) + 0.18 * Math.sin(TAU * 1320 * t)) * Math.min(1f, t / 0.003f) * (float) Math.exp(-t * 42f);
        }
        return finish(y, len, 0.03f, 0.115f);
    }

    /** A short, softened square-wave blip, like an old handheld game (an original sound). */
    private static short[] retroBlip() {
        float len = 0.09f; int n = (int) (RATE * len);
        float[] y = new float[n];
        double ph = 0; float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            double f = t < 0.022f ? 988 : 1319;
            ph += f / RATE;
            float sq = (ph % 1.0) < 0.5 ? 1f : -1f;
            lp += 0.3f * (sq - lp);
            float env = Math.min(1f, t / 0.001f) * (t < 0.05f ? 1f : (float) Math.exp(-(t - 0.05f) * 90f));
            y[i] = lp * env;
        }
        return finish(y, len, 0.02f, 0.095f);
    }

    /** A quick, bright "plip" of a single drop, with a little echo. */
    private static short[] waterDrop() {
        float len = 0.35f; int n = (int) (RATE * len);
        float[] y = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            double f = 950 + 1450 * Math.pow(Math.min(1.0, t / 0.03), 0.7);
            ph += f / RATE;
            float env = Math.min(1f, t / 0.0015f) * (float) Math.exp(-t * 70f);
            y[i] = (float) Math.sin(TAU * ph) * env;
        }
        return finish(space(y, 0.62f, 0.45f, 0.30f), len, 0.08f, 0.12f);
    }

    /** A deep, muted keyboard "thock". */
    private static short[] thock() {
        float len = 0.16f; int n = (int) (RATE * len);
        Random r = new Random(12);
        float[] y = new float[n];
        float lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float att = Math.min(1f, t / 0.0008f);
            float body = (float) (Math.sin(TAU * 190 * t) * Math.exp(-t * 55f) * 0.7f
                + Math.sin(TAU * 410 * t) * Math.exp(-t * 95f) * 0.3f);
            float nz = (r.nextFloat() * 2f - 1f) * (float) Math.exp(-t * 700f);
            lp += 0.25f * (nz - lp); lp2 += 0.25f * (lp - lp2);
            y[i] = (body + lp2 * 2.2f) * att;
        }
        return finish(space(y, 0.3f, 0.6f, 0.08f), len, 0.04f, 0.13f);
    }

    // ------------------------------------------------------------------ opening an app

    /** The sound for opening an app, or null for "off" (or an unknown id). */
    public static short[] open(String id) {
        switch (id) {
            case "rise": return risingGlass();
            case "chord": return brightChord();
            case "swell": return warmSwell();
            case "whoosh": return whoosh();
            case "bell": return deepBell();
            case "arcade": return arcade();
            case "harp": return harp();
            case "sparkle": return sparkle();
            case "portal": return portal();
            default: return null;
        }
    }

    /** Two soft glass notes a beat apart, rising, ringing out. */
    private static short[] risingGlass() {
        float len = 1.0f; int n = (int) (RATE * len);
        float[] dry = new float[n];
        float[][] notes = {{0f, 1318.5f, 0.5f}, {0.075f, 1975.5f, 0.45f}};
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float v = 0;
            for (float[] nt : notes) {
                float tt = t - nt[0];
                if (tt < 0) continue;
                float env = Math.min(1f, tt / 0.004f) * (float) Math.exp(-tt * 6.5f);
                float f = nt[1];
                v += (float) (Math.sin(TAU * f * tt) * 0.55f + Math.sin(TAU * f * 2.76f * tt) * 0.16f * Math.exp(-tt * 14f)
                    + Math.sin(TAU * f * 2f * tt) * 0.12f) * env * nt[2];
            }
            dry[i] = v;
        }
        return finish(space(dry, 0.76f, 0.4f, 0.42f), len, 0.12f, 0.15f);
    }

    /** Three quick notes climbing a bright major chord. */
    private static short[] brightChord() {
        float len = 1.1f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float[][] notes = {{0f, 1046.5f, 0.5f}, {0.06f, 1318.5f, 0.45f}, {0.12f, 1568f, 0.45f}};
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float v = 0;
            for (float[] nt : notes) {
                float tt = t - nt[0];
                if (tt < 0) continue;
                float env = Math.min(1f, tt / 0.003f) * (float) Math.exp(-tt * 5f);
                v += (float) (Math.sin(TAU * nt[1] * tt) + 0.22 * Math.sin(TAU * nt[1] * 2 * tt) * Math.exp(-tt * 9f)) * env * nt[2];
            }
            y[i] = v;
        }
        return finish(space(y, 0.75f, 0.4f, 0.4f), len, 0.15f, 0.15f);
    }

    /** A soft, warm chord that swells up and fades away. */
    private static short[] warmSwell() {
        float len = 1.1f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float[] fs = {220f, 329.63f, 440f, 554.37f};
        float[] amp = {0.5f, 0.4f, 0.32f, 0.22f};
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float rise = t < 0.22f ? (float) Math.pow(Math.sin(Math.PI / 2 * t / 0.22f), 2) : 1f;
            float env = rise * (t < 0.22f ? 1f : (float) Math.exp(-(t - 0.22f) * 3.2f));
            float v = 0;
            for (int k = 0; k < fs.length; k++)
                v += (float) (Math.sin(TAU * fs[k] * t) + Math.sin(TAU * fs[k] * 1.004 * t + 0.7)) * 0.5f * amp[k];
            y[i] = v * env;
        }
        return finish(space(y, 0.7f, 0.5f, 0.3f), len, 0.2f, 0.15f);
    }

    /** A quick airy sweep upward, ending on a faint glint. */
    private static short[] whoosh() {
        float len = 0.75f; int n = (int) (RATE * len);
        Random r = new Random(21);
        float[] y = new float[n];
        float low = 0, band = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            double fc = 350 + 3300 * Math.pow(Math.min(1.0, t / 0.38), 1.6);
            float fk = (float) (2 * Math.sin(Math.PI * fc / RATE));
            float in = r.nextFloat() * 2f - 1f;
            float high = in - low - 0.55f * band;
            band += fk * high; low += fk * band;
            float env = t < 0.42f ? (float) Math.pow(Math.sin(Math.PI * t / 0.42f), 1.5) : 0f;
            float tt = t - 0.3f;
            float glint = tt > 0 ? (float) (Math.sin(TAU * 1975.5 * tt) * Math.exp(-tt * 9f)) * Math.min(1f, tt / 0.004f) * 0.18f : 0f;
            y[i] = band * env * 0.9f + glint;
        }
        return finish(space(y, 0.6f, 0.4f, 0.25f), len, 0.15f, 0.15f);
    }

    /** One deep, round bell stroke with a long ring. */
    private static short[] deepBell() {
        float len = 1.6f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float f = 392f;
        float[] ratio = {0.5f, 1f, 1.19f, 1.5f, 2f, 2.52f, 3.01f};
        float[] amp = {0.35f, 0.6f, 0.25f, 0.2f, 0.22f, 0.1f, 0.07f};
        float[] dec = {1.8f, 2.6f, 3.5f, 4.2f, 5f, 7f, 9f};
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            float att = Math.min(1f, t / 0.002f);
            float v = 0;
            for (int k = 0; k < ratio.length; k++) v += (float) (Math.sin(TAU * f * ratio[k] * t) * Math.exp(-t * dec[k])) * amp[k];
            y[i] = v * att;
        }
        return finish(space(y, 0.72f, 0.5f, 0.3f), len, 0.3f, 0.15f);
    }

    /** Three quick rising blips, like an old arcade cabinet (an original sound). */
    private static short[] arcade() {
        float len = 0.32f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float[] fs = {784f, 1046.5f, 1568f};
        double ph = 0; float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            int k = Math.min(2, (int) (t / 0.05f));
            ph += fs[k] / RATE;
            float sq = (ph % 1.0) < 0.5 ? 1f : -1f;
            lp += 0.28f * (sq - lp);
            float local = t - k * 0.05f;
            float env = Math.min(1f, local / 0.001f) * (k < 2 ? 1f : (float) Math.exp(-local * 14f));
            y[i] = lp * env;
        }
        return finish(space(y, 0.4f, 0.5f, 0.1f), len, 0.06f, 0.12f);
    }

    /** Four plucked strings rolling upward, like a quick harp glissando. */
    private static short[] harp() {
        float len = 1.3f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float[] fs = {523.25f, 659.25f, 784f, 1046.5f};
        Random r = new Random(4);
        for (int k = 0; k < fs.length; k++) {
            int start = (int) (RATE * 0.05f * k);
            int p = Math.max(2, Math.round(RATE / fs[k]));
            float[] buf = new float[p];
            float lpn = 0;
            for (int j = 0; j < p; j++) { lpn += 0.5f * ((r.nextFloat() * 2f - 1f) - lpn); buf[j] = lpn; }
            int idx = 0;
            for (int i = start; i < n; i++) {
                int nx = (idx + 1) % p;
                float v = buf[idx];
                buf[idx] = (buf[idx] + buf[nx]) * 0.5f * 0.9965f;
                idx = nx;
                y[i] += v * 0.5f;
            }
        }
        return finish(space(y, 0.65f, 0.45f, 0.25f), len, 0.25f, 0.15f);
    }

    /** A handful of tiny high glints scattered over a moment, ringing together. */
    private static short[] sparkle() {
        float len = 1.0f; int n = (int) (RATE * len);
        float[] y = new float[n];
        float[] pool = {2093f, 2349.3f, 2637f, 3136f, 3520f};
        Random r = new Random(11);
        for (int g = 0; g < 7; g++) {
            float st = g * 0.045f + r.nextFloat() * 0.03f;
            float f = pool[r.nextInt(pool.length)];
            float a = 0.6f - g * 0.05f;
            int s0 = (int) (st * RATE);
            for (int i = s0; i < n; i++) {
                float tt = (float) (i - s0) / RATE;
                y[i] += (float) ((Math.sin(TAU * f * tt) + 0.2 * Math.sin(TAU * f * 2 * tt)) * Math.exp(-tt * 22f)) * Math.min(1f, tt / 0.001f) * a;
            }
        }
        return finish(space(y, 0.78f, 0.35f, 0.45f), len, 0.2f, 0.13f);
    }

    /** A low, round "whomp" that drops away, as if stepping through a doorway. */
    private static short[] portal() {
        float len = 1.1f; int n = (int) (RATE * len);
        Random r = new Random(9);
        float[] y = new float[n];
        double ph = 0; float lp = 0, lp2 = 0;
        for (int i = 0; i < n; i++) {
            float t = (float) i / RATE;
            double f = 110 + 220 * Math.exp(-t * 9);
            ph += f / RATE;
            float env = Math.min(1f, t / 0.01f) * (float) Math.exp(-t * 4.5f);
            float tone = (float) (Math.sin(TAU * ph) + 0.35 * Math.sin(TAU * ph * 2)) * env;
            float nz = r.nextFloat() * 2f - 1f;
            float a = (float) (1 - Math.exp(-TAU * (300 + 1500 * Math.exp(-t * 6)) / RATE));
            lp += a * (nz - lp); lp2 += a * (lp - lp2);
            y[i] = tone * 0.8f + lp2 * env * 0.6f;
        }
        return finish(space(y, 0.7f, 0.5f, 0.3f), len, 0.25f, 0.15f);
    }

    // ------------------------------------------------------------------ background music

    /**
     * A background music loop ({@link #LOOP_S} seconds), at a similar loudness whichever it is. It's made to repeat
     * exactly: every tone is a whole number of cycles per loop, every slow change follows the loop, and the noise
     * repeats with it. Two loops are made and the second is kept (by then every filter has settled into the loop),
     * so it joins up without a seam or a swell.
     */
    public static short[] background(String id) {
        int n = RATE * LOOP_S;
        float[] out = new float[2 * n];
        switch (id) {
            case "pad": warmPad(out, n); break;
            case "night": nightWind(out, n); break;
            case "ocean": ocean(out, n); break;
            case "stars": starlight(out, n); break;
            case "rain": softRain(out, n); break;
            case "pulse": slowPulse(out, n); break;
            case "musicbox": musicBox(out, n); break;
            default: drone(out, n); break;
        }
        // Same loudness for every loop (by its average level); the odd peak above half scale is rounded off gently
        double sum = 0;
        for (int i = n; i < 2 * n; i++) sum += out[i] * (double) out[i];
        double g = BG_RMS / (Math.sqrt(sum / n) + 1e-9);
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            double v = out[n + i] * g, m = Math.abs(v);
            if (m > 0.5) m = 0.5 + (m - 0.5) / (1 + (m - 0.5) / 0.3);
            s[i] = (short) Math.round(Math.copySign(Math.min(m, 0.8), v) * 32767);
        }
        return s;
    }

    /** How loud (on average) each background loop plays, of full scale (the deep drone's level). */
    private static final double BG_RMS = 0.165;

    private static final float[] LUT = new float[8192];
    static { for (int i = 0; i < 8192; i++) LUT[i] = (float) Math.sin(i * TAU / 8192); }
    /** A sine by cycles (not radians): one table look-up. */
    private static float sn(double cycles) { double f = cycles - Math.floor(cycles); return LUT[(int) (f * 8192) & 8191]; }
    /** Where in the loop (0..1) time t is. */
    private static double ph(double t) { return (t % LOOP_S) / LOOP_S; }
    /** The nearest pitch that fits a whole number of times into the loop. */
    private static double q(double f) { return Math.round(f * LOOP_S) / (double) LOOP_S; }
    /** White noise that repeats every loop: [n] values, read round and round. */
    private static float[] noise(int n, long seed) {
        Random r = new Random(seed);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) v[i] = r.nextFloat() * 2f - 1f;
        return v;
    }

    /** One note of a pad: two tones a hair apart (so it shimmers) and a soft octave, all fitting the loop. */
    private static float padNote(double f, double t, double o) {
        double f1 = q(f), f2 = q(f * 1.0035);
        if (f2 == f1) f2 = f1 + 1.0 / LOOP_S;
        return (sn(f1 * t + o) + sn(f2 * t + o + 0.37)) * 0.5f + 0.12f * sn(2 * f1 * t + o * 2);
    }

    /**
     * A deep, slowly breathing drone (55 Hz and its harmonics, twice, a quarter hertz apart), a soft pad an octave and
     * a fifth above, and a low wash of filtered noise swelling and receding twice a loop.
     */
    private static void drone(float[] out, int n) {
        float[] nz = noise(n, 7);
        float[] harm = new float[9];
        for (int k = 1; k < 9; k++) harm[k] = (float) (1 / Math.pow(k, 1.25));
        float lp = 0, lp2 = 0, brown = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            float d = 0;
            for (int k = 1; k <= 8; k++) d += harm[k] * (sn(k * 55.0 * t + k * 0.11) + sn(k * 55.25 * t + k * 0.3)) * 0.5f;
            float trem = (float) (0.8 + 0.2 * Math.sin(TAU * p * 2 + 0.3));
            float pad = 0.4f * (sn(110.0 * t) + sn(110.25 * t + 0.16)) * 0.5f + 0.28f * (sn(165.0 * t + 0.06) + sn(165.25 * t + 0.35)) * 0.5f;
            float w = nz[i % n];
            double cut = 250.0 + 400.0 * (0.5 - 0.5 * Math.cos(TAU * p * 2));
            float a = (float) (1.0 - Math.exp(-TAU * cut / RATE));
            lp += a * (w - lp); lp2 += a * (lp - lp2);
            float swell = (float) (0.25 + 0.75 * Math.pow(0.5 - 0.5 * Math.cos(TAU * p * 2 - 0.5), 1.5));
            brown = (brown + 0.02f * w) * 0.995f;
            out[i] = d * 0.55f * trem + pad * 0.35f + lp2 * 1.6f * swell + brown * 0.5f;
        }
    }

    /** A soft, warm pad drifting between two chords (A minor, then F major), eight seconds each. */
    private static void warmPad(float[] out, int n) {
        double[] a = {110, 164.81, 220, 261.63, 329.63};
        double[] b = {87.31, 130.81, 174.61, 220, 261.63};
        float[] amp = {0.5f, 0.42f, 0.36f, 0.3f, 0.22f};
        float[] nz = noise(n, 2);
        float lp = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            double wa = 0.5 + 0.5 * Math.cos(TAU * p), wb = 1 - wa;
            float va = 0, vb = 0;
            for (int k = 0; k < 5; k++) { va += padNote(a[k], t, k * 0.13) * amp[k]; vb += padNote(b[k], t, k * 0.29) * amp[k]; }
            float breath = (float) (0.85 + 0.15 * Math.sin(TAU * p * 4));
            lp += 0.02f * (nz[i % n] - lp);
            out[i] = (float) (va * Math.sqrt(wa) + vb * Math.sqrt(wb)) * breath + lp * 0.8f;
        }
    }

    /** Wind moving through the dark, with two low, quiet tones and a few far-off chimes. */
    private static void nightWind(float[] out, int n) {
        float[] nz = noise(n, 31);
        float low = 0, band = 0;
        double[][] chimes = {{2.0, q(1174.66)}, {7.5, q(1318.51)}, {12.3, q(880.0)}};
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            double fc = 520 + 260 * Math.sin(TAU * p * 2 + 0.4) + 140 * Math.sin(TAU * p * 5 + 1.3);
            float fk = (float) (2 * Math.sin(Math.PI * fc / RATE));
            float high = nz[i % n] - low - 1.1f * band;
            band += fk * high; low += fk * band;
            float gust = (float) Math.pow(0.55 + 0.45 * Math.sin(TAU * p * 3 + 0.8) * Math.sin(TAU * p + 0.2), 1.4);
            float tones = 0.18f * (sn(q(73.42) * t) + sn(q(73.6) * t + 0.3)) * 0.5f + 0.1f * sn(110.0 * t + 0.5);
            float ch = 0;
            double tl = t % LOOP_S;
            for (double[] c : chimes) for (double tt : new double[]{tl - c[0], tl + LOOP_S - c[0]}) {
                if (tt >= 0 && tt < 5) ch += (float) ((sn(c[1] * tt) + 0.25 * sn(c[1] * 2.76 * tt)) * Math.exp(-tt * 1.4) * Math.min(1.0, tt / 0.004)) * 0.05f;
            }
            out[i] = band * 0.22f * gust + tones + ch;
        }
    }

    /** Slow waves rolling in and drawing back (two a loop), over a faint low hum. */
    private static void ocean(float[] out, int n) {
        float[] nz = noise(n, 17);
        float lp = 0, lp2 = 0, hp = 0, px = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            double wp = (p * 2) % 1.0;
            // A wave: builds for a while, breaks, then draws back slowly
            double env = wp < 0.35 ? Math.pow(wp / 0.35, 2) : Math.exp(-(wp - 0.35) * 4.2);
            env = 0.12 + 0.88 * env;
            float w = nz[i % n];
            double cut = 180 + 1500 * env;
            float a = (float) (1 - Math.exp(-TAU * cut / RATE));
            lp += a * (w - lp); lp2 += a * (lp - lp2);
            hp = 0.9f * (hp + w - px); px = w;
            float foam = (float) (hp * Math.pow(env, 3) * 0.05);
            float hum = 0.08f * (sn(55.0 * t) + sn(q(82.41) * t + 0.2)) * 0.5f;
            out[i] = (float) (lp2 * env * 1.4) + foam + hum;
        }
    }

    /** High, soft tones twinkling in and out over a warm low hum, like a sky full of stars. */
    private static void starlight(float[] out, int n) {
        double[] f = {880, q(987.77), q(1108.73), q(1318.51), 1480, 1760};
        int[] rate = {1, 2, 3, 2, 1, 3};
        double[] off = {0.0, 0.31, 0.62, 0.17, 0.48, 0.83};
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            float tw = 0;
            for (int k = 0; k < f.length; k++) {
                double s = Math.sin(TAU * (p * rate[k] + off[k]));
                double a = s > 0 ? s * s * s : 0;
                tw += (float) (sn(f[k] * t + k * 0.1) * a) * 0.16f;
            }
            float hum = 0.3f * (sn(55.0 * t) + sn(55.1875 * t + 0.4)) * 0.5f + 0.18f * sn(110.0 * t + 0.1) + 0.12f * padNote(220, t, 0.2) + 0.08f * padNote(329.63, t, 0.6);
            out[i] = tw + hum;
        }
    }

    /** Gentle rain with drops landing here and there, over a quiet, warm chord. */
    private static void softRain(float[] out, int n) {
        float[] nz = noise(n, 23);
        // The drops: tiny, bright ticks, about thirty a second (placed round the loop, so they repeat with it)
        float[] drops = new float[n];
        Random dr = new Random(29);
        for (int d = 0; d < 30 * LOOP_S; d++) {
            int s0 = dr.nextInt(n);
            double f = 2200 + dr.nextFloat() * 2600;
            float a = 0.05f + dr.nextFloat() * 0.12f;
            for (int j = 0; j < 300; j++) {
                double tt = (double) j / RATE;
                drops[(s0 + j) % n] += (float) (Math.sin(TAU * f * tt) * Math.exp(-tt * 600)) * a;
            }
        }
        float lp = 0, lp2 = 0, hp = 0, px = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            lp += 0.45f * (nz[i % n] - lp); lp2 += 0.45f * (lp - lp2);
            hp = 0.97f * (hp + lp2 - px); px = lp2;
            float level = (float) (0.8 + 0.2 * Math.sin(TAU * p * 2 + 0.6));
            float chord = 0.16f * padNote(110, t, 0.1) + 0.12f * padNote(164.81, t, 0.5) + 0.1f * padNote(261.63, t, 0.9);
            out[i] = hp * 0.35f * level + chord + drops[i % n];
        }
    }

    /** A slow, soft low pulse every two seconds under a dark, slowly opening chord (D minor). */
    private static void slowPulse(float[] out, int n) {
        double[] ch = {146.83, 174.61, 220, 293.66};
        float lp = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE, p = ph(t);
            double beat = t % 2.0;
            // A soft, round thump that drops in pitch (its phase follows the falling pitch)
            double phase = 48 * beat + 40 * (1 - Math.exp(-beat * 18)) / 18;
            float thump = (float) (Math.sin(TAU * phase) * Math.exp(-beat * 7) * Math.min(1.0, beat / 0.004)) * 0.9f;
            float pad = 0;
            for (int k = 0; k < ch.length; k++) pad += padNote(ch[k], t, k * 0.21) * (0.3f - k * 0.04f);
            double cut = 300 + 900 * (0.5 - 0.5 * Math.cos(TAU * p));
            float a = (float) (1 - Math.exp(-TAU * cut / RATE));
            lp += a * (pad - lp);
            out[i] = thump + lp * 0.9f;
        }
    }

    /** A little original music-box tune (two phrases of eight seconds), over a soft low chord. */
    private static void musicBox(float[] out, int n) {
        // C major pentatonic, eighth notes at 120 bpm (0 = rest); 64 of them fill the loop exactly
        double C5 = 523.25, D5 = 587.33, E5 = 659.25, G5 = 783.99, A5 = 880, C6 = 1046.5, D6 = 1174.66, G4 = 392;
        double[] tune = {
            E5, G5, A5, G5, E5, D5, C5, 0, D5, E5, G5, E5, D5, C5, D5, 0,
            E5, G5, C6, A5, G5, E5, G5, 0, A5, G5, E5, D5, C5, D5, C5, 0,
            G5, A5, C6, D6, C6, A5, G5, 0, E5, G5, A5, G5, E5, D5, E5, 0,
            C6, A5, G5, E5, D5, E5, G5, 0, E5, D5, C5, D5, G4, C5, 0, 0
        };
        double step = (double) LOOP_S / tune.length;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            float v = 0;
            // This note and the few before it, still ringing (counted straight through, so it carries over the loop)
            long now = (long) Math.floor(t / step);
            for (int back = 0; back < 8; back++) {
                long k = now - back;
                if (k < 0) break;
                double f = tune[(int) (k % tune.length)];
                if (f == 0) continue;
                double tt = t - k * step;
                double env = Math.exp(-tt * 3.2) * Math.min(1.0, tt / 0.002);
                v += (float) ((sn(f * tt) + 0.3 * sn(2 * f * tt) * Math.exp(-tt * 4) + 0.12 * sn(3.01 * f * tt) * Math.exp(-tt * 8)) * env) * 0.3f;
            }
            float bed = 0.12f * padNote(130.81, t, 0.3) + 0.08f * padNote(196, t, 0.7);
            out[i] = v + bed;
        }
    }

    // ------------------------------------------------------------------ shaping

    /**
     * A small, dark-sounding room: four echoing delays (each softening as it repeats) and two diffusers, mixed in
     * with the dry sound. [fb] how long it rings, [damp] how quickly the echoes lose their brightness.
     */
    private static float[] space(float[] x, float fb, float damp, float mix) {
        float[] wet = new float[x.length];
        for (float ms : new float[]{29.7f, 37.1f, 41.1f, 43.7f}) {
            float[] buf = new float[(int) (RATE * ms / 1000f)];
            int idx = 0; float lp = 0;
            for (int i = 0; i < x.length; i++) {
                float o = buf[idx]; lp = o * (1f - damp) + lp * damp;
                buf[idx] = x[i] + lp * fb; idx = (idx + 1) % buf.length;
                wet[i] += o / 4f;
            }
        }
        for (float ms : new float[]{5.0f, 1.7f}) {
            float[] buf = new float[(int) (RATE * ms / 1000f)];
            int idx = 0;
            for (int i = 0; i < wet.length; i++) {
                float b = buf[idx]; float v = wet[i] + b * 0.5f;
                wet[i] = b - v * 0.5f; buf[idx] = v; idx = (idx + 1) % buf.length;
            }
        }
        float[] y = new float[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * (1f - mix * 0.5f) + wet[i] * mix * 2.2f;
        return y;
    }

    /**
     * Fades the very end out over [fadeS] and sets how loud it sounds: its loudest twentieth of a second is brought to
     * [loud] (so every choice sounds about as loud as the others), never letting a peak go past 0.7. Then 16-bit.
     */
    private static short[] finish(float[] y, float len, float fadeS, float loud) {
        float mx = 1e-6f;
        for (int i = 0; i < y.length; i++) {
            float t = (float) i / RATE;
            y[i] *= Math.max(0f, Math.min(1f, (len - t) / fadeS));
            mx = Math.max(mx, Math.abs(y[i]));
        }
        int w = Math.min(y.length, RATE / 20);
        double best = 1e-9;
        for (int st = 0; st + w <= y.length; st += Math.max(1, w / 4)) {
            double sum = 0;
            for (int i = st; i < st + w; i++) sum += y[i] * (double) y[i];
            best = Math.max(best, Math.sqrt(sum / w));
        }
        float k = (float) Math.min(loud / best, 0.7 / mx);
        short[] s = new short[y.length];
        for (int i = 0; i < y.length; i++) s[i] = (short) Math.max(-32767, Math.min(32767, Math.round(y[i] * k * 32767)));
        return s;
    }
}
