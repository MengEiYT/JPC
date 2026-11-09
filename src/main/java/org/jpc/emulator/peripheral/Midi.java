package org.jpc.emulator.peripheral;

/** MIDI is not supported in the JPC mod (no javax.sound.midi). Same entry points as the original, all no-ops. */
public class Midi {
    public static class Sysex {
    }

    public Sysex sysex = new Sysex();

    public static void MIDI_RawOutByte(int data) {
    }

    public static boolean MIDI_Available() {
        return false;
    }

    public Midi() {
    }

    public static void MIDI_Destroy() {
    }

    public static void MIDI_Init() {
    }
}
