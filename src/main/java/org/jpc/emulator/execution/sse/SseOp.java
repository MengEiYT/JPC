/*
    JPC mod addition (not in the original JPC): MMX / SSE / SSE2 instructions.

    Written for the JPC mod (GPL-2.0, like the rest of this tree). Not part of the original JPC.
    One class executes every instruction; the opcode id is (prefix << 8) | second opcode byte,
    where prefix 0 = none, 0x100 = 66, 0x200 = F3, 0x300 = F2.
*/

package org.jpc.emulator.execution.sse;

import org.jpc.emulator.execution.Executable;
import org.jpc.emulator.execution.decoder.Modrm;
import org.jpc.emulator.execution.decoder.PeekableInputStream;
import org.jpc.emulator.execution.decoder.Pointer;
import org.jpc.emulator.execution.decoder.Prefices;
import org.jpc.emulator.processor.Processor;
import org.jpc.emulator.processor.ProcessorException;
import org.jpc.emulator.processor.fpu64.FpuState64;

public final class SseOp extends Executable
{
    public static final int P66 = 0x100, PF3 = 0x200, PF2 = 0x300;

    private static final long LOW32 = 0xFFFFFFFFL;
    private static final long HIGH32 = 0xFFFFFFFF00000000L;

    final int id;
    final int op;
    final int pfx;
    final int modrm;
    final int reg;
    final int rm;
    final boolean isMem;
    final Pointer mem;
    final int imm;
    final int segIndex;
    /** 0: no checks, 1: x87-level checks (MMX), 2: SSE-level checks. */
    final int check;

    private long s0, s1;

    public SseOp(int blockStart, int eip, int prefices, PeekableInputStream input, int id, boolean hasImm, boolean hasModrm)
    {
        super(blockStart, eip);
        this.id = id;
        this.op = id & 0xFF;
        this.pfx = id & 0x300;
        this.segIndex = Prefices.getSegment(prefices, Processor.DS_INDEX);
        if (hasModrm)
        {
            modrm = input.readU8();
            reg = Modrm.reg(modrm);
            rm = Modrm.rm(modrm);
            isMem = Modrm.isMem(modrm);
            mem = isMem ? Modrm.getPointer(prefices, modrm, input) : null;
        }
        else
        {
            modrm = 0;
            reg = 0;
            rm = 0;
            isMem = false;
            mem = null;
        }
        imm = hasImm ? input.readU8() : 0;
        check = checkKind(op, pfx);
    }

    private static int checkKind(int op, int pfx)
    {
        if (op == 0x0D || (op >= 0x18 && op <= 0x1E) || op == 0xAE || op == 0xC3)
            return 0;
        if (op == 0x77)
            return 1;
        if (pfx == 0 && ((op >= 0x60 && op <= 0x7F) || op == 0xC4 || op == 0xC5 || op >= 0xD0))
            return 1;
        return 2;
    }

    public boolean isBranch()
    {
        return false;
    }

    public String toString()
    {
        return String.format("%s[%03x]", getClass().getName(), id);
    }

    // ------------------------------------------------------------------ operand helpers

    private void ld128(Processor cpu)
    {
        if (isMem)
        {
            s0 = mem.get64(cpu, 0);
            s1 = mem.get64(cpu, 8);
        }
        else
        {
            s0 = cpu.xmm[rm << 1];
            s1 = cpu.xmm[(rm << 1) + 1];
        }
    }

    private long ld64(Processor cpu)
    {
        return isMem ? mem.get64(cpu, 0) : cpu.xmm[rm << 1];
    }

    private int ld32(Processor cpu)
    {
        return isMem ? mem.get32(cpu, 0) : (int) cpu.xmm[rm << 1];
    }

    private long ldMM(Processor cpu)
    {
        return isMem ? mem.get64(cpu, 0) : cpu.mm[rm];
    }

    private int ed(Processor cpu)
    {
        return isMem ? mem.get32(cpu, 0) : cpu.regs[Modrm.Ed(modrm)].get32();
    }

    private void st128(Processor cpu, long v0, long v1)
    {
        if (isMem)
        {
            mem.set64(cpu, 0, v0);
            mem.set64(cpu, 8, v1);
        }
        else
        {
            cpu.xmm[rm << 1] = v0;
            cpu.xmm[(rm << 1) + 1] = v1;
        }
    }

    private static int rcOf(Processor cpu)
    {
        return (cpu.mxcsr >> 13) & 3;
    }

    private static void mmx(Processor cpu)
    {
        if (!cpu.mmxActive)
        {
            cpu.mmxActive = true;
            cpu.fpu.top = 0;
            cpu.fpu.setTagWord(0);
        }
    }

    private static void setComis(Processor cpu, boolean un, boolean lt, boolean eq)
    {
        cpu.flagStatus = 0;
        cpu.of = false;
        cpu.sf = false;
        cpu.af = false;
        cpu.zf = un || eq;
        cpu.pf = un;
        cpu.cf = un || lt;
    }

    private static int dword(long lo, long hi, int i)
    {
        switch (i & 3)
        {
            case 0: return (int) lo;
            case 1: return (int) (lo >>> 32);
            case 2: return (int) hi;
            default: return (int) (hi >>> 32);
        }
    }

    private static long pair(int lo, int hi)
    {
        return (lo & LOW32) | ((long) hi << 32);
    }

    private static int arithKind(int op)
    {
        switch (op)
        {
            case 0x51: return Simd.F_SQRT;
            case 0x52: return Simd.F_RSQRT;
            case 0x53: return Simd.F_RCP;
            case 0x58: return Simd.F_ADD;
            case 0x59: return Simd.F_MUL;
            case 0x5C: return Simd.F_SUB;
            case 0x5D: return Simd.F_MIN;
            case 0x5E: return Simd.F_DIV;
            default: return Simd.F_MAX;
        }
    }

    /** Lane-wise integer operations that apply independently to each 64-bit half. */
    private static long lane64(int op, long a, long b)
    {
        switch (op)
        {
            case 0x64: return Simd.lanes(Simd.GT, 8, a, b);
            case 0x65: return Simd.lanes(Simd.GT, 16, a, b);
            case 0x66: return Simd.lanes(Simd.GT, 32, a, b);
            case 0x74: return Simd.lanes(Simd.EQ, 8, a, b);
            case 0x75: return Simd.lanes(Simd.EQ, 16, a, b);
            case 0x76: return Simd.lanes(Simd.EQ, 32, a, b);
            case 0xD4: return a + b;
            case 0xD5: return Simd.lanes(Simd.MULLO, 16, a, b);
            case 0xD8: return Simd.lanes(Simd.SUBUS, 8, a, b);
            case 0xD9: return Simd.lanes(Simd.SUBUS, 16, a, b);
            case 0xDA: return Simd.lanes(Simd.MINU, 8, a, b);
            case 0xDB: return a & b;
            case 0xDC: return Simd.lanes(Simd.ADDUS, 8, a, b);
            case 0xDD: return Simd.lanes(Simd.ADDUS, 16, a, b);
            case 0xDE: return Simd.lanes(Simd.MAXU, 8, a, b);
            case 0xDF: return ~a & b;
            case 0xE0: return Simd.lanes(Simd.AVG, 8, a, b);
            case 0xE3: return Simd.lanes(Simd.AVG, 16, a, b);
            case 0xE4: return Simd.lanes(Simd.MULHU, 16, a, b);
            case 0xE5: return Simd.lanes(Simd.MULHS, 16, a, b);
            case 0xE8: return Simd.lanes(Simd.SUBS, 8, a, b);
            case 0xE9: return Simd.lanes(Simd.SUBS, 16, a, b);
            case 0xEA: return Simd.lanes(Simd.MINS, 16, a, b);
            case 0xEB: return a | b;
            case 0xEC: return Simd.lanes(Simd.ADDS, 8, a, b);
            case 0xED: return Simd.lanes(Simd.ADDS, 16, a, b);
            case 0xEE: return Simd.lanes(Simd.MAXS, 16, a, b);
            case 0xEF: return a ^ b;
            case 0xF4: return Simd.pmuludq(a, b);
            case 0xF5: return Simd.pmaddwd(a, b);
            case 0xF6: return Simd.psadbw(a, b);
            case 0xF8: return Simd.lanes(Simd.SUB, 8, a, b);
            case 0xF9: return Simd.lanes(Simd.SUB, 16, a, b);
            case 0xFA: return Simd.lanes(Simd.SUB, 32, a, b);
            case 0xFB: return a - b;
            case 0xFC: return Simd.lanes(Simd.ADD, 8, a, b);
            case 0xFD: return Simd.lanes(Simd.ADD, 16, a, b);
            case 0xFE: return Simd.lanes(Simd.ADD, 32, a, b);
            default: throw ProcessorException.UNDEFINED;
        }
    }

    private static boolean isLane64(int op)
    {
        switch (op)
        {
            case 0x64: case 0x65: case 0x66: case 0x74: case 0x75: case 0x76:
            case 0xD4: case 0xD5: case 0xD8: case 0xD9: case 0xDA: case 0xDB: case 0xDC: case 0xDD: case 0xDE: case 0xDF:
            case 0xE0: case 0xE3: case 0xE4: case 0xE5: case 0xE8: case 0xE9: case 0xEA: case 0xEB: case 0xEC: case 0xED: case 0xEE: case 0xEF:
            case 0xF4: case 0xF5: case 0xF6: case 0xF8: case 0xF9: case 0xFA: case 0xFB: case 0xFC: case 0xFD: case 0xFE:
                return true;
            default:
                return false;
        }
    }

    private static boolean isShift(int op)
    {
        switch (op)
        {
            case 0xD1: case 0xD2: case 0xD3: case 0xE1: case 0xE2: case 0xF1: case 0xF2: case 0xF3:
                return true;
            default:
                return false;
        }
    }

    private static long shiftOp(int op, long a, long count)
    {
        switch (op)
        {
            case 0xD1: return Simd.shift(Simd.SRL, 16, a, count);
            case 0xD2: return Simd.shift(Simd.SRL, 32, a, count);
            case 0xD3: return Simd.shift(Simd.SRL, 64, a, count);
            case 0xE1: return Simd.shift(Simd.SRA, 16, a, count);
            case 0xE2: return Simd.shift(Simd.SRA, 32, a, count);
            case 0xF1: return Simd.shift(Simd.SLL, 16, a, count);
            case 0xF2: return Simd.shift(Simd.SLL, 32, a, count);
            default: return Simd.shift(Simd.SLL, 64, a, count);
        }
    }

    private static int unpackWidth(int op)
    {
        switch (op)
        {
            case 0x60: case 0x68: return 1;
            case 0x61: case 0x69: return 2;
            default: return 4;
        }
    }

    // ------------------------------------------------------------------ execution

    public Branch execute(Processor cpu)
    {
        if (check != 0)
        {
            int cr0 = cpu.getCR0();
            if ((cr0 & Processor.CR0_FPU_EMULATION) != 0)
                throw ProcessorException.UNDEFINED;
            if (check == 2 && (cpu.getCR4() & Processor.CR4_OS_SUPPORT_FXSAVE_FXSTORE) == 0)
                throw ProcessorException.UNDEFINED;
            if ((cr0 & Processor.CR0_TASK_SWITCHED) != 0)
                throw ProcessorException.NO_FPU;
        }

        final long[] x = cpu.xmm;
        final int d = reg << 1;
        final int r = rm << 1;

        switch (op)
        {
            // ---- no operation: prefetch, hint nops (endbr32 and friends)
            case 0x0D: case 0x18: case 0x19: case 0x1A: case 0x1B: case 0x1C: case 0x1D: case 0x1E:
                break;

            case 0x77: // emms
                cpu.mmxActive = false;
                cpu.fpu.setTagWord(0xFFFF);
                break;

            // ---- moves
            case 0x10:
                if (pfx == 0 || pfx == P66)
                {
                    ld128(cpu);
                    x[d] = s0;
                    x[d + 1] = s1;
                }
                else if (pfx == PF3)
                {
                    if (isMem)
                    {
                        x[d] = mem.get32(cpu, 0) & LOW32;
                        x[d + 1] = 0;
                    }
                    else
                        x[d] = (x[d] & HIGH32) | (x[r] & LOW32);
                }
                else
                {
                    if (isMem)
                    {
                        x[d] = mem.get64(cpu, 0);
                        x[d + 1] = 0;
                    }
                    else
                        x[d] = x[r];
                }
                break;
            case 0x11:
                if (pfx == 0 || pfx == P66)
                    st128(cpu, x[d], x[d + 1]);
                else if (pfx == PF3)
                {
                    if (isMem)
                        mem.set32(cpu, 0, (int) x[d]);
                    else
                        x[r] = (x[r] & HIGH32) | (x[d] & LOW32);
                }
                else
                {
                    if (isMem)
                        mem.set64(cpu, 0, x[d]);
                    else
                        x[r] = x[d];
                }
                break;
            case 0x12:
                if (isMem)
                    x[d] = mem.get64(cpu, 0);
                else if (pfx == 0)
                    x[d] = x[r + 1];
                else
                    throw ProcessorException.UNDEFINED;
                break;
            case 0x13:
                if (!isMem)
                    throw ProcessorException.UNDEFINED;
                mem.set64(cpu, 0, x[d]);
                break;
            case 0x14:
            {
                ld128(cpu);
                long d0 = x[d];
                if (pfx == 0)
                {
                    x[d] = (d0 & LOW32) | (s0 << 32);
                    x[d + 1] = (d0 >>> 32) | (s0 & HIGH32);
                }
                else
                    x[d + 1] = s0;
                break;
            }
            case 0x15:
            {
                ld128(cpu);
                long d1 = x[d + 1];
                if (pfx == 0)
                {
                    x[d] = (d1 & LOW32) | (s1 << 32);
                    x[d + 1] = (d1 >>> 32) | (s1 & HIGH32);
                }
                else
                {
                    x[d] = d1;
                    x[d + 1] = s1;
                }
                break;
            }
            case 0x16:
                if (isMem)
                    x[d + 1] = mem.get64(cpu, 0);
                else if (pfx == 0)
                    x[d + 1] = x[r];
                else
                    throw ProcessorException.UNDEFINED;
                break;
            case 0x17:
                if (!isMem)
                    throw ProcessorException.UNDEFINED;
                mem.set64(cpu, 0, x[d + 1]);
                break;
            case 0x28:
                ld128(cpu);
                x[d] = s0;
                x[d + 1] = s1;
                break;
            case 0x29:
                st128(cpu, x[d], x[d + 1]);
                break;
            case 0x2B:
                if (!isMem)
                    throw ProcessorException.UNDEFINED;
                st128(cpu, x[d], x[d + 1]);
                break;

            // ---- conversions between integers and floating point
            case 0x2A:
            {
                int rc = rcOf(cpu);
                switch (pfx)
                {
                    case 0:
                    {
                        mmx(cpu);
                        long src = ldMM(cpu);
                        x[d] = pair(Simd.i2f((int) src, rc), Simd.i2f((int) (src >>> 32), rc));
                        break;
                    }
                    case PF3:
                        x[d] = (x[d] & HIGH32) | (Simd.i2f(ed(cpu), rc) & LOW32);
                        break;
                    case P66:
                    {
                        mmx(cpu);
                        long src = ldMM(cpu);
                        x[d] = Simd.i2d((int) src);
                        x[d + 1] = Simd.i2d((int) (src >>> 32));
                        break;
                    }
                    default:
                        x[d] = Simd.i2d(ed(cpu));
                        break;
                }
                break;
            }
            case 0x2C:
            case 0x2D:
            {
                int rc = op == 0x2C ? 3 : rcOf(cpu);
                switch (pfx)
                {
                    case 0:
                    {
                        mmx(cpu);
                        long src = isMem ? mem.get64(cpu, 0) : x[r];
                        cpu.mm[reg] = pair(Simd.f2i((int) src, rc), Simd.f2i((int) (src >>> 32), rc));
                        break;
                    }
                    case P66:
                        mmx(cpu);
                        ld128(cpu);
                        cpu.mm[reg] = pair(Simd.d2i(s0, rc), Simd.d2i(s1, rc));
                        break;
                    case PF3:
                        cpu.regs[Modrm.Gd(modrm)].set32(Simd.f2i(ld32(cpu), rc));
                        break;
                    default:
                        cpu.regs[Modrm.Gd(modrm)].set32(Simd.d2i(ld64(cpu), rc));
                        break;
                }
                break;
            }
            case 0x2E:
            case 0x2F:
            {
                double a, b;
                if (pfx == 0)
                {
                    a = Float.intBitsToFloat((int) x[d]);
                    b = Float.intBitsToFloat(ld32(cpu));
                }
                else
                {
                    a = Double.longBitsToDouble(x[d]);
                    b = Double.longBitsToDouble(ld64(cpu));
                }
                setComis(cpu, a != a || b != b, a < b, a == b);
                break;
            }
            case 0x50:
            {
                if (isMem)
                    throw ProcessorException.UNDEFINED;
                int v;
                if (pfx == 0)
                    v = (int) ((x[r] >>> 31) & 1) | (int) (((x[r] >>> 63) & 1) << 1) | (int) (((x[r + 1] >>> 31) & 1) << 2) | (int) (((x[r + 1] >>> 63) & 1) << 3);
                else
                    v = (int) (x[r] >>> 63) | (int) ((x[r + 1] >>> 63) << 1);
                cpu.regs[Modrm.Gd(modrm)].set32(v);
                break;
            }

            // ---- floating point arithmetic
            case 0x51: case 0x52: case 0x53: case 0x58: case 0x59: case 0x5C: case 0x5D: case 0x5E: case 0x5F:
            {
                int kind = arithKind(op);
                switch (pfx)
                {
                    case 0:
                    {
                        ld128(cpu);
                        long a0 = x[d], a1 = x[d + 1];
                        x[d] = pair(Simd.fop(kind, (int) a0, (int) s0), Simd.fop(kind, (int) (a0 >>> 32), (int) (s0 >>> 32)));
                        x[d + 1] = pair(Simd.fop(kind, (int) a1, (int) s1), Simd.fop(kind, (int) (a1 >>> 32), (int) (s1 >>> 32)));
                        break;
                    }
                    case PF3:
                    {
                        int s = ld32(cpu);
                        x[d] = (x[d] & HIGH32) | (Simd.fop(kind, (int) x[d], s) & LOW32);
                        break;
                    }
                    case P66:
                        ld128(cpu);
                        x[d] = Simd.dop(kind, x[d], s0);
                        x[d + 1] = Simd.dop(kind, x[d + 1], s1);
                        break;
                    default:
                        x[d] = Simd.dop(kind, x[d], ld64(cpu));
                        break;
                }
                break;
            }
            case 0x54:
                ld128(cpu);
                x[d] &= s0;
                x[d + 1] &= s1;
                break;
            case 0x55:
                ld128(cpu);
                x[d] = ~x[d] & s0;
                x[d + 1] = ~x[d + 1] & s1;
                break;
            case 0x56:
                ld128(cpu);
                x[d] |= s0;
                x[d + 1] |= s1;
                break;
            case 0x57:
                ld128(cpu);
                x[d] ^= s0;
                x[d + 1] ^= s1;
                break;
            case 0x5A:
            {
                int rc = rcOf(cpu);
                switch (pfx)
                {
                    case 0:
                    {
                        long src = isMem ? mem.get64(cpu, 0) : x[r];
                        x[d] = Simd.f2d((int) src);
                        x[d + 1] = Simd.f2d((int) (src >>> 32));
                        break;
                    }
                    case P66:
                        ld128(cpu);
                        x[d] = pair(Simd.d2f(s0, rc), Simd.d2f(s1, rc));
                        x[d + 1] = 0;
                        break;
                    case PF3:
                        x[d] = Simd.f2d(ld32(cpu));
                        break;
                    default:
                        x[d] = (x[d] & HIGH32) | (Simd.d2f(ld64(cpu), rc) & LOW32);
                        break;
                }
                break;
            }
            case 0x5B:
            {
                ld128(cpu);
                if (pfx == 0)
                {
                    int rc = rcOf(cpu);
                    x[d] = pair(Simd.i2f((int) s0, rc), Simd.i2f((int) (s0 >>> 32), rc));
                    x[d + 1] = pair(Simd.i2f((int) s1, rc), Simd.i2f((int) (s1 >>> 32), rc));
                }
                else
                {
                    int rc = pfx == PF3 ? 3 : rcOf(cpu);
                    x[d] = pair(Simd.f2i((int) s0, rc), Simd.f2i((int) (s0 >>> 32), rc));
                    x[d + 1] = pair(Simd.f2i((int) s1, rc), Simd.f2i((int) (s1 >>> 32), rc));
                }
                break;
            }
            case 0xE6:
            {
                if (pfx == PF3)
                {
                    long src = ld64(cpu);
                    x[d] = Simd.i2d((int) src);
                    x[d + 1] = Simd.i2d((int) (src >>> 32));
                }
                else
                {
                    int rc = pfx == P66 ? 3 : rcOf(cpu);
                    ld128(cpu);
                    x[d] = pair(Simd.d2i(s0, rc), Simd.d2i(s1, rc));
                    x[d + 1] = 0;
                }
                break;
            }
            case 0xC2:
            {
                int pred = imm & 7;
                switch (pfx)
                {
                    case 0:
                    {
                        ld128(cpu);
                        long a0 = x[d], a1 = x[d + 1];
                        int m0 = Simd.cmpF(pred, Float.intBitsToFloat((int) a0), Float.intBitsToFloat((int) s0)) ? -1 : 0;
                        int m1 = Simd.cmpF(pred, Float.intBitsToFloat((int) (a0 >>> 32)), Float.intBitsToFloat((int) (s0 >>> 32))) ? -1 : 0;
                        int m2 = Simd.cmpF(pred, Float.intBitsToFloat((int) a1), Float.intBitsToFloat((int) s1)) ? -1 : 0;
                        int m3 = Simd.cmpF(pred, Float.intBitsToFloat((int) (a1 >>> 32)), Float.intBitsToFloat((int) (s1 >>> 32))) ? -1 : 0;
                        x[d] = pair(m0, m1);
                        x[d + 1] = pair(m2, m3);
                        break;
                    }
                    case PF3:
                    {
                        int m = Simd.cmpF(pred, Float.intBitsToFloat((int) x[d]), Float.intBitsToFloat(ld32(cpu))) ? -1 : 0;
                        x[d] = (x[d] & HIGH32) | (m & LOW32);
                        break;
                    }
                    case P66:
                        ld128(cpu);
                        x[d] = Simd.cmpD(pred, Double.longBitsToDouble(x[d]), Double.longBitsToDouble(s0)) ? -1L : 0L;
                        x[d + 1] = Simd.cmpD(pred, Double.longBitsToDouble(x[d + 1]), Double.longBitsToDouble(s1)) ? -1L : 0L;
                        break;
                    default:
                        x[d] = Simd.cmpD(pred, Double.longBitsToDouble(x[d]), Double.longBitsToDouble(ld64(cpu))) ? -1L : 0L;
                        break;
                }
                break;
            }

            // ---- integer unpack and pack
            case 0x60: case 0x61: case 0x62: case 0x68: case 0x69: case 0x6A:
            {
                int w = unpackWidth(op);
                boolean high = op >= 0x68;
                if (pfx == 0)
                {
                    mmx(cpu);
                    long a = cpu.mm[reg], b = ldMM(cpu);
                    cpu.mm[reg] = high ? Simd.interleave(w, (int) (a >>> 32), (int) (b >>> 32)) : Simd.interleave(w, (int) a, (int) b);
                }
                else
                {
                    ld128(cpu);
                    long a = high ? x[d + 1] : x[d];
                    long b = high ? s1 : s0;
                    x[d] = Simd.interleave(w, (int) a, (int) b);
                    x[d + 1] = Simd.interleave(w, (int) (a >>> 32), (int) (b >>> 32));
                }
                break;
            }
            case 0x63: case 0x67: case 0x6B:
            {
                boolean words = op == 0x6B;
                boolean uns = op == 0x67;
                if (pfx == 0)
                {
                    mmx(cpu);
                    long a = cpu.mm[reg], b = ldMM(cpu);
                    long pa = (words ? Simd.pack2(a) : Simd.pack4(a, uns)) & LOW32;
                    long pb = (words ? Simd.pack2(b) : Simd.pack4(b, uns)) & LOW32;
                    cpu.mm[reg] = pa | (pb << 32);
                }
                else
                {
                    ld128(cpu);
                    long a0 = x[d], a1 = x[d + 1];
                    long r0 = (words ? Simd.pack2(a0) : Simd.pack4(a0, uns)) & LOW32;
                    long r1 = (words ? Simd.pack2(a1) : Simd.pack4(a1, uns)) & LOW32;
                    long r2 = (words ? Simd.pack2(s0) : Simd.pack4(s0, uns)) & LOW32;
                    long r3 = (words ? Simd.pack2(s1) : Simd.pack4(s1, uns)) & LOW32;
                    x[d] = r0 | (r1 << 32);
                    x[d + 1] = r2 | (r3 << 32);
                }
                break;
            }
            case 0x6C:
                ld128(cpu);
                x[d + 1] = s0;
                break;
            case 0x6D:
                ld128(cpu);
                x[d] = x[d + 1];
                x[d + 1] = s1;
                break;

            // ---- data moves with integer registers
            case 0x6E:
                if (pfx == 0)
                {
                    mmx(cpu);
                    cpu.mm[reg] = ed(cpu) & LOW32;
                }
                else
                {
                    x[d] = ed(cpu) & LOW32;
                    x[d + 1] = 0;
                }
                break;
            case 0x6F:
                if (pfx == 0)
                {
                    mmx(cpu);
                    cpu.mm[reg] = ldMM(cpu);
                }
                else
                {
                    ld128(cpu);
                    x[d] = s0;
                    x[d + 1] = s1;
                }
                break;
            case 0x70:
            {
                switch (pfx)
                {
                    case 0:
                    {
                        mmx(cpu);
                        long src = ldMM(cpu);
                        long res = 0;
                        for (int i = 0; i < 4; i++)
                            res |= ((src >>> (16 * ((imm >> (2 * i)) & 3))) & 0xFFFFL) << (16 * i);
                        cpu.mm[reg] = res;
                        break;
                    }
                    case P66:
                    {
                        ld128(cpu);
                        int e0 = dword(s0, s1, imm), e1 = dword(s0, s1, imm >> 2), e2 = dword(s0, s1, imm >> 4), e3 = dword(s0, s1, imm >> 6);
                        x[d] = pair(e0, e1);
                        x[d + 1] = pair(e2, e3);
                        break;
                    }
                    case PF2:
                    {
                        ld128(cpu);
                        long res = 0;
                        for (int i = 0; i < 4; i++)
                            res |= ((s0 >>> (16 * ((imm >> (2 * i)) & 3))) & 0xFFFFL) << (16 * i);
                        x[d] = res;
                        x[d + 1] = s1;
                        break;
                    }
                    default:
                    {
                        ld128(cpu);
                        long res = 0;
                        for (int i = 0; i < 4; i++)
                            res |= ((s1 >>> (16 * ((imm >> (2 * i)) & 3))) & 0xFFFFL) << (16 * i);
                        x[d] = s0;
                        x[d + 1] = res;
                        break;
                    }
                }
                break;
            }
            case 0x71: case 0x72: case 0x73:
            {
                if (isMem)
                    throw ProcessorException.UNDEFINED;
                long count = imm & 0xFF;
                int kind = reg; // 2 = srl, 4 = sra, 6 = sll, 3 = srldq, 7 = slldq
                int dir, bits;
                if (op == 0x73 && (kind == 3 || kind == 7))
                {
                    if (pfx == 0)
                        throw ProcessorException.UNDEFINED;
                    int c = imm > 16 ? 16 : imm;
                    long lo = x[r], hi = x[r + 1];
                    if (kind == 7) // pslldq
                    {
                        if (c == 0) { }
                        else if (c < 8) { hi = (hi << (8 * c)) | (lo >>> (64 - 8 * c)); lo = lo << (8 * c); }
                        else if (c < 16) { hi = lo << (8 * (c - 8)); lo = 0; }
                        else { hi = 0; lo = 0; }
                    }
                    else // psrldq
                    {
                        if (c == 0) { }
                        else if (c < 8) { lo = (lo >>> (8 * c)) | (hi << (64 - 8 * c)); hi = hi >>> (8 * c); }
                        else if (c < 16) { lo = hi >>> (8 * (c - 8)); hi = 0; }
                        else { hi = 0; lo = 0; }
                    }
                    x[r] = lo;
                    x[r + 1] = hi;
                    break;
                }
                if (kind == 2) dir = Simd.SRL;
                else if (kind == 4) dir = Simd.SRA;
                else if (kind == 6) dir = Simd.SLL;
                else throw ProcessorException.UNDEFINED;
                bits = op == 0x71 ? 16 : (op == 0x72 ? 32 : 64);
                if (dir == Simd.SRA && bits == 64)
                    throw ProcessorException.UNDEFINED;
                if (pfx == 0)
                {
                    mmx(cpu);
                    cpu.mm[rm] = Simd.shift(dir, bits, cpu.mm[rm], count);
                }
                else
                {
                    x[r] = Simd.shift(dir, bits, x[r], count);
                    x[r + 1] = Simd.shift(dir, bits, x[r + 1], count);
                }
                break;
            }
            case 0x7E:
                if (pfx == PF3)
                {
                    x[d] = ld64(cpu);
                    x[d + 1] = 0;
                }
                else
                {
                    int v;
                    if (pfx == 0)
                    {
                        mmx(cpu);
                        v = (int) cpu.mm[reg];
                    }
                    else
                        v = (int) x[d];
                    if (isMem)
                        mem.set32(cpu, 0, v);
                    else
                        cpu.regs[Modrm.Ed(modrm)].set32(v);
                }
                break;
            case 0x7F:
                if (pfx == 0)
                {
                    mmx(cpu);
                    if (isMem)
                        mem.set64(cpu, 0, cpu.mm[reg]);
                    else
                        cpu.mm[rm] = cpu.mm[reg];
                }
                else
                    st128(cpu, x[d], x[d + 1]);
                break;
            case 0xC3:
                if (!isMem)
                    throw ProcessorException.UNDEFINED;
                mem.set32(cpu, 0, cpu.regs[Modrm.Gd(modrm)].get32());
                break;
            case 0xC4:
            {
                int w = isMem ? (mem.get16(cpu, 0) & 0xFFFF) : (cpu.regs[Modrm.Ed(modrm)].get32() & 0xFFFF);
                if (pfx == 0)
                {
                    mmx(cpu);
                    int sh = (imm & 3) * 16;
                    cpu.mm[reg] = (cpu.mm[reg] & ~(0xFFFFL << sh)) | ((long) w << sh);
                }
                else
                {
                    int k = imm & 7;
                    int sh = (k & 3) * 16;
                    int q = d + (k >> 2);
                    x[q] = (x[q] & ~(0xFFFFL << sh)) | ((long) w << sh);
                }
                break;
            }
            case 0xC5:
            {
                if (isMem)
                    throw ProcessorException.UNDEFINED;
                int w;
                if (pfx == 0)
                {
                    mmx(cpu);
                    w = (int) (cpu.mm[rm] >>> ((imm & 3) * 16)) & 0xFFFF;
                }
                else
                {
                    int k = imm & 7;
                    w = (int) (x[r + (k >> 2)] >>> ((k & 3) * 16)) & 0xFFFF;
                }
                cpu.regs[Modrm.Gd(modrm)].set32(w);
                break;
            }
            case 0xC6:
            {
                ld128(cpu);
                long d0 = x[d], d1 = x[d + 1];
                if (pfx == 0)
                {
                    int e0 = dword(d0, d1, imm), e1 = dword(d0, d1, imm >> 2), e2 = dword(s0, s1, imm >> 4), e3 = dword(s0, s1, imm >> 6);
                    x[d] = pair(e0, e1);
                    x[d + 1] = pair(e2, e3);
                }
                else
                {
                    x[d] = (imm & 1) == 0 ? d0 : d1;
                    x[d + 1] = (imm & 2) == 0 ? s0 : s1;
                }
                break;
            }
            case 0xD6:
                if (pfx == P66)
                {
                    if (isMem)
                        mem.set64(cpu, 0, x[d]);
                    else
                    {
                        x[r] = x[d];
                        x[r + 1] = 0;
                    }
                }
                else if (pfx == PF3) // movq2dq
                {
                    if (isMem)
                        throw ProcessorException.UNDEFINED;
                    mmx(cpu);
                    x[d] = cpu.mm[rm];
                    x[d + 1] = 0;
                }
                else // movdq2q
                {
                    if (isMem)
                        throw ProcessorException.UNDEFINED;
                    mmx(cpu);
                    cpu.mm[reg] = x[r];
                }
                break;
            case 0xD7:
            {
                if (isMem)
                    throw ProcessorException.UNDEFINED;
                int v = 0;
                if (pfx == 0)
                {
                    mmx(cpu);
                    long m = cpu.mm[rm];
                    for (int i = 0; i < 8; i++)
                        v |= (int) ((m >>> (8 * i + 7)) & 1) << i;
                }
                else
                {
                    for (int i = 0; i < 8; i++)
                    {
                        v |= (int) ((x[r] >>> (8 * i + 7)) & 1) << i;
                        v |= (int) ((x[r + 1] >>> (8 * i + 7)) & 1) << (i + 8);
                    }
                }
                cpu.regs[Modrm.Gd(modrm)].set32(v);
                break;
            }
            case 0xE7:
                if (!isMem)
                    throw ProcessorException.UNDEFINED;
                if (pfx == 0)
                {
                    mmx(cpu);
                    mem.set64(cpu, 0, cpu.mm[reg]);
                }
                else
                    st128(cpu, x[d], x[d + 1]);
                break;
            case 0xF7:
            {
                if (isMem)
                    throw ProcessorException.UNDEFINED;
                int edi = cpu.r_edi.get32();
                if (pfx == 0)
                {
                    mmx(cpu);
                    long data = cpu.mm[reg], mask = cpu.mm[rm];
                    for (int i = 0; i < 8; i++)
                        if (((mask >>> (8 * i + 7)) & 1) != 0)
                            cpu.segs[segIndex].setByte(edi + i, (byte) (data >>> (8 * i)));
                }
                else
                {
                    for (int i = 0; i < 16; i++)
                    {
                        long mask = x[r + (i >> 3)];
                        long data = x[d + (i >> 3)];
                        int sh = 8 * (i & 7);
                        if (((mask >>> (sh + 7)) & 1) != 0)
                            cpu.segs[segIndex].setByte(edi + i, (byte) (data >>> sh));
                    }
                }
                break;
            }

            case 0xAE:
                group15(cpu);
                break;

            default:
                if (isShift(op))
                {
                    if (pfx == 0)
                    {
                        mmx(cpu);
                        cpu.mm[reg] = shiftOp(op, cpu.mm[reg], ldMM(cpu));
                    }
                    else
                    {
                        ld128(cpu);
                        x[d] = shiftOp(op, x[d], s0);
                        x[d + 1] = shiftOp(op, x[d + 1], s0);
                    }
                }
                else if (isLane64(op))
                {
                    if (pfx == 0)
                    {
                        mmx(cpu);
                        cpu.mm[reg] = lane64(op, cpu.mm[reg], ldMM(cpu));
                    }
                    else
                    {
                        ld128(cpu);
                        long a0 = x[d], a1 = x[d + 1];
                        x[d] = lane64(op, a0, s0);
                        x[d + 1] = lane64(op, a1, s1);
                    }
                }
                else
                    throw ProcessorException.UNDEFINED;
                break;
        }
        return Branch.None;
    }

    // ------------------------------------------------------------------ 0F AE: fences, fxsave, ldmxcsr ...

    private void group15(Processor cpu)
    {
        if (!isMem)
        {
            // lfence / mfence / sfence: nothing to do in a single-threaded interpreter
            if (reg == 5 || reg == 6 || reg == 7)
                return;
            throw ProcessorException.UNDEFINED;
        }
        switch (reg)
        {
            case 0: // fxsave
            case 1: // fxrstor
            {
                int cr0 = cpu.getCR0();
                if ((cr0 & Processor.CR0_FPU_EMULATION) != 0)
                    throw ProcessorException.UNDEFINED;
                if ((cr0 & Processor.CR0_TASK_SWITCHED) != 0)
                    throw ProcessorException.NO_FPU;
                if (reg == 0)
                    fxsave(cpu);
                else
                    fxrstor(cpu);
                return;
            }
            case 2: // ldmxcsr
            case 3: // stmxcsr
            {
                int cr0 = cpu.getCR0();
                if ((cr0 & Processor.CR0_FPU_EMULATION) != 0 || (cpu.getCR4() & Processor.CR4_OS_SUPPORT_FXSAVE_FXSTORE) == 0)
                    throw ProcessorException.UNDEFINED;
                if ((cr0 & Processor.CR0_TASK_SWITCHED) != 0)
                    throw ProcessorException.NO_FPU;
                if (reg == 2)
                {
                    int v = mem.get32(cpu, 0);
                    if ((v & ~0xFFBF) != 0)
                        throw ProcessorException.GENERAL_PROTECTION_0;
                    cpu.mxcsr = v;
                }
                else
                    mem.set32(cpu, 0, cpu.mxcsr);
                return;
            }
            case 7: // clflush
                return;
            default:
                throw ProcessorException.UNDEFINED;
        }
    }

    private void fxsave(Processor cpu)
    {
        org.jpc.emulator.processor.fpu64.FpuState fpu = cpu.fpu;
        int fsw = fpu.getStatus();
        int tw = fpu.getTagWord();
        int abridged = 0;
        for (int i = 0; i < 8; i++)
            if (((tw >> (2 * i)) & 3) != 3)
                abridged |= 1 << i;
        double[] st = fpu.getStack();

        // Write everything that can fault first would be ideal; the memory model faults on the first
        // bad page and the instruction is restarted, so writing in order is fine.
        mem.set32(cpu, 0, (fpu.getControl() & 0xFFFF) | ((fsw & 0xFFFF) << 16));
        mem.set32(cpu, 4, abridged);
        mem.set32(cpu, 8, (int) fpu.lastIP);
        mem.set32(cpu, 12, 0);
        mem.set32(cpu, 16, (int) fpu.lastData);
        mem.set32(cpu, 20, 0);
        mem.set32(cpu, 24, cpu.mxcsr);
        mem.set32(cpu, 28, 0xFFBF);
        for (int i = 0; i < 8; i++)
        {
            long lo;
            int hi;
            if (cpu.mmxActive)
            {
                lo = cpu.mm[(fpu.top + i) & 7];
                hi = 0xFFFF;
            }
            else if (((tw >> (2 * ((fpu.top + i) & 7))) & 3) == 3)
            {
                lo = 0;
                hi = 0;
            }
            else
            {
                byte[] e = FpuState64.doubleToExtended(st[i], false);
                lo = 0;
                for (int j = 7; j >= 0; j--)
                    lo = (lo << 8) | (e[j] & 0xFF);
                hi = (e[8] & 0xFF) | ((e[9] & 0xFF) << 8);
            }
            mem.set64(cpu, 32 + 16 * i, lo);
            mem.set64(cpu, 40 + 16 * i, hi & 0xFFFFL);
        }
        for (int i = 0; i < 8; i++)
        {
            mem.set64(cpu, 160 + 16 * i, cpu.xmm[2 * i]);
            mem.set64(cpu, 168 + 16 * i, cpu.xmm[2 * i + 1]);
        }
    }

    private void fxrstor(Processor cpu)
    {
        org.jpc.emulator.processor.fpu64.FpuState fpu = cpu.fpu;
        int fcwFsw = mem.get32(cpu, 0);
        int abridged = mem.get32(cpu, 4) & 0xFF;
        int mxcsr = mem.get32(cpu, 24);
        if ((mxcsr & ~0xFFBF) != 0)
            throw ProcessorException.GENERAL_PROTECTION_0;

        long[] lo = new long[8];
        int[] hi = new int[8];
        for (int i = 0; i < 8; i++)
        {
            lo[i] = mem.get64(cpu, 32 + 16 * i);
            hi[i] = (int) mem.get64(cpu, 40 + 16 * i) & 0xFFFF;
        }
        long[] xs = new long[16];
        for (int i = 0; i < 8; i++)
        {
            xs[2 * i] = mem.get64(cpu, 160 + 16 * i);
            xs[2 * i + 1] = mem.get64(cpu, 168 + 16 * i);
        }

        fpu.setControl(fcwFsw & 0xFFFF);
        fpu.setStatus(fcwFsw >>> 16);

        boolean mmxState = abridged == 0xFF;
        for (int i = 0; i < 8 && mmxState; i++)
            if (hi[i] != 0xFFFF)
                mmxState = false;

        if (mmxState)
        {
            for (int i = 0; i < 8; i++)
                cpu.mm[(fpu.top + i) & 7] = lo[i];
            cpu.mmxActive = true;
            fpu.setTagWord(0);
        }
        else
        {
            cpu.mmxActive = false;
            int newTags = 0;
            for (int i = 0; i < 8; i++)
            {
                byte[] e = new byte[10];
                for (int j = 0; j < 8; j++)
                    e[j] = (byte) (lo[i] >>> (8 * j));
                e[8] = (byte) hi[i];
                e[9] = (byte) (hi[i] >>> 8);
                fpu.setST(i, FpuState64.extendedToDouble(e));
            }
            for (int p = 0; p < 8; p++)
                if ((abridged & (1 << p)) == 0)
                    newTags |= 3 << (2 * p);
            fpu.setTagWord(newTags);
        }
        cpu.mxcsr = mxcsr;
        System.arraycopy(xs, 0, cpu.xmm, 0, 16);
    }
}
