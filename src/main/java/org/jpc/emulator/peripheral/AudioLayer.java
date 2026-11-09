package org.jpc.emulator.peripheral;

/** Sound output is not supported in the JPC mod: this keeps the emulator code compiling without javax.sound. */
public class AudioLayer {
    public static boolean open(int bufferSize, int freq) {
        return false;
    }

    public static void stop() {
    }
}
