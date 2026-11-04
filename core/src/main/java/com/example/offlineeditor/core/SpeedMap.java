package com.example.offlineeditor.core;

import java.util.Arrays;

/** Source-time speed curve. Integrate dt = ds/v(s); units are microseconds. */
public final class SpeedMap {
    private final double[] positions;
    private final double[] rates;
    public SpeedMap(double[] positions, double[] rates) {
        if (positions == null || rates == null || positions.length != rates.length || positions.length < 2)
            throw new IllegalArgumentException("At least two matching speed points required");
        this.positions = positions.clone(); this.rates = rates.clone();
        if (positions[0] != 0 || positions[positions.length - 1] != 1)
            throw new IllegalArgumentException("Curve endpoints must be 0 and 1");
        for (int i = 0; i < positions.length; i++) {
            if (!Double.isFinite(positions[i]) || positions[i] < 0 || positions[i] > 1 ||
                (i > 0 && positions[i] <= positions[i - 1]) ||
                !Double.isFinite(rates[i]) || rates[i] < 0.1 || rates[i] > 16)
                throw new IllegalArgumentException("Invalid position or speed (0.1..16)");
        }
    }
    public static SpeedMap constant(double rate) { return new SpeedMap(new double[]{0,1}, new double[]{rate,rate}); }
    public double[] positions() { return positions.clone(); }
    public double[] rates() { return rates.clone(); }
    public boolean isConstant() { for (double r : rates) if (r != rates[0]) return false; return true; }
    public double rateAt(double x) {
        checkUnit(x);
        int i = 0; while (i < positions.length - 2 && x > positions[i + 1]) i++;
        double f = (x - positions[i]) / (positions[i + 1] - positions[i]);
        return rates[i] + f * (rates[i + 1] - rates[i]);
    }
    private static void checkUnit(double x) {
        if (!Double.isFinite(x) || x < 0 || x > 1) throw new IllegalArgumentException("Position outside 0..1");
    }
    // Piecewise linear v(s), NOT linear progress on the output timeline.
    private double integral(double x) {
        checkUnit(x); double sum = 0;
        for (int i = 0; i < positions.length - 1 && x > positions[i]; i++) {
            double dx = Math.min(x, positions[i + 1]) - positions[i];
            double slope = (rates[i + 1] - rates[i]) / (positions[i + 1] - positions[i]);
            sum += Math.abs(slope) < 1e-12 ? dx / rates[i] : Math.log1p(slope * dx / rates[i]) / slope;
        }
        return sum;
    }
    public long durationUs(long sourceDurationUs) {
        if (sourceDurationUs <= 0 || sourceDurationUs > 86_400_000_000L) throw new IllegalArgumentException("Duration must be within 24 hours");
        return Math.max(1L, Math.round(sourceDurationUs * integral(1)));
    }
    public double sourceFraction(long outputUs, long sourceDurationUs, boolean reverse) {
        long total = durationUs(sourceDurationUs);
        if (outputUs < 0 || outputUs > total) throw new IllegalArgumentException("Seek outside clip");
        if (outputUs == 0) return reverse ? 1 : 0;
        if (outputUs == total) return reverse ? 0 : 1;
        double target = outputUs / (double) sourceDurationUs;
        if (reverse) target = integral(1) - target;
        double lo = 0, hi = 1;
        for (int i = 0; i < 55; i++) {
            double mid = (lo + hi) / 2;
            if (integral(mid) < target) lo = mid; else hi = mid;
        }
        return (lo + hi) / 2;
    }
}
