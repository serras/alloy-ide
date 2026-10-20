package edu.mit.csail.sdg.alloy4viz;

/**
 * The Alloy visualizer's automatic theme, for the IDE's instance viewer. {@code MagicLayout} and
 * {@code MagicColor} are package-private, so this bridge lives in their package; the plugin classloader is not
 * modular, so the split package is allowed.
 */
public final class AlloyIdeMagic {
    private AlloyIdeMagic() {}

    /** Infers node visibility, shapes, colors, edge labels, attributes and a default projection. */
    public static void magic(VizState state) {
        MagicLayout.magic(state);
        MagicColor.magic(state);
    }
}
