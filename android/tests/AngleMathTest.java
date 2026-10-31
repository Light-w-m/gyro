package cn.local.axisview;

import java.util.Random;

public class AngleMathTest {
    private static float[] matrix(double alpha, double beta, double gamma) {
        double a = Math.toRadians(alpha), b = Math.toRadians(beta), g = Math.toRadians(gamma);
        double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cg = Math.cos(g), sg = Math.sin(g);
        return new float[] {
            (float)(ca*cg-sa*sb*sg), (float)(-sa*cb), (float)(ca*sg+sa*sb*cg),
            (float)(sa*cg+ca*sb*sg), (float)(ca*cb), (float)(sa*sg-ca*sb*cg),
            (float)(-cb*sg), (float)sb, (float)(cb*cg)
        };
    }
    private static void check(double alpha, double beta, double gamma) {
        float[] original = matrix(alpha, beta, gamma);
        double[] xyz = new double[3];
        AngleMath.fromMatrix(original, xyz);
        if (xyz[0] < -180 || xyz[0] > 180 || xyz[1] < -90.00001 || xyz[1] > 90.00001 || xyz[2] < 0 || xyz[2] >= 360)
            throw new AssertionError("Angle out of range");
        float[] rebuilt = matrix(xyz[2], xyz[0], xyz[1]);
        for (int i = 0; i < 9; i++) if (Math.abs(original[i] - rebuilt[i]) > 2e-5)
            throw new AssertionError("Matrix roundtrip failed at alpha="+alpha+", beta="+beta+", gamma="+gamma);
    }
    public static void main(String[] args) {
        for (double a : new double[] {0, 1, 90, 180, 270, 359.9})
            for (double b : new double[] {-180, -135, -90, -45, 0, 45, 90, 135, 179.9})
                for (double g : new double[] {-90, -45, 0, 45, 90}) check(a, b, g);
        Random random = new Random(42);
        for (int i = 0; i < 10000; i++) check(random.nextDouble()*360, random.nextDouble()*360-180, random.nextDouble()*180-90);
        if (!"0.0".equals(AngleMath.format(-0.0001, 0))) throw new AssertionError("Negative zero");
        if (!"0.0".equals(AngleMath.format(359.99, 2))) throw new AssertionError("Z wrap");
        if (!"—.—".equals(AngleMath.format(Double.NaN, 0))) throw new AssertionError("Missing data");
        System.out.println("PASS: 10,270 matrix roundtrips; axis ranges, singularities, negative zero, Z wrap and missing readings.");
    }
}
