/*
    JPC: An x86 PC Hardware Emulator for a pure Java Virtual Machine

    Copyright (C) 2012-2013 Ian Preston

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License version 2 as published by
    the Free Software Foundation.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along
    with this program; if not, write to the Free Software Foundation, Inc.,
    51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

    Details (including contact information) can be found at:

    jpc.sourceforge.net
    or the developer website
    sourceforge.net/projects/jpc/

    End of licence header
*/

package org.jpc.emulator.memory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;

/**
 * A direct-mapped translation cache with 1024 entries.
 *
 * <p>The JPC mod rewrote the storage: one array per field instead of an object per entry (no allocation when an entry is
 * filled, and a hit reads two small arrays), no boxed {@code HashSet<Integer>} of non-global pages (the profile of a
 * booting KolibriOS spent most of its time there), and flushing clears the arrays instead of allocating new ones.
 */
public class FastTLB extends TLB
{
    private static final byte FOUR_M = (byte) 0x01;
    private static final byte FOUR_K = (byte) 0x00;

    private static final int TLB_SIZE = 1024;
    private static final int TLB_MASK = (TLB_SIZE-1) << 12;
    private static int TLBIndexOf(int addr) {
        return (addr & TLB_MASK) >>> 12;
    }

    // access bits of an entry; an entry with no bits set is empty
    private static final byte SYS_READ_OK     = 0x01;
    private static final byte USER_READ_OK    = 0x02;
    private static final byte SYS_WRITE_OK    = 0x04;
    private static final byte USER_WRITE_OK   = 0x08;

    private static final int NON_GLOBAL_LIST_SIZE = 4096;

    private final int[] tag = new int[TLB_SIZE];          // linear address of the cached page (low 12 bits are zero)
    private final Memory[] block = new Memory[TLB_SIZE];  // the memory the page translates to
    private final byte[] access = new byte[TLB_SIZE];     // which kinds of access are allowed

    /** Per 4 MB region of the linear address space: 1 if a 4 MB page was cached there since the last full flush. */
    private final byte[] largeRegion = new byte[1024];

    /**
     * Pages (page numbers) that were cached as non-global while global pages were enabled, so that a CR3 write can
     * drop exactly those. May hold duplicates and pages that are gone from the cache; dropping a page that is no
     * longer there, or one too many, is harmless because this is only a cache.
     */
    private final int[] nonGlobalList = new int[NON_GLOBAL_LIST_SIZE];
    private int nonGlobalCount;

    private boolean globalPagesEnabled;

    public FastTLB()
    {
    }

    @Override
    public void saveState(DataOutput output) throws IOException {
        output.writeInt(largeRegion.length);
        output.write(largeRegion);
    }

    @Override
    public void loadState(DataInput input) throws IOException {
        int len = input.readInt();
        byte[] regions = new byte[len];
        input.readFully(regions, 0, len);
        flush();
        System.arraycopy(regions, 0, largeRegion, 0, Math.min(len, largeRegion.length));
    }

    @Override
    public void setSupervisor(boolean isSupervisor) {

    }

    @Override
    public void setWriteProtectPages(boolean value) {

    }

    @Override
    public void flush() {
        Arrays.fill(access, (byte) 0);
        Arrays.fill(block, null);
        Arrays.fill(largeRegion, FOUR_K);
        nonGlobalCount = 0;
    }

    @Override
    public void flushNonGlobal() {
        if (globalPagesEnabled) {
            // JPC mod: the stored values are page numbers; the old code compared them with a full linear address
            // (never equal), so with CR4.PGE set a CR3 write left every user translation in the TLB.
            dropNonGlobal();
        } else
            flush();
    }

    private void dropNonGlobal() {
        for (int i = 0; i < nonGlobalCount; i++) {
            int addr = nonGlobalList[i] << AddressSpace.INDEX_SHIFT;
            int index = TLBIndexOf(addr);
            if (access[index] != 0 && ((tag[index] ^ addr) & AddressSpace.INDEX_MASK) == 0) {
                access[index] = 0;
                block[index] = null;
            }
        }
        nonGlobalCount = 0;
    }

    @Override
    public void setGlobalPages(boolean enabled) {
        globalPagesEnabled = enabled;
        nonGlobalCount = 0;   // the caller flushes the whole TLB right after changing this
    }

    @Override
    public void addNonGlobalPage(int addr) {
        // Without global pages every CR3 write flushes everything, so there is nothing to remember.
        if (!globalPagesEnabled)
            return;
        if (nonGlobalCount == NON_GLOBAL_LIST_SIZE)
            dropNonGlobal();          // list full: drop the non-global pages early (they can be translated again)
        nonGlobalList[nonGlobalCount++] = addr >>> AddressSpace.INDEX_SHIFT;
    }

    @Override
    public boolean globalPagesEnabled() {
        return globalPagesEnabled;
    }

    @Override
    public Memory getReadMemoryBlockAt(boolean isSupervisor, int addr) {
        int index = TLBIndexOf(addr);
        if (((tag[index] ^ addr) & AddressSpace.INDEX_MASK) == 0 && (access[index] & (isSupervisor ? SYS_READ_OK : USER_READ_OK)) != 0)
            return block[index];
        return null;
    }

    @Override
    public void setReadMemoryBlockAt(boolean isSupervisor, int addr, Memory m) {
        fill(addr, m, isSupervisor ? SYS_READ_OK : (SYS_READ_OK | USER_READ_OK));
    }

    @Override
    public Memory getWriteMemoryBlockAt(boolean isSupervisor, int addr) {
        int index = TLBIndexOf(addr);
        if (((tag[index] ^ addr) & AddressSpace.INDEX_MASK) == 0 && (access[index] & (isSupervisor ? SYS_WRITE_OK : USER_WRITE_OK)) != 0)
            return block[index];
        return null;
    }

    @Override
    public void setWriteMemoryBlockAt(boolean isSupervisor, int addr, Memory m) {
        fill(addr, m, isSupervisor ? SYS_WRITE_OK : (SYS_WRITE_OK | USER_WRITE_OK));
    }

    // JPC mod: a new translation for the same linear page (copy-on-write, remap) must replace the entry; only a
    // permission upgrade for the very same physical block may reuse it. Keeping the old block sent writes of
    // forked processes to the page both of them were meant to have copied.
    private void fill(int addr, Memory m, int bits) {
        int index = TLBIndexOf(addr);
        if (access[index] == 0 || ((tag[index] ^ addr) & AddressSpace.INDEX_MASK) != 0 || block[index] != m) {
            tag[index] = addr & AddressSpace.INDEX_MASK;
            block[index] = m;
            access[index] = (byte) bits;
        } else
            access[index] |= (byte) bits;
    }

    @Override
    protected void setPageSize(int addr, byte type) {
        // only the "a 4 MB page lives here" fact is kept, per 4 MB region (it is only used by INVLPG)
        if (type == FOUR_M)
            largeRegion[addr >>> 22] = FOUR_M;
    }

    @Override
    protected void replaceBlocks(Memory oldBlock, Memory newBlock) {
        System.out.println("Replace blocks used!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    @Override
    public void invalidateTLBEntry(int addr) {
        if (largeRegion[addr >>> 22] == FOUR_K) {
            drop(addr);
        } else {
            // a 4 MB page may be cached one 4 KB piece at a time: drop every piece
            int base = addr & 0xFFC00000;
            for (int i = 0; i < 1024; i++)
                drop(base + (i << AddressSpace.INDEX_SHIFT));
        }
    }

    private void drop(int addr) {
        int index = TLBIndexOf(addr);
        if (((tag[index] ^ addr) & AddressSpace.INDEX_MASK) == 0) {
            access[index] = 0;
            block[index] = null;
        }
    }
}
