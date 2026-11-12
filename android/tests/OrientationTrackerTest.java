package cn.local.axisview;

import java.util.Random;

/** Analytic sensor replays: commanded motion is independent of the fused yaw. */
public final class OrientationTrackerTest {
    private static final long START = 1_000_000_000L, STEP = 10_000_000L;
    private static int checks;
    private static float[] orientation(double yaw, double pitch, double roll) {
        double a = Math.toRadians(yaw)/2, b = Math.toRadians(pitch)/2, g = Math.toRadians(roll)/2;
        double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
        return new float[] {(float)(ca*sb*cg-sa*cb*sg), (float)(ca*cb*sg+sa*sb*cg),
            (float)(sa*cb*cg+ca*sb*sg), (float)(ca*cb*cg-sa*sb*sg)};
    }
    private static float[] rate(double speed, double pitch, double roll, double bias) {
        double b = Math.toRadians(pitch), g = Math.toRadians(roll), s = Math.toRadians(speed);
        // R^T * world-Z angular velocity, plus a known physical gyro bias.
        return new float[] {(float)(-Math.cos(b)*Math.sin(g)*s), (float)(Math.sin(b)*s),
            (float)(Math.cos(b)*Math.cos(g)*s+bias), 0, 0, (float)bias};
    }
    private static OrientationTracker tracker(double yaw, double pitch, double roll) {
        OrientationTracker t = new OrientationTracker();
        t.reset(true);
        require(t.updateRotationVector(orientation(yaw, pitch, roll), START), "initial quaternion");
        return t;
    }
    private static double[] angles(OrientationTracker t) { double[] a = new double[3]; t.readAngles(a); return a; }
    private static void require(boolean result, String message) {
        checks++;
        if (!result) throw new AssertionError(message);
    }
    private static void close(double actual, double expected, double tolerance, String message) {
        require(Double.isFinite(actual) && Math.abs(actual-expected) <= tolerance,
            message+": expected "+expected+", got "+actual);
    }
    private static void heading(OrientationTracker t, double expected, double tolerance, String message) {
        double error = AngleMath.normalize360(angles(t)[2]-expected+180)-180;
        close(error, 0, tolerance, message);
    }
    private static void slowTurns() {
        for (double speed : new double[] {.01, .05, .2, 1, -.01, -.2, -1}) {
            for (double[] tilt : new double[][] {{0,0}, {60,30}, {-70,-25}, {120,20}, {0,85}, {89,0}, {90,0}}) {
                OrientationTracker t = tracker(123, tilt[0], tilt[1]);
                float[] raw = rate(speed, tilt[0], tilt[1], .003);
                t.updateGyroscope(raw, START, true);
                for (int i = 1; i <= 6000; i++) {
                    // Simulate a vendor bias estimator wrongly absorbing all slow yaw.
                    raw[5] = (float)(.003 + Math.toRadians(speed));
                    t.updateGyroscope(raw, START+i*STEP, true);
                    // The fused yaw stalls, then jumps. Neither should affect our yaw.
                    if (i % 2 == 0) t.updateRotationVector(orientation(i < 3000 ? 123 : 271, tilt[0], tilt[1]), START+i*STEP);
                    if (i % 100 == 0) heading(t, speed*i*.01, .002, "slow yaw "+speed+" at tilt "+tilt[0]+","+tilt[1]);
                }
            }
        }
    }
    private static void variableTimingAndWrap() {
        OrientationTracker t = tracker(350, 0, 0);
        float[] raw = rate(.4, 0, 0, .002);
        t.updateGyroscope(raw, START, true);
        Random random = new Random(7);
        long now = START;
        for (int i = 0; i < 10000; i++) {
            now += 5_000_000L + random.nextInt(13_000_000);
            t.updateGyroscope(raw, now, true);
            if (i % 3 == 0) t.updateRotationVector(orientation(350,0,0), now);
        }
        heading(t, (now-START)*1e-9*.4, .0005, "irregular sensor timestamps");

        t = tracker(0,0,0);
        raw = rate(60,0,0,0);
        t.updateGyroscope(raw, START, true);
        double previous = 0;
        for (int i = 1; i <= 1800; i++) {
            t.updateGyroscope(raw, START+i*STEP, true);
            double current = angles(t)[2];
            close(AngleMath.normalize360(current-previous+180)-180, .6, .0001, "continuous multi-turn wrap");
            previous = current;
        }
        heading(t, 0, .0001, "three complete revolutions");

        // A smooth reversal has zero net turn; endpoint rates are both zero.
        t = tracker(0,0,0);
        t.updateGyroscope(rate(0,0,0,0), START, true);
        for (int i = 1; i <= 4000; i++) {
            double speed = .2*Math.sin(2*Math.PI*i/4000);
            t.updateGyroscope(rate(speed,0,0,0), START+i*STEP, true);
        }
        heading(t, 0, .0001, "slow reversal");
    }
    private static void invalidPacketsAndGaps() {
        OrientationTracker t = tracker(0,0,0);
        float[] raw = rate(1,0,0,0);
        require(!t.updateGyroscope(raw, START-STEP, true), "gyro backlog predating orientation seed ignored");
        t.updateGyroscope(raw, START, true);
        t.updateGyroscope(raw, START+STEP, true);
        require(!t.updateGyroscope(raw, START+STEP, true), "duplicate timestamp ignored");
        require(!t.updateGyroscope(raw, START, true), "out of order timestamp ignored");
        require(!t.updateGyroscope(new float[5], START+2*STEP, true), "short uncalibrated packet ignored");
        for (float bad : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            require(!t.updateGyroscope(new float[] {0,0,bad,0,0,0}, START+2*STEP, true), "bad rate ignored");
            require(!t.updateGyroscope(new float[] {0,0,0,0,0,bad}, START+2*STEP, true), "bad bias ignored");
            require(!t.updateRotationVector(new float[] {0,0,0,bad}, START+2*STEP), "bad quaternion ignored");
        }
        require(!t.updateRotationVector(new float[] {0,0,0,0}, START+2*STEP), "zero quaternion rejected");
        require(!t.updateRotationVector(new float[] {2,2,2}, START+2*STEP), "invalid three-component vector");
        t.updateGyroscope(raw, START+STEP*2, true);
        heading(t, .02, .00001, "bad packets do not corrupt time or attitude");
        t.updateGyroscope(raw, START+10_000_000_000L, true);
        heading(t, .02, .00001, "no extrapolation across a long gap");
        t.updateGyroscope(raw, START+10_000_000_000L+STEP, true);
        heading(t, .03, .00001, "resumes after gap");
        t.reset(true);
        require(Double.isNaN(angles(t)[2]), "resume waits for fresh orientation");
        t.updateRotationVector(orientation(100,0,0), START+20_000_000_000L);
        t.updateGyroscope(raw, START+20_000_000_000L, true);
        heading(t, 0, .00001, "resume starts at zero without integrating background time");
    }
    private static void calibrationAndFallback() {
        OrientationTracker t = tracker(0,0,0);
        float[] raw = new float[] {.001f,-.002f,.003f,0,0,0};
        t.updateGyroscope(raw, START, true);
        require(t.startCalibration(), "explicit calibration starts");
        for (int i = 1; i <= 205; i++) t.updateGyroscope(raw, START+i*STEP, true);
        require(t.calibrationState() == OrientationTracker.CALIBRATION_DONE, "stationary calibration completes");
        heading(t, 0, .00001, "calibration zeros heading");
        for (int i = 206; i <= 6205; i++) t.updateGyroscope(raw, START+i*STEP, true);
        heading(t, 0, .00001, "stationary residual bias removed for one minute");
        raw[2] += (float)Math.toRadians(.02);
        // One trapezoid interval at the transition contributes half a sample.
        for (int i = 6206; i <= 12205; i++) t.updateGyroscope(raw, START+i*STEP, true);
        heading(t, 1.1999, .0001, "calibration does not swallow subsequent slow turns");
        require(t.startCalibration(), "second calibration starts");
        t.updateGyroscope(rate(20,0,0,0), START+12206*STEP, true);
        require(t.calibrationState() == OrientationTracker.CALIBRATION_MOVED, "moving calibration rejected");

        t = tracker(0,0,0);
        t.updateGyroscope(new float[6],START,true);
        t.startCalibration();
        for (int i=1;i<=205;i++) {
            // Below the gross-motion limit, but too noisy to be a stationary zero.
            t.updateGyroscope(rate(i%2==0 ? .5 : -.5,0,0,0),START+i*STEP,true);
        }
        require(t.calibrationState() == OrientationTracker.CALIBRATION_MOVED,"noisy calibration rejected");
        t.startCalibration();
        t.updateGyroscope(new float[6],START+10_000_000_000L,true);
        require(t.calibrationState() == OrientationTracker.CALIBRATION_MOVED,"calibration with missing data rejected");

        t = tracker(0,0,0);
        raw = rate(1,0,0,.003);
        t.updateGyroscope(raw,START,true);
        for (int i=1; i<=1000; i++) t.updateGyroscope(raw,START+i*STEP,true);
        heading(t,10,.0001,"before gyro source change");
        t.restartGyroscope();
        t.updateGyroscope(new float[] {0,0,(float)Math.toRadians(1)},START+1001*STEP,false);
        t.updateGyroscope(new float[] {0,0,(float)Math.toRadians(1)},START+1002*STEP,false);
        heading(t,10.01,.0001,"source change clears old raw bias");
        t.useRotationVectorOnly();
        t.updateRotationVector(orientation(240,0,0),START+1003*STEP);
        heading(t,10.01,.0001,"fallback preserves heading");
        t.updateRotationVector(orientation(241,0,0),START+1004*STEP);
        heading(t,11.01,.0001,"fallback follows relative vector");
        require(!t.startCalibration(),"no gyro cannot calibrate");
        t.reset(false);
        t.updateRotationVector(orientation(350,0,0),START);
        t.updateRotationVector(orientation(10,0,0),START+STEP);
        heading(t,20,.0001,"rotation-vector-only start and wrap");
    }
    private static void tiltAndQuaternionVariants() {
        OrientationTracker t = tracker(0,0,0);
        float[] still = new float[6];
        t.updateGyroscope(still,START,true);
        for (int i=1; i<=800; i++) {
            t.updateGyroscope(still, START+i*STEP, true);
            t.updateRotationVector(orientation(170,20,0), START+i*STEP);
        }
        close(angles(t)[0],20,.002,"gravity tilt correction converges");
        heading(t,0,.00001,"fused yaw not injected with tilt correction");
        float[] v = orientation(120,30,-20);
        t = tracker(120,30,-20);
        t.updateGyroscope(still,START,true);
        for (int i=1;i<=100;i++) {
            float[] sign = new float[4];
            for (int j=0;j<4;j++) sign[j] = v[j]*(i%2==0 ? -1 : 1)*1.001f;
            require(t.updateRotationVector(sign,START+i*STEP),"normalized q / -q accepted");
        }
        heading(t,0,.00001,"quaternion hemisphere changes cannot flip yaw");
        close(angles(t)[0],30,.0001,"tilt preserved");
        close(angles(t)[1],-20,.0001,"roll preserved");
        t.reset(false);
        require(t.updateRotationVector(new float[] {0,0,(float)Math.sin(Math.toRadians(20))},START),"three-value vector supported");
        heading(t,0,.0001,"three-value seed");
    }
    public static void main(String[] args) {
        slowTurns(); variableTimingAndWrap(); invalidPacketsAndGaps(); calibrationAndFallback(); tiltAndQuaternionVariants();
        System.out.println("PASS: "+checks+" assertions; slow yaw 0.01-1 deg/s, vendor bias changes/frozen yaw, 7 tilts, timestamp jitter, wrap/reversal, gaps, invalid packets, calibration, source fallback and quaternion variants.");
    }
}
