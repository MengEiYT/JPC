/*
    JPC mod addition (not in the original JPC): lane arithmetic for MMX / SSE / SSE2.

    Written for the JPC mod (GPL-2.0, like the rest of this tree). Not part of the original JPC.
    All results follow the Intel SDM, including NaN propagation, saturation and rounding.
*/

package org.jpc.emulator.execution.sse;

public final class Simd
{
    private Simd() {}

    // ------------------------------------------------------------------ integer lanes (one 64-bit word)

    public static final int ADD = 0, SUB = 1, ADDS = 2, ADDUS = 3, SUBS = 4, SUBUS = 5, EQ = 6, GT = 7,
            MINU = 8, MAXU = 9, MINS = 10, MAXS = 11, AVG = 12, MULLO = 13, MULHS = 14, MULHU = 15;

    static long sext(long v, int bits)
    {
        int s = 64 - bits;
        return (v << s) >> s;
    }

    /** Applies a lane operation to every lane of a and b. bits is the lane width (8, 16, 32 or 64). */
    public static long lanes(int kind, int bits, long a, long b)
    {
        final int n = 64 / bits;
        final long mask = bits == 64 ? -1L : (1L << bits) - 1;
        long res = 0;
        for (int i = 0; i < n; i++)
        {
            int sh = i * bits;
            long x = (a >>> sh) & mask;
            long y = (b >>> sh) & mask;
            long r;
            switch (kind)
            {
                case ADD: r = x + y; break;
                case SUB: r = x - y; break;
                case ADDS:
                {
                    long v = sext(x, bits) + sext(y, bits);
                    long max = (1L << (bits - 1)) - 1, min = -(1L << (bits - 1));
                    r = v > max ? max : (v < min ? min : v);
                    break;
                }
                case ADDUS:
                {
                    long v = x + y;
                    r = v > mask ? mask : v;
                    break;
                }
                case SUBS:
                {
                    long v = sext(x, bits) - sext(y, bits);
                    long max = (1L << (bits - 1)) - 1, min = -(1L << (bits - 1));
                    r = v > max ? max : (v < min ? min : v);
                    break;
                }
                case SUBUS: r = x > y ? x - y : 0; break;
                case EQ: r = x == y ? mask : 0; break;
                case GT: r = sext(x, bits) > sext(y, bits) ? mask : 0; break;
                case MINU: r = x < y ? x : y; break;
                case MAXU: r = x > y ? x : y; break;
                case MINS: r = sext(x, bits) < sext(y, bits) ? x : y; break;
                case MAXS: r = sext(x, bits) > sext(y, bits) ? x : y; break;
                case AVG: r = (x + y + 1) >>> 1; break;
                case MULLO: r = x * y; break;
                case MULHS: r = (sext(x, bits) * sext(y, bits)) >> 16; break;
                case MULHU: r = (x * y) >>> 16; break;
                default: r = 0;
            }
            res |= (r & mask) << sh;
        }
        return res;
    }

    public static long pmaddwd(long a, long b)
    {
        int lo = (short) a * (short) b + (short) (a >>> 16) * (short) (b >>> 16);
        int hi = (short) (a >>> 32) * (short) (b >>> 32) + (short) (a >>> 48) * (short) (b >>> 48);
        return (lo & 0xFFFFFFFFL) | ((long) hi << 32);
    }

    public static long pmuludq(long a, long b)
    {
        return (a & 0xFFFFFFFFL) * (b & 0xFFFFFFFFL);
    }

    public static long psadbw(long a, long b)
    {
        int s = 0;
        for (int i = 0; i < 8; i++)
            s += Math.abs(((int) (a >>> (8 * i)) & 0xFF) - ((int) (b >>> (8 * i)) & 0xFF));
        return s & 0xFFFFL;
    }

    public static final int SLL = 0, SRL = 1, SRA = 2;

    /** Shifts every lane of a by count (a full 64-bit unsigned count). */
    public static long shift(int dir, int bits, long a, long count)
    {
        boolean big = Long.compareUnsigned(count, bits) >= 0;
        if (bits == 64)
        {
            if (big)
                return 0;
            return dir == SLL ? a << count : a >>> count;
        }
        final long mask = (1L << bits) - 1;
        final int n = 64 / bits;
        int c = big ? bits - 1 : (int) count;
        long res = 0;
        for (int i = 0; i < n; i++)
        {
            int sh = i * bits;
            long x = (a >>> sh) & mask;
            long r;
            if (dir == SLL)
                r = big ? 0 : (x << c) & mask;
            else if (dir == SRL)
                r = big ? 0 : x >>> c;
            else
                r = (sext(x, bits) >> c) & mask;
            res |= r << sh;
        }
        return res;
    }

    static int satS8(int v) { return v > 127 ? 127 : (v < -128 ? -128 : v); }
    static int satU8(int v) { return v > 255 ? 255 : (v < 0 ? 0 : v); }
    static int satS16(int v) { return v > 32767 ? 32767 : (v < -32768 ? -32768 : v); }

    /** Four signed words to four saturated bytes (packsswb / packuswb), result in the low 32 bits. */
    public static int pack4(long x, boolean unsigned)
    {
        int r = 0;
        for (int i = 0; i < 4; i++)
        {
            int w = (short) (x >>> (16 * i));
            int s = unsigned ? satU8(w) : satS8(w);
            r |= (s & 0xFF) << (8 * i);
        }
        return r;
    }

    /** Two signed dwords to two saturated words (packssdw), result in the low 32 bits. */
    public static int pack2(long x)
    {
        int r = 0;
        for (int i = 0; i < 2; i++)
        {
            int d = (int) (x >>> (32 * i));
            r |= (satS16(d) & 0xFFFF) << (16 * i);
        }
        return r;
    }

    /** Interleaves the w-byte elements of two 32-bit chunks: a0 b0 a1 b1 ... (punpck*). */
    public static long interleave(int w, int a32, int b32)
    {
        int n = 4 / w;
        long m = (1L << (8 * w)) - 1;
        long a = a32 & 0xFFFFFFFFL, b = b32 & 0xFFFFFFFFL;
        long r = 0;
        for (int i = 0; i < n; i++)
        {
            long ea = (a >>> (8 * w * i)) & m;
            long eb = (b >>> (8 * w * i)) & m;
            r |= ea << (2 * i * 8 * w);
            r |= eb << ((2 * i + 1) * 8 * w);
        }
        return r;
    }

    // ------------------------------------------------------------------ floating point

    public static final int QNAN32 = 0xFFC00000;
    public static final long QNAN64 = 0xFFF8000000000000L;
    public static final int INDEFINITE = 0x80000000;

    public static final int F_ADD = 0, F_SUB = 1, F_MUL = 2, F_DIV = 3, F_MIN = 4, F_MAX = 5, F_SQRT = 6, F_RCP = 7, F_RSQRT = 8;

    /** Single precision operation on raw bits. Unary operations (sqrt, rcp, rsqrt) use only b. */
    public static int fop(int kind, int a, int b)
    {
        float x = Float.intBitsToFloat(a);
        float y = Float.intBitsToFloat(b);
        switch (kind)
        {
            case F_MIN: return x < y ? a : b;
            case F_MAX: return x > y ? a : b;
            case F_SQRT:
                if (y != y) return b | 0x400000;
                if (y < 0) return QNAN32;
                return Float.floatToRawIntBits((float) Math.sqrt(y));
            case F_RCP:
            {
                if (y != y) return b | 0x400000;
                // Intel: denormal inputs are treated as zero of the same sign, tiny results flush to zero
                if ((b & 0x7F800000) == 0) y = Float.intBitsToFloat(b & 0x80000000);
                int r = Float.floatToRawIntBits(1.0f / y);
                if ((r & 0x7F800000) == 0) r &= 0x80000000;
                return r;
            }
            case F_RSQRT:
            {
                if (y != y) return b | 0x400000;
                if ((b & 0x7F800000) == 0) y = Float.intBitsToFloat(b & 0x80000000);
                if (y < 0) return QNAN32;
                return Float.floatToRawIntBits(1.0f / (float) Math.sqrt(y));
            }
            default:
        }
        if (x != x) return a | 0x400000;
        if (y != y) return b | 0x400000;
        float r;
        switch (kind)
        {
            case F_ADD: r = x + y; break;
            case F_SUB: r = x - y; break;
            case F_MUL: r = x * y; break;
            default: r = x / y; break;
        }
        return r != r ? QNAN32 : Float.floatToRawIntBits(r);
    }

    /** Double precision operation on raw bits. Unary operations use only b. */
    public static long dop(int kind, long a, long b)
    {
        double x = Double.longBitsToDouble(a);
        double y = Double.longBitsToDouble(b);
        switch (kind)
        {
            case F_MIN: return x < y ? a : b;
            case F_MAX: return x > y ? a : b;
            case F_SQRT:
                if (y != y) return b | 0x0008000000000000L;
                if (y < 0) return QNAN64;
                return Double.doubleToRawLongBits(Math.sqrt(y));
            default:
        }
        if (x != x) return a | 0x0008000000000000L;
        if (y != y) return b | 0x0008000000000000L;
        double r;
        switch (kind)
        {
            case F_ADD: r = x + y; break;
            case F_SUB: r = x - y; break;
            case F_MUL: r = x * y; break;
            default: r = x / y; break;
        }
        return r != r ? QNAN64 : Double.doubleToRawLongBits(r);
    }

    /** cmpps/cmppd predicate (0..7). Returns true when the lane compares true. */
    public static boolean cmpF(int pred, float x, float y)
    {
        switch (pred & 7)
        {
            case 0: return x == y;
            case 1: return x < y;
            case 2: return x <= y;
            case 3: return x != x || y != y;
            case 4: return !(x == y);
            case 5: return !(x < y);
            case 6: return !(x <= y);
            default: return !(x != x || y != y);
        }
    }

    public static boolean cmpD(int pred, double x, double y)
    {
        switch (pred & 7)
        {
            case 0: return x == y;
            case 1: return x < y;
            case 2: return x <= y;
            case 3: return x != x || y != y;
            case 4: return !(x == y);
            case 5: return !(x < y);
            case 6: return !(x <= y);
            default: return !(x != x || y != y);
        }
    }

    // ------------------------------------------------------------------ conversions

    /** Rounds a double to an int32 with the given rounding control (0 even, 1 down, 2 up, 3 truncate). */
    public static int roundInt(double v, int rc)
    {
        if (v != v)
            return INDEFINITE;
        double r;
        switch (rc)
        {
            case 0: r = Math.rint(v); break;
            case 1: r = Math.floor(v); break;
            case 2: r = Math.ceil(v); break;
            default: r = v < 0 ? Math.ceil(v) : Math.floor(v); break;
        }
        if (r >= 2147483648.0 || r < -2147483648.0)
            return INDEFINITE;
        return (int) r;
    }

    public static int f2i(int bits, int rc)
    {
        return roundInt((double) Float.intBitsToFloat(bits), rc);
    }

    public static int d2i(long bits, int rc)
    {
        return roundInt(Double.longBitsToDouble(bits), rc);
    }

    /** Single to double, keeping NaN payloads (signalling NaNs become quiet). */
    public static long f2d(int a)
    {
        float x = Float.intBitsToFloat(a);
        if (x != x)
            return ((long) (a >>> 31) << 63) | 0x7FF8000000000000L | ((long) (a & 0x7FFFFF) << 29);
        return Double.doubleToRawLongBits((double) x);
    }

    static float roundD2F(double x, int rc)
    {
        float f = (float) x;
        if (rc == 0 || f != f)
            return f;
        double fd = f;
        if (fd == x)
            return f;
        switch (rc)
        {
            case 1:
                if (fd > x) f = Math.nextDown(f);
                break;
            case 2:
                if (fd < x) f = Math.nextUp(f);
                break;
            default:
                if (Math.abs(fd) > Math.abs(x)) f = x > 0 ? Math.nextDown(f) : Math.nextUp(f);
                break;
        }
        return f;
    }

    /** Double to single with the given rounding control. */
    public static int d2f(long a, int rc)
    {
        double x = Double.longBitsToDouble(a);
        if (x != x)
            return ((int) (a >>> 63) << 31) | 0x7FC00000 | (int) ((a & 0xFFFFFFFFFFFFFL) >>> 29);
        return Float.floatToRawIntBits(roundD2F(x, rc));
    }

    public static int i2f(int v, int rc)
    {
        if (rc == 0)
            return Float.floatToRawIntBits((float) v);
        return Float.floatToRawIntBits(roundD2F((double) v, rc));
    }

    public static long i2d(int v)
    {
        return Double.doubleToRawLongBits((double) v);
    }
}
