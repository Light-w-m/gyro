package cn.local.axisview;

/** Device axes stay fixed to the phone's natural orientation when the UI rotates. */
public final class AngleMath {
    private AngleMath() {}

    /** Rotation matrix -> intrinsic Z-X-Y angles, matching web beta/gamma/alpha. */
    public static void fromMatrix(float[] r, double[] xyz) {
        double cb = Math.hypot(r[6], r[8]);
        double alpha, beta, gamma;
        if (cb < 1e-6) {
            // Gimbal lock: choose gamma=0; the Z and Y rotations are not independent.
            beta = r[7] >= 0 ? Math.PI / 2 : -Math.PI / 2;
            alpha = Math.atan2(r[3], r[0]);
            gamma = 0;
        } else {
            beta = Math.atan2(r[7], cb);
            gamma = Math.atan2(-r[6], r[8]);
            if (gamma > Math.PI / 2 || gamma < -Math.PI / 2) {
                gamma += gamma > 0 ? -Math.PI : Math.PI;
                beta = beta >= 0 ? Math.PI - beta : -Math.PI - beta;
                alpha = Math.atan2(r[1], -r[4]);
            } else {
                alpha = Math.atan2(-r[1], r[4]);
            }
        }
        xyz[0] = Math.toDegrees(beta);
        xyz[1] = Math.toDegrees(gamma);
        xyz[2] = normalize360(Math.toDegrees(alpha));
    }

    public static double normalize360(double degrees) {
        return ((degrees % 360) + 360) % 360;
    }

    public static String format(double value, int axis) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "—.—";
        double rounded = Math.round(value * 10.0) / 10.0;
        if (axis == 2) rounded = normalize360(rounded);
        if (rounded == 0) rounded = 0; // Normalize negative zero.
        return String.format(java.util.Locale.US, "%.1f", rounded);
    }
}
