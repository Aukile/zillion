import net.ankrya.zillion.client.fx.FxMath;

public final class ChoreographyTest {
    public static void main(String[] args) {
        check(FxMath.ramp(0, 0, 10) == 0, "ramp starts at zero");
        check(FxMath.ramp(10, 0, 10) == 1, "ramp ends at one");
        check(Math.abs(FxMath.ramp(5, 0, 10) - .5f) < .001, "symmetric ramp");
        long seed = 0x12345678;
        for (int frame = 0; frame < 160; frame++) {
            double previous = -100;
            for (int i = 0; i < 5; i++) {
                double a = FxMath.orbitAngle(i, frame / 20.0, seed);
                check(a != previous, "orbit sectors must be distinct");
                previous = a;
                check(Double.isFinite(FxMath.orbitAngle(i, frame / 20.0, seed)), "finite orbit angle");
                check(FxMath.docking(frame, i) >= 0 && FxMath.docking(frame, i) <= 1, "bounded docking");
            }
        }
        check(FxMath.docking(103, 0) == 0, "first docking starts after the vortex");
        check(FxMath.docking(138, 4) == 1, "last docking finishes at the complete frame");
        check(FxMath.random(seed, 1) == FxMath.random(seed, 1), "random walk is deterministic");
        System.out.println("ChoreographyTest: all deterministic path checks passed.");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
