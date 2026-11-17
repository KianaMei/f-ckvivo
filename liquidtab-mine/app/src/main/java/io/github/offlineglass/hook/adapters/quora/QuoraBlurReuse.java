package io.github.offlineglass.hook.adapters.quora;

/** Last recorded input, not a pixel cache. Pending is captured BEFORE recording. */
public final class QuoraBlurReuse {
    private static final class Input {
        Object bitmap, effect;
        int generation, width, height;
        long revision;
        boolean valid;
        void clear() { bitmap=null; effect=null; valid=false; }
        void copyFrom(Input other) {
            bitmap=other.bitmap; effect=other.effect; generation=other.generation;
            width=other.width; height=other.height; revision=other.revision; valid=other.valid;
        }
    }
    private final Input pending=new Input(), recorded=new Input();
    public boolean needsRecord(boolean prepared, Object bitmap, int generation, long revision,
                               int width, int height, Object effect) {
        pending.bitmap=bitmap; pending.generation=generation; pending.revision=revision;
        pending.width=width; pending.height=height; pending.effect=effect; pending.valid=true;
        return !prepared || !recorded.valid || recorded.bitmap!=bitmap || recorded.effect!=effect ||
            recorded.generation!=generation || recorded.revision!=revision ||
            recorded.width!=width || recorded.height!=height;
    }
    public void recorded() { recorded.copyFrom(pending); }
    public void clear() { pending.clear(); recorded.clear(); }
}
