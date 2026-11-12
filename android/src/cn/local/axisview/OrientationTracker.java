package cn.local.axisview;

/** Timestamp-driven, magnetometer-free relative orientation. No Android dependencies. */
public final class OrientationTracker {
    public static final int CALIBRATION_IDLE = 0, CALIBRATING = 1,
        CALIBRATION_DONE = 2, CALIBRATION_MOVED = 3;
    private static final double MAX_GAP_SECONDS = .25;
    private static final long CALIBRATION_NS = 2_000_000_000L;
    private final double[] q = new double[4]; // w, x, y, z; device -> world.
    private final double[] fused = new double[4];
    private final float[] matrix = new float[9];
    private final double[] scratchAngles = new double[3];
    private final double[] bias = new double[3], previousRate = new double[3];
    private final double[] mean = new double[3], m2 = new double[3];
    private boolean gyroEnabled, initialized, biasInitialized, fallbackPending;
    private long gyroTimestamp, rotationTimestamp, seedTimestamp, calibrationStart;
    private int calibrationState, calibrationSamples;
    private double fallbackOffset, fallbackBaseYaw;

    public void reset(boolean enableGyroscope) {
        gyroEnabled = enableGyroscope;
        initialized = biasInitialized = false;
        fallbackPending = true;
        fallbackOffset = fallbackBaseYaw = 0;
        gyroTimestamp = rotationTimestamp = seedTimestamp = 0;
        calibrationState = CALIBRATION_IDLE;
        q[0] = 1;
        for (int i = 0; i < 3; i++) { q[i + 1] = 0; bias[i] = previousRate[i] = 0; }
    }

    public boolean isReady() { return initialized; }
    public int calibrationState() { return calibrationState; }

    /** Switching from uncalibrated to calibrated rates must discard the old bias and dt. */
    public void restartGyroscope() {
        biasInitialized = false;
        gyroTimestamp = 0;
        if (calibrationState == CALIBRATING) calibrationState = CALIBRATION_MOVED;
    }

    /** A failed gyro stream can fall back without snapping the displayed heading. */
    public void useRotationVectorOnly() {
        if (!gyroEnabled) return;
        if (initialized) { readAngles(scratchAngles); fallbackBaseYaw = scratchAngles[2]; }
        gyroEnabled = false;
        fallbackPending = true;
        if (calibrationState == CALIBRATING) calibrationState = CALIBRATION_MOVED;
    }

    public boolean updateRotationVector(float[] values, long timestamp) {
        if (timestamp <= 0 || timestamp <= rotationTimestamp || !decodeQuaternion(values)) return false;
        if (gyroEnabled && gyroTimestamp > 0 && timestamp < gyroTimestamp - 250_000_000L) return false;
        double dt = rotationTimestamp == 0 ? 0 : (timestamp - rotationTimestamp) * 1e-9;
        rotationTimestamp = timestamp;
        if (!initialized || !gyroEnabled) {
            System.arraycopy(fused, 0, q, 0, 4);
            toMatrix(q, matrix);
            AngleMath.fromMatrix(matrix, scratchAngles);
            if (fallbackPending) {
                fallbackOffset = scratchAngles[2] - fallbackBaseYaw;
                fallbackPending = false;
            }
            rotateWorldYaw(Math.toRadians(-fallbackOffset));
            if (!initialized) { gyroTimestamp = 0; seedTimestamp = timestamp; }
            initialized = true;
        } else if (dt > 0) {
            // Keep only fused gravity. Its heading may be clamped/recalibrated by the HAL.
            // A correction perpendicular to gravity cannot inject that heading into q.
            correctTilt(1 - Math.exp(-Math.min(dt, MAX_GAP_SECONDS) / .5));
        }
        return true;
    }

    public boolean updateGyroscope(float[] values, long timestamp, boolean uncalibrated) {
        int required = uncalibrated ? 6 : 3;
        if (!gyroEnabled || timestamp <= 0 || timestamp <= gyroTimestamp || values.length < required) return false;
        if (initialized && timestamp < seedTimestamp) return false;
        for (int i = 0; i < required; i++) if (!finite(values[i])) return false;
        if (!biasInitialized) {
            // Snapshot the HAL's estimate once. Continuously following it can erase a
            // real, constant slow turn. Only an explicit stationary calibration changes it.
            for (int i = 0; i < 3; i++) bias[i] = uncalibrated ? values[i + 3] : 0;
            biasInitialized = true;
        }
        double dt = gyroTimestamp == 0 ? 0 : (timestamp - gyroTimestamp) * 1e-9;
        gyroTimestamp = timestamp;
        boolean calibratedNow = collectCalibration(values, timestamp, dt);
        double x = values[0] - bias[0], y = values[1] - bias[1], z = values[2] - bias[2];
        if (initialized && dt > 0 && dt <= MAX_GAP_SECONDS && !calibratedNow) {
            // Trapezoidal integration; there is deliberately no angular-speed dead band.
            rotateDevice((previousRate[0] + x) * .5, (previousRate[1] + y) * .5,
                (previousRate[2] + z) * .5, dt);
        }
        previousRate[0] = x; previousRate[1] = y; previousRate[2] = z;
        return true;
    }

    /** The user must hold the phone stationary; constant slow yaw is not observable as bias. */
    public boolean startCalibration() {
        if (!gyroEnabled || !initialized || gyroTimestamp == 0) return false;
        calibrationState = CALIBRATING;
        calibrationStart = 0;
        calibrationSamples = 0;
        for (int i = 0; i < 3; i++) mean[i] = m2[i] = 0;
        return true;
    }

    public void readAngles(double[] angles) {
        if (!initialized) { for (int i = 0; i < 3; i++) angles[i] = Double.NaN; return; }
        toMatrix(q, matrix);
        AngleMath.fromMatrix(matrix, angles);
    }

    private boolean collectCalibration(float[] values, long timestamp, double dt) {
        if (calibrationState != CALIBRATING) return false;
        double speed2 = 0;
        for (int i = 0; i < 3; i++) speed2 += Math.pow(values[i] - bias[i], 2);
        if (dt > MAX_GAP_SECONDS || speed2 > Math.pow(Math.toRadians(3), 2)) {
            calibrationState = CALIBRATION_MOVED;
            return false;
        }
        if (calibrationStart == 0) calibrationStart = timestamp;
        calibrationSamples++;
        for (int i = 0; i < 3; i++) {
            double delta = values[i] - mean[i];
            mean[i] += delta / calibrationSamples;
            m2[i] += delta * (values[i] - mean[i]);
        }
        if (timestamp - calibrationStart < CALIBRATION_NS) return false;
        if (calibrationSamples < 40) { calibrationState = CALIBRATION_MOVED; return false; }
        for (int i = 0; i < 3; i++) {
            if (m2[i] / (calibrationSamples - 1) > Math.pow(Math.toRadians(.2), 2)) {
                calibrationState = CALIBRATION_MOVED;
                return false;
            }
        }
        System.arraycopy(mean, 0, bias, 0, 3);
        readAngles(scratchAngles);
        rotateWorldYaw(Math.toRadians(-scratchAngles[2]));
        calibrationState = CALIBRATION_DONE;
        return true;
    }

    private boolean decodeQuaternion(float[] values) {
        if (values.length < 3) return false;
        for (int i = 0; i < Math.min(4, values.length); i++) if (!finite(values[i])) return false;
        double x = values[0], y = values[1], z = values[2];
        double vectorNorm2 = x*x + y*y + z*z;
        if (values.length == 3 && vectorNorm2 > 1.0001) return false;
        double w = values.length >= 4 ? values[3] : Math.sqrt(Math.max(0, 1 - vectorNorm2));
        double norm = Math.sqrt(w*w + vectorNorm2);
        if (norm < .5 || norm > 1.5) return false;
        fused[0] = w / norm; fused[1] = x / norm; fused[2] = y / norm; fused[3] = z / norm;
        return true;
    }

    private void correctTilt(double weight) {
        toMatrix(fused, matrix);
        double mx = matrix[6], my = matrix[7], mz = matrix[8];
        toMatrix(q, matrix);
        double px = matrix[6], py = matrix[7], pz = matrix[8];
        double x = my*pz - mz*py, y = mz*px - mx*pz, z = mx*py - my*px;
        double length = Math.sqrt(x*x + y*y + z*z);
        double sine = length;
        double dot = Math.max(-1, Math.min(1, mx*px + my*py + mz*pz));
        if (length < 1e-8) {
            if (dot >= 0) return;
            // Antiparallel gravity: choose a deterministic axis perpendicular to it.
            if (Math.abs(px) < .9) { x = 0; y = pz; z = -py; }
            else { x = -pz; y = 0; z = px; }
            length = Math.sqrt(x*x + y*y + z*z);
        }
        // The cross product retains small-angle precision when the float dot rounds to 1.
        double angle = Math.atan2(sine, dot) * weight;
        rotateDevice(x / length, y / length, z / length, angle);
    }

    private void rotateDevice(double x, double y, double z, double dt) {
        double speed = Math.sqrt(x*x + y*y + z*z);
        if (speed == 0) return;
        double half = speed * dt * .5, scale = Math.sin(half) / speed;
        double dw = Math.cos(half), dx = x*scale, dy = y*scale, dz = z*scale;
        double w = q[0], qx = q[1], qy = q[2], qz = q[3];
        q[0] = w*dw - qx*dx - qy*dy - qz*dz;
        q[1] = w*dx + qx*dw + qy*dz - qz*dy;
        q[2] = w*dy - qx*dz + qy*dw + qz*dx;
        q[3] = w*dz + qx*dy - qy*dx + qz*dw;
        double norm = Math.sqrt(q[0]*q[0] + q[1]*q[1] + q[2]*q[2] + q[3]*q[3]);
        for (int i = 0; i < 4; i++) q[i] /= norm;
    }

    private void rotateWorldYaw(double radians) {
        double c = Math.cos(radians / 2), s = Math.sin(radians / 2);
        double w = q[0], x = q[1], y = q[2], z = q[3];
        q[0] = c*w - s*z; q[1] = c*x - s*y; q[2] = c*y + s*x; q[3] = c*z + s*w;
    }

    private static void toMatrix(double[] q, float[] r) {
        double w = q[0], x = q[1], y = q[2], z = q[3];
        r[0] = (float)(1 - 2*(y*y + z*z)); r[1] = (float)(2*(x*y - z*w)); r[2] = (float)(2*(x*z + y*w));
        r[3] = (float)(2*(x*y + z*w)); r[4] = (float)(1 - 2*(x*x + z*z)); r[5] = (float)(2*(y*z - x*w));
        r[6] = (float)(2*(x*z - y*w)); r[7] = (float)(2*(y*z + x*w)); r[8] = (float)(1 - 2*(x*x + y*y));
    }

    private static boolean finite(double value) { return !Double.isNaN(value) && !Double.isInfinite(value); }
}
