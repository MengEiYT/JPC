/*
    JPC mod addition (not in the original JPC): decoder for the MMX / SSE / SSE2 opcode map (two-byte 0F xx opcodes).

    Written for the JPC mod (GPL-2.0, like the rest of this tree). Not part of the original JPC.
*/

package org.jpc.emulator.execution.sse;

import org.jpc.emulator.execution.Executable;
import org.jpc.emulator.execution.decoder.PeekableInputStream;

public final class SseDecoder
{
    private SseDecoder() {}

    /** Indexed by (prefix << 8) | opcode: 0 = not handled here, 1 = modrm, 2 = modrm + imm8. */
    private static final byte[] KIND = new byte[0x400];

    private static void def(int pfx, int kind, int... ops)
    {
        for (int o : ops)
            KIND[pfx | o] = (byte) kind;
    }

    private static void range(int pfx, int from, int to)
    {
        for (int o = from; o <= to; o++)
            KIND[pfx | o] = 1;
    }

    static
    {
        final int N = 0, P66 = SseOp.P66, F3 = SseOp.PF3, F2 = SseOp.PF2;

        // ---- no prefix: SSE (single precision) and MMX
        def(N, 1, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F);
        range(N, 0x50, 0x5F);
        range(N, 0x60, 0x6B);
        def(N, 1, 0x6E, 0x6F, 0x74, 0x75, 0x76, 0x7E, 0x7F, 0xAE, 0xC3);
        range(N, 0xD1, 0xD5);
        range(N, 0xD7, 0xFE);
        KIND[N | 0xE6] = 0;   // only exists with a prefix
        KIND[N | 0xF0] = 0;
        def(N, 2, 0x70, 0x71, 0x72, 0x73, 0xC2, 0xC4, 0xC5, 0xC6);
        KIND[N | 0x52] = 1;
        KIND[N | 0x53] = 1;
        KIND[N | 0x5A] = 1;
        KIND[N | 0x5B] = 1;

        // ---- 66: SSE2 double precision and 128-bit integer
        def(P66, 1, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F);
        def(P66, 1, 0x50, 0x51);
        range(P66, 0x54, 0x5F);
        range(P66, 0x60, 0x6F);
        def(P66, 1, 0x74, 0x75, 0x76, 0x7E, 0x7F);
        range(P66, 0xD1, 0xFE);
        KIND[P66 | 0xD0] = 0;
        KIND[P66 | 0xF0] = 0;
        def(P66, 2, 0x70, 0x71, 0x72, 0x73, 0xC2, 0xC4, 0xC5, 0xC6);

        // ---- F3: scalar single and a few moves
        def(F3, 1, 0x10, 0x11, 0x2A, 0x2C, 0x2D, 0x51, 0x52, 0x53, 0x58, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x6F, 0x7E, 0x7F, 0xD6, 0xE6);
        def(F3, 2, 0x70, 0xC2);

        // ---- F2: scalar double
        def(F2, 1, 0x10, 0x11, 0x2A, 0x2C, 0x2D, 0x51, 0x58, 0x59, 0x5A, 0x5C, 0x5D, 0x5E, 0x5F, 0xD6, 0xE6);
        def(F2, 2, 0x70, 0xC2);
    }

    /**
     * Called by the main decoder after it has read 0F and the second opcode byte.
     * Returns null when this opcode is not one of ours, so the caller can carry on as before.
     */
    public static Executable decode(int b2, boolean p66, boolean pF2, boolean pF3, int blockStart, int opStart, int prefices, PeekableInputStream input)
    {
        int pfx = pF3 ? SseOp.PF3 : pF2 ? SseOp.PF2 : p66 ? SseOp.P66 : 0;
        int id = pfx | b2;

        // JPC's generated tables only produce CMPXCHG8B (0F C7 /1) for the REPNE-prefixed form; the plain form that
        // every 32-bit Linux kernel uses for its 64-bit atomics came out as an invalid opcode.
        if (b2 == 0xC7)
        {
            int modrm = input.peek() & 0xFF;
            if (modrm < 0xC0 && ((modrm >> 3) & 7) == 1)
                return new org.jpc.emulator.execution.opcodes.pm.cmpxchg8b_M_mem(blockStart, opStart, prefices, input);
            return null;
        }

        // prefetch and the hint-nop range (0F 18..1E, 0F 0D), also endbr32 (F3 0F 1E FB)
        if (b2 == 0x0D || (b2 >= 0x18 && b2 <= 0x1E))
            return new SseOp(blockStart, opStart, prefices, input, id, false, true);
        if (b2 == 0x77 && pfx == 0)
            return new SseOp(blockStart, opStart, prefices, input, id, false, false);

        int kind = KIND[id];
        if (kind == 0)
            return null;
        return new SseOp(blockStart, opStart, prefices, input, id, kind == 2, true);
    }
}
