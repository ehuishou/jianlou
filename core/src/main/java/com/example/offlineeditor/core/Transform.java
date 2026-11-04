package com.example.offlineeditor.core;

/** Center-origin transform; translation is a fraction of output canvas size. */
public final class Transform {
    public final double scale, rotationDegrees, x, y;
    public Transform(double scale, double rotationDegrees, double x, double y) {
        if (!Double.isFinite(scale) || scale <= 0 || scale > 20 || !Double.isFinite(rotationDegrees) ||
            !Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Invalid transform");
        this.scale = scale; this.rotationDegrees = rotationDegrees % 360; this.x = x; this.y = y;
    }
    public static Transform identity() { return new Transform(1,0,0,0); }
    public double[] map(double px, double py, double width, double height) {
        if (!(width > 0 && height > 0) || !Double.isFinite(width) || !Double.isFinite(height))
            throw new IllegalArgumentException("Invalid canvas");
        double radians = Math.toRadians(rotationDegrees), c = Math.cos(radians), s = Math.sin(radians);
        return new double[]{scale*(c*px-s*py)+x*width, scale*(s*px+c*py)+y*height};
    }
}
