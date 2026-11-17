package io.github.offlineglass.rendering;
/** Dimensionless velocity deformation shared by injected and manager bars. */
public final class BottomBarDynamics {
    private BottomBarDynamics() {}
    public static float stretchX(float velocity) {
        return 1f / (1f - Math.max(-.2f, Math.min(.2f, velocity / 10f * .75f)));
    }
    public static float stretchY(float velocity) {
        return 1f - Math.max(-.2f, Math.min(.2f, velocity / 10f * .25f));
    }
}
