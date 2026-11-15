package net.bonjiminium.phonemic;

/** Display-only RMS scale: -60 dBFS is empty, 0 dBFS is full. Never alters audio. */
public final class AudioLevel {
    public static float fromRms(double rms) {
        if (!(rms > 0) || !Double.isFinite(rms)) return 0;
        return (float) Math.max(0, Math.min(1, (20 * Math.log10(rms) + 60) / 60));
    }
    private AudioLevel() {}
}
