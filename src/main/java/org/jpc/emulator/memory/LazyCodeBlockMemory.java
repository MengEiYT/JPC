package org.jpc.emulator.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.jpc.emulator.execution.Executable;
import org.jpc.emulator.execution.decoder.Instruction;
import org.jpc.emulator.execution.codeblock.*;
import org.jpc.emulator.processor.Processor;
import org.jpc.j2se.Option;

/**
 * <code>Memory</code> object with simple execute capabilities.  Uses a
 * {@link org.jpc.emulator.execution.codeblock.CodeBlockManager} instance to generate
 * {@link org.jpc.emulator.execution.codeblock.CodeBlock} objects which are then
 * stored in mirror arrays of the memory structure.
 * @author Chris Dennis
 * @author Rhys Newman
 * @author Ian Preston
 */
public class LazyCodeBlockMemory extends AbstractMemory
{
    public static final boolean LOG_DISAM_ADDRESSES = Option.log_disam_addresses.value();
    private CodeBlockManager codeBlockManager;
    private static final BlankCodeBlock PLACEHOLDER = new BlankCodeBlock();
    private RealModeCodeBlock[] realCodeBuffer;
    private ProtectedModeCodeBlock[] protectedCodeBuffer;
    private Virtual8086ModeCodeBlock[] virtual8086CodeBuffer;
    private static final int ALLOCATION_THRESHOLD = 10;
    private final int size;
    private byte[] buffer = null;
    private int nullReadCount = 0;
    private List<SpanningCodeBlock> spanning = new LinkedList();

    /**
     * Constructs an instance <code>size</code> bytes long.
     * @param size
     */
    public LazyCodeBlockMemory(int size, CodeBlockManager manager) {
        this.size = size;
        this.codeBlockManager = manager;
    }

    protected void constructCodeBlocksArray() {
        realCodeBuffer = new RealModeCodeBlock[(int) getSize()];
        protectedCodeBuffer = new ProtectedModeCodeBlock[(int) getSize()];
        virtual8086CodeBuffer = new Virtual8086ModeCodeBlock[(int) getSize()];
    }

    private void constructRealCodeBlocksArray() {
        realCodeBuffer = new RealModeCodeBlock[(int) getSize()];
    }

    private void constructVirtual8086CodeBlocksArray() {
        virtual8086CodeBuffer = new Virtual8086ModeCodeBlock[(int) getSize()];
    }

    private void constructProtectedCodeBlocksArray() {
        protectedCodeBuffer = new ProtectedModeCodeBlock[(int) getSize()];
    }

    public int executeProtected(Processor cpu, int offset) {
        int x86Count = 0;
        // JPC mod: every caller passes the instruction pointer it has just worked out, so use it. Asking the processor again
        // cost a second segment lookup (a virtual call) for every block.
        offset &= AddressSpace.BLOCK_MASK;
        ProtectedModeCodeBlock block = getProtectedModeCodeBlockAt(offset);
        try
        {
            try
            {
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
            catch (NullPointerException e)
            {
                if (LOG_DISAM_ADDRESSES)
                    System.out.printf("Disassembling PM from %08x with opsize=%s\n", cpu.getInstructionPointer(), cpu.cs.getDefaultSizeFlag());
                try {
                    block = codeBlockManager.getProtectedModeCodeBlockAt(this, offset, cpu.cs.getDefaultSizeFlag());
                }catch (SpanningDecodeException s)
                {
                    setProtectedCodeBlockAt(offset, (ProtectedModeCodeBlock) s.getBlock());
                    throw s;
                }

                setProtectedCodeBlockAt(offset, block);
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
        }
        catch (CodeBlockReplacementException e)
        {
            block = (ProtectedModeCodeBlock) e.getReplacement();
            protectedCodeBuffer[offset] = block;
            block.execute(cpu);
            x86Count += block.getX86Count();
        }

        return x86Count;
    }

    public int executeReal(Processor cpu, int offset) {
        int x86Count = 0;
        // JPC mod: see executeProtected
        offset &= AddressSpace.BLOCK_MASK;
        RealModeCodeBlock block = getRealModeCodeBlockAt(offset);
        try
        {
            try
            {
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
            catch (NullPointerException e)
            {
                if (LOG_DISAM_ADDRESSES)
                    System.out.printf("Disassembling RM from %08x\n", cpu.getInstructionPointer());
                try {
                    block = codeBlockManager.getRealModeCodeBlockAt(this, offset);
                } catch (SpanningDecodeException s)
                {
                    setRealCodeBlockAt(offset, (RealModeCodeBlock) s.getBlock());
                    throw s;
                }
                setRealCodeBlockAt(offset, block);
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
        }
        catch (CodeBlockReplacementException e)
        {
            block = (RealModeCodeBlock) e.getReplacement();
            realCodeBuffer[offset] = block;
            block.execute(cpu);
            x86Count += block.getX86Count();
        }

        return x86Count;
    }

    public int executeVirtual8086(Processor cpu, int offset) {
        int x86Count = 0;
        // JPC mod: see executeProtected
        offset &= AddressSpace.BLOCK_MASK;
        Virtual8086ModeCodeBlock block = getVirtual8086ModeCodeBlockAt(offset);
        try
        {
            try
            {
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
            catch (NullPointerException e)
            {
                if (LOG_DISAM_ADDRESSES)
                    System.out.printf("Disassembling VM86 from %08x\n", cpu.getInstructionPointer());
                try {
                    block = codeBlockManager.getVirtual8086ModeCodeBlockAt(this, offset);
                } catch (SpanningDecodeException s)
                {
                    setVirtual8086CodeBlockAt(offset, (Virtual8086ModeCodeBlock) s.getBlock());
                    throw s;
                }
                setVirtual8086CodeBlockAt(offset, block);
                block.execute(cpu);
                x86Count += block.getX86Count();
            }
        }
        catch (CodeBlockReplacementException e)
        {
            block = (Virtual8086ModeCodeBlock) e.getReplacement();
            virtual8086CodeBuffer[offset] = block;
            block.execute(cpu);
            x86Count += block.getX86Count();
        }

        return x86Count;
    }

    private RealModeCodeBlock getRealModeCodeBlockAt(int offset) {
        try {
            return realCodeBuffer[offset];
        } catch (NullPointerException e) {
            constructRealCodeBlocksArray();
            return realCodeBuffer[offset];
        }
    }

    private ProtectedModeCodeBlock getProtectedModeCodeBlockAt(int offset) {
        try {
            return protectedCodeBuffer[offset];
        } catch (NullPointerException e) {
            constructProtectedCodeBlocksArray();
            return protectedCodeBuffer[offset];
        }
    }

    private Virtual8086ModeCodeBlock getVirtual8086ModeCodeBlockAt(int offset) {
        try {
            return virtual8086CodeBuffer[offset];
        } catch (NullPointerException e) {
            constructVirtual8086CodeBlocksArray();
            return virtual8086CodeBuffer[offset];
        }
    }

    public void addSpanningBlock(SpanningCodeBlock b, int remainingLength)
    {
        spanning.add(b);
        // add markers up to the codeblock's length
        if (remainingLength > 4096)
            remainingLength = 4096;
        if (b instanceof RealModeCodeBlock)
        {
            if (realCodeBuffer == null) {
                allocateBuffer();
                realCodeBuffer = new RealModeCodeBlock[(int) getSize()];
            }
            for (int i=0; i < remainingLength; i++)
                if (realCodeBuffer[i] == null)
                    realCodeBuffer[i] = PLACEHOLDER;
        }
        else if (b instanceof ProtectedModeCodeBlock)
        {
            if (protectedCodeBuffer == null) {
                allocateBuffer();
                protectedCodeBuffer = new ProtectedModeCodeBlock[(int) getSize()];
            }
            for (int i=0; i < remainingLength; i++)
                if (protectedCodeBuffer[i] == null)
                    protectedCodeBuffer[i] = PLACEHOLDER;
        }
        else if (b instanceof Virtual8086ModeCodeBlock)
        {
            if (virtual8086CodeBuffer == null) {
                allocateBuffer();
                virtual8086CodeBuffer = new Virtual8086ModeCodeBlock[(int) getSize()];
            }
            for (int i=0; i < remainingLength; i++)
                if (virtual8086CodeBuffer[i] == null)
                    virtual8086CodeBuffer[i] = PLACEHOLDER;
        }
    }

    private void removeVirtual8086CodeBlockAt(int offset) {
        Virtual8086ModeCodeBlock b = virtual8086CodeBuffer[offset];
        if ((b == null) || (b == PLACEHOLDER)) {
            return;
        }

        virtual8086CodeBuffer[offset] = null;
        int len = b.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < virtual8086CodeBuffer.length); i++) {
            if (virtual8086CodeBuffer[i] == PLACEHOLDER) {
                virtual8086CodeBuffer[i] = null;
            }
        }

        for (int i = Math.min(offset + len, virtual8086CodeBuffer.length) - 1; i >= 0; i--) {
            if (virtual8086CodeBuffer[i] == null) {
                if (i < offset) {
                    break;
                } else {
                    continue;
                }
            }
            if (virtual8086CodeBuffer[i] == PLACEHOLDER) {
                continue;
            }

            Virtual8086ModeCodeBlock bb = virtual8086CodeBuffer[i];
            len = bb.getX86Length();

            for (int j = i + 1; (j < i + len) && (j < virtual8086CodeBuffer.length); j++) {
                if (virtual8086CodeBuffer[j] == null) {
                    virtual8086CodeBuffer[j] = PLACEHOLDER;
                }
            }
        }
    }

    private void removeProtectedCodeBlockAt(int offset) {
        ProtectedModeCodeBlock b = protectedCodeBuffer[offset];
        if ((b == null) || (b == PLACEHOLDER)) {
            return;
        }

        protectedCodeBuffer[offset] = null;
        int len = b.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < protectedCodeBuffer.length); i++) {
            if (protectedCodeBuffer[i] == PLACEHOLDER) {
                protectedCodeBuffer[i] = null;
            }
        }

        for (int i = Math.min(offset + len, protectedCodeBuffer.length) - 1; i >= 0; i--) {
            if (protectedCodeBuffer[i] == null) {
                if (i < offset) {
                    break;
                } else {
                    continue;
                }
            }
            if (protectedCodeBuffer[i] == PLACEHOLDER) {
                continue;
            }

            ProtectedModeCodeBlock bb = protectedCodeBuffer[i];
            len = bb.getX86Length();

            for (int j = i + 1; (j < i + len) && (j < protectedCodeBuffer.length); j++) {
                if (protectedCodeBuffer[j] == null) {
                    protectedCodeBuffer[j] = PLACEHOLDER;
                }
            }
        }
    }

    private void removeRealCodeBlockAt(int offset) {
        RealModeCodeBlock b = realCodeBuffer[offset];
        if ((b == null) || (b == PLACEHOLDER)) {
            return;
        }

        realCodeBuffer[offset] = null;
        int len = b.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < realCodeBuffer.length); i++) {
            if (realCodeBuffer[i] == PLACEHOLDER) {
                realCodeBuffer[i] = null;
            }
        }

        for (int i = Math.min(offset + len, realCodeBuffer.length) - 1; i >= 0; i--) {
            if (realCodeBuffer[i] == null) {
                if (i < offset) {
                    break;
                } else {
                    continue;
                }
            }
            if (realCodeBuffer[i] == PLACEHOLDER) {
                continue;
            }

            RealModeCodeBlock bb = realCodeBuffer[i];
            len = bb.getX86Length();

            for (int j = i + 1; (j < i + len) && (j < realCodeBuffer.length); j++) {
                if (realCodeBuffer[j] == null) {
                    realCodeBuffer[j] = PLACEHOLDER;
                }
            }
        }
    }

    private void setVirtual8086CodeBlockAt(int offset, Virtual8086ModeCodeBlock block) {
        removeVirtual8086CodeBlockAt(offset);
        if (block == null) {
            return;
        }

        virtual8086CodeBuffer[offset] = block;
        int len = block.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < virtual8086CodeBuffer.length); i++) {
            if (virtual8086CodeBuffer[i] == null) {
                virtual8086CodeBuffer[i] = PLACEHOLDER;
            }
        }
    }

    private void setProtectedCodeBlockAt(int offset, ProtectedModeCodeBlock block) {
        removeProtectedCodeBlockAt(offset);
        if (block == null) {
            return;
        }

        protectedCodeBuffer[offset] = block;
        int len = block.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < protectedCodeBuffer.length); i++) {
            if (protectedCodeBuffer[i] == null) {
                protectedCodeBuffer[i] = PLACEHOLDER;
            }
        }
    }

    private void setRealCodeBlockAt(int offset, RealModeCodeBlock block) {
        removeRealCodeBlockAt(offset);
        if (block == null) {
            return;
        }

        realCodeBuffer[offset] = block;
        int len = block.getX86Length();
        for (int i = offset + 1; (i < offset + len) && (i < realCodeBuffer.length); i++) {
            if (realCodeBuffer[i] == null) {
                realCodeBuffer[i] = PLACEHOLDER;
            }
        }
    }

    private void regionAltered(int start, int end) {
        if (realCodeBuffer != null) {
            for (int i = end; i >= 0; i--) {
                if (i == 0)
                {
                    for (SpanningCodeBlock b : spanning)
                        b.invalidate();
                }
                RealModeCodeBlock b = realCodeBuffer[i];
                if (b == null) {
                    if (i < start) {
                        break;
                    } else {
                        continue;
                    }
                }

                if (b == PLACEHOLDER) {
                    continue;
                }

                if (!b.handleMemoryRegionChange(start, end)) {
                    removeRealCodeBlockAt(i);
                }
            }
        }

        if (protectedCodeBuffer != null) {
            for (int i = end; i >= 0; i--) {
                if (i == 0)
                {
                    for (SpanningCodeBlock b : spanning)
                        b.invalidate();
                }
                ProtectedModeCodeBlock b = protectedCodeBuffer[i];
                if (b == null) {
                    if (i < start) {
                        break;
                    } else {
                        continue;
                    }
                }

                if (b == PLACEHOLDER) {
                    continue;
                }

                if (!b.handleMemoryRegionChange(start, end)) {
                    removeProtectedCodeBlockAt(i);
                }
            }
        }

        if (virtual8086CodeBuffer != null) {
            for (int i = end; i >= 0; i--) {
                if (i == 0)
                {
                    for (SpanningCodeBlock b : spanning)
                        b.invalidate();
                }
                Virtual8086ModeCodeBlock b = virtual8086CodeBuffer[i];
                if (b == null) {
                    if (i < start) {
                        break;
                    } else {
                        continue;
                    }
                }

                if (b == PLACEHOLDER) {
                    continue;
                }

                if (!b.handleMemoryRegionChange(start, end)) {
                    removeVirtual8086CodeBlockAt(i);
                }
            }
        }
    }

    public void clear() {
        realCodeBuffer = null;
        protectedCodeBuffer = null;
        virtual8086CodeBuffer = null;
        buffer = null;
    }

    public String toString() {
        return "LazyCodeBlockMemory[" + getSize() + "]";
    }

    private static class BlankCodeBlock implements RealModeCodeBlock, ProtectedModeCodeBlock, Virtual8086ModeCodeBlock {

        private static final RuntimeException executeException = new NullPointerException();

        public int getX86Length() {
            return 0;
        }

        public int getX86Count() {
            return 0;
        }

        public Executable.Branch execute(Processor cpu) {
            throw executeException;
        }

        public boolean handleMemoryRegionChange(int startAddress, int endAddress) {
            return false;
        }

        public String getDisplayString() {
            return "\n\n<<Blank Block>>\n\n";
        }

        public String toString() {
            return " -- Blank --\n";
        }

        public Instruction getInstructions()
        {
            return null;
        }
    }

    public ProtectedModeCodeBlock getProtectedBlock(int offset, boolean size) {
        if (protectedCodeBuffer == null) {
            allocateBuffer();
            protectedCodeBuffer = new ProtectedModeCodeBlock[(int) getSize()];
        }
        ProtectedModeCodeBlock block = protectedCodeBuffer[offset];
        if ((block != null) && (block != PLACEHOLDER)) {
            return block;
        }

        block = codeBlockManager.getProtectedModeCodeBlockAt(this, offset, size);
        setProtectedCodeBlockAt(offset, block);
        return block;
    }

    public Virtual8086ModeCodeBlock getVirtual8086Block(int offset) {
        if (virtual8086CodeBuffer == null) {
            allocateBuffer();
            virtual8086CodeBuffer = new Virtual8086ModeCodeBlock[(int) getSize()];
        }
        Virtual8086ModeCodeBlock block = virtual8086CodeBuffer[offset];
        if ((block != null) && (block != PLACEHOLDER)) {
            return block;
        }

        block = codeBlockManager.getVirtual8086ModeCodeBlockAt(this, offset);
        setVirtual8086CodeBlockAt(offset, block);
        return block;
    }

    public RealModeCodeBlock getRealBlock(int offset) {
        if (realCodeBuffer == null) {
            allocateBuffer();
            realCodeBuffer = new RealModeCodeBlock[(int) getSize()];
        }
        RealModeCodeBlock block = realCodeBuffer[offset];
        if ((block != null) && (block != PLACEHOLDER)) {
            return block;
        }

        block = codeBlockManager.getRealModeCodeBlockAt(this, offset);
        setRealCodeBlockAt(offset, block);
        return block;
    }

    //begin lazy memory methods
    private final void allocateBuffer() {
        if (buffer == null) {
            buffer = new byte[size];
        }
    }

    public void copyContentsIntoArray(int address, byte[] buf, int off, int len) {
        try {
            System.arraycopy(buffer, address, buf, off, len);
        } catch (NullPointerException e) {
            if (++nullReadCount == ALLOCATION_THRESHOLD) {
                allocateBuffer();
                System.arraycopy(buffer, address, buf, off, len);
            } else {
                Arrays.fill(buf, off, off + len, (byte) 0);
            }
        } catch (ArrayIndexOutOfBoundsException e)
        {
            System.out.println("Array bounds exception reading from lazycodeblockmemory: address=0x" + Integer.toHexString(address) + ", off="+Integer.toHexString(off)  + ", len="+len);
        }
    }

    public void loadInitialContents(int address, byte[] buf, int off, int len) {
        try {
            System.arraycopy(buf, off, buffer, address, len);
        } catch (NullPointerException e) {
            allocateBuffer();
            System.arraycopy(buf, off, buffer, address, len);
        }
    }

    public void copyArrayIntoContents(int address, byte[] buf, int off, int len) {
        try {
            System.arraycopy(buf, off, buffer, address, len);
        } catch (NullPointerException e) {
            allocateBuffer();
            System.arraycopy(buf, off, buffer, address, len);
        }
        regionAltered(address, address + len - 1);
    }

    public long getSize() {
        return size;
    }

    public boolean isAllocated() {
        return (buffer != null);
    }

    public byte getByte(int offset) {
        try {
            return buffer[offset];
        } catch (NullPointerException e) {
            if (++nullReadCount == ALLOCATION_THRESHOLD) {
                allocateBuffer();
                return buffer[offset];
            } else {
                return 0;
            }
        }
    }

    public void setByte(int offset, byte data) {
        if (getByte(offset) == data) {
            return;
        }
        try {
            buffer[offset] = data;
        } catch (NullPointerException e) {
            allocateBuffer();
            buffer[offset] = data;
        }
        regionAltered(offset, offset);
    }

    // JPC mod: the 16 and 32 bit accesses below use one little-endian load or store each instead of two or four single
    // byte ones (x86 is little endian). An access that runs past the end of the page still throws
    // ArrayIndexOutOfBoundsException, now before anything is read or written, and a missing buffer still gives a
    // NullPointerException, which is what the callers rely on.
    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    public short getWord(int offset) {
        try {
            return (short) SHORT_LE.get(buffer, offset);
        } catch (NullPointerException e) {
            if (++nullReadCount == ALLOCATION_THRESHOLD) {
                allocateBuffer();
                return (short) SHORT_LE.get(buffer, offset);
            } else {
                return 0;
            }
        }
    }

    public int getDoubleWord(int offset) {
        try {
            return (int) INT_LE.get(buffer, offset);
        } catch (NullPointerException e) {
            if (++nullReadCount == ALLOCATION_THRESHOLD) {
                allocateBuffer();
                return (int) INT_LE.get(buffer, offset);
            } else {
                return 0;
            }
        }
    }

    public void setWord(int offset, short data) {
        if (getWord(offset) == data) {
            return;
        }
        try {
            SHORT_LE.set(buffer, offset, data);
        } catch (NullPointerException e) {
            allocateBuffer();
            SHORT_LE.set(buffer, offset, data);
        }
        // JPC mod: the changed range is offset .. offset+1. JPC passed it after counting offset up, so a block that
        // only overlapped the first bytes of the write was not thrown away and kept running the old code.
        regionAltered(offset, offset + 1);
    }

    public void setDoubleWord(int offset, int data) {
        if (getDoubleWord(offset) == data) {
            return;
        }
        try {
            INT_LE.set(buffer, offset, data);
        } catch (NullPointerException e) {
            allocateBuffer();
            INT_LE.set(buffer, offset, data);
        }
        regionAltered(offset, offset + 3); // see setWord
    }
}
