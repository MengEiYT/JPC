/*
    JPC mod addition (not in the original JPC): complete FNSTENV / FLDENV / FNSAVE / FRSTOR.

    The stock JPC versions only stored the three control words (and did not touch the register stack),
    so any OS that saves the x87 state with FNSAVE on a context switch lost its floating point registers.

    Written for the JPC mod (GPL-2.0, like the rest of this tree). Not part of the original JPC.
*/

package org.jpc.emulator.execution.sse;

import org.jpc.emulator.execution.decoder.Pointer;
import org.jpc.emulator.processor.Processor;
import org.jpc.emulator.processor.fpu64.FpuState;
import org.jpc.emulator.processor.fpu64.FpuState64;

public final class X87Env
{
    private X87Env() {}

    /** The 80-bit image of physical register p. MMX state is shown the way the real chip shows it. */
    private static byte[] image(Processor cpu, FpuState fpu, int p, double[] stByPhys)
    {
        byte[] e;
        if (cpu.mmxActive)
        {
            e = new byte[10];
            long v = cpu.mm[p];
            for (int j = 0; j < 8; j++)
                e[j] = (byte) (v >>> (8 * j));
            e[8] = (byte) 0xFF;
            e[9] = (byte) 0xFF;
        }
        else
            e = FpuState64.doubleToExtended(stByPhys[p], false);
        return e;
    }

    /** Values indexed by physical register number. */
    private static double[] byPhysical(FpuState fpu)
    {
        double[] st = fpu.getStack();
        double[] phys = new double[8];
        for (int i = 0; i < 8; i++)
            phys[(fpu.top + i) & 7] = st[i];
        return phys;
    }

    private static int classify(byte[] e)
    {
        int exp = (e[8] & 0xFF) | ((e[9] & 0x7F) << 8);
        boolean integer = (e[7] & 0x80) != 0;
        boolean zeroMantissa = true;
        for (int j = 0; j < 8; j++)
            if (e[j] != 0)
                zeroMantissa = false;
        if (exp == 0x7FFF)
            return 2;
        if (exp == 0)
            return zeroMantissa ? 1 : 2;
        return integer ? 0 : 2;
    }

    /** The 16-bit tag word as FNSTENV reports it: empty from the stack bookkeeping, the rest from register contents. */
    private static int fullTagWord(Processor cpu, FpuState fpu, double[] phys)
    {
        int tw = fpu.getTagWord();
        int w = 0;
        for (int p = 0; p < 8; p++)
        {
            int t = (tw >> (2 * p)) & 3;
            if (t != 3)
                t = classify(image(cpu, fpu, p, phys));
            w |= t << (2 * p);
        }
        return w;
    }

    private static void writeEnv(Processor cpu, Pointer p, boolean o32, double[] phys)
    {
        FpuState fpu = cpu.fpu;
        int fcw = fpu.getControl() & 0xFFFF;
        int fsw = fpu.getStatus() & 0xFFFF;
        int ftw = fullTagWord(cpu, fpu, phys);
        if (o32)
        {
            p.set32(cpu, 0, fcw);
            p.set32(cpu, 4, fsw);
            p.set32(cpu, 8, ftw);
            p.set32(cpu, 12, (int) fpu.lastIP);
            p.set32(cpu, 16, 0);
            p.set32(cpu, 20, (int) fpu.lastData);
            p.set32(cpu, 24, 0);
        }
        else
        {
            p.set16(cpu, 0, (short) fcw);
            p.set16(cpu, 2, (short) fsw);
            p.set16(cpu, 4, (short) ftw);
            p.set16(cpu, 6, (short) fpu.lastIP);
            p.set16(cpu, 8, (short) 0);
            p.set16(cpu, 10, (short) fpu.lastData);
            p.set16(cpu, 12, (short) 0);
        }
    }

    private static int envSize(boolean o32)
    {
        return o32 ? 28 : 14;
    }

    public static void fnstenv(Processor cpu, Pointer p, boolean o32)
    {
        double[] phys = byPhysical(cpu.fpu);
        writeEnv(cpu, p, o32, phys);
        // FNSTENV leaves every floating point exception masked
        cpu.fpu.setControl(cpu.fpu.getControl() | 0x3F);
    }

    public static void fldenv(Processor cpu, Pointer p, boolean o32)
    {
        FpuState fpu = cpu.fpu;
        int fcw, fsw, ftw;
        if (o32)
        {
            fcw = p.get32(cpu, 0) & 0xFFFF;
            fsw = p.get32(cpu, 4) & 0xFFFF;
            ftw = p.get32(cpu, 8) & 0xFFFF;
        }
        else
        {
            fcw = p.get16(cpu, 0) & 0xFFFF;
            fsw = p.get16(cpu, 2) & 0xFFFF;
            ftw = p.get16(cpu, 4) & 0xFFFF;
        }
        fpu.setControl(fcw);
        fpu.setStatus(fsw);
        fpu.setTagWord(ftw);
    }

    public static void fnsave(Processor cpu, Pointer p, boolean o32)
    {
        FpuState fpu = cpu.fpu;
        double[] phys = byPhysical(fpu);
        writeEnv(cpu, p, o32, phys);
        int base = envSize(o32);
        for (int i = 0; i < 8; i++)
        {
            byte[] e = image(cpu, fpu, (fpu.top + i) & 7, phys);
            long lo = 0;
            for (int j = 7; j >= 0; j--)
                lo = (lo << 8) | (e[j] & 0xFF);
            p.set64(cpu, base + 10 * i, lo);
            p.set16(cpu, base + 10 * i + 8, (short) ((e[8] & 0xFF) | ((e[9] & 0xFF) << 8)));
        }
        // FNSAVE finishes with FNINIT
        fpu.init();
        cpu.mmxActive = false;
    }

    public static void frstor(Processor cpu, Pointer p, boolean o32)
    {
        FpuState fpu = cpu.fpu;
        int base = envSize(o32);
        int fcw, fsw, ftw;
        if (o32)
        {
            fcw = p.get32(cpu, 0) & 0xFFFF;
            fsw = p.get32(cpu, 4) & 0xFFFF;
            ftw = p.get32(cpu, 8) & 0xFFFF;
        }
        else
        {
            fcw = p.get16(cpu, 0) & 0xFFFF;
            fsw = p.get16(cpu, 2) & 0xFFFF;
            ftw = p.get16(cpu, 4) & 0xFFFF;
        }
        long[] lo = new long[8];
        int[] hi = new int[8];
        for (int i = 0; i < 8; i++)
        {
            lo[i] = p.get64(cpu, base + 10 * i);
            hi[i] = p.get16(cpu, base + 10 * i + 8) & 0xFFFF;
        }

        fpu.setControl(fcw);
        fpu.setStatus(fsw);

        boolean mmxState = true;
        for (int i = 0; i < 8; i++)
            if (hi[i] != 0xFFFF || ((ftw >> (2 * i)) & 3) == 3)
                mmxState = false;

        if (mmxState)
        {
            for (int i = 0; i < 8; i++)
                cpu.mm[(fpu.top + i) & 7] = lo[i];
            cpu.mmxActive = true;
            fpu.setTagWord(0);
            return;
        }

        cpu.mmxActive = false;
        for (int i = 0; i < 8; i++)
        {
            byte[] e = new byte[10];
            for (int j = 0; j < 8; j++)
                e[j] = (byte) (lo[i] >>> (8 * j));
            e[8] = (byte) hi[i];
            e[9] = (byte) (hi[i] >>> 8);
            fpu.setST(i, FpuState64.extendedToDouble(e));
        }
        fpu.setTagWord(ftw);
    }
}
