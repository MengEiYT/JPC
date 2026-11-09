package org.jpc.emulator.execution.opcodes.pm;

import org.jpc.emulator.execution.*;
import org.jpc.emulator.execution.decoder.*;
import org.jpc.emulator.processor.*;
import org.jpc.emulator.processor.fpu64.*;
import static org.jpc.emulator.processor.Processor.*;

public class UnimplementedOpcode extends Executable
{
    final int blockLength;
    final int instructionLength;
    String error;
    static int reported;

    public UnimplementedOpcode(int blockStart, int eip, int prefices, PeekableInputStream input)
    {
        super(blockStart, eip);
        instructionLength = (int)input.getAddress()-eip;
        blockLength = (int)input.getAddress()-blockStart;
        input.seek(-instructionLength);
        Instruction in = Disassembler.disassemble(input, Prefices.isAddr16(prefices) ? 16 : 32);
        error = in.toString() + " ("+in.getGeneralClassName(false, false)+"), x86 byte = " + Disassembler.getRawBytes(input, eip-blockStart);
    }

    public Branch execute(Processor cpu)
    {
        // JPC mod: the guest gets "invalid opcode" instead of the emulator stopping
        if (reported++ < 10)
            java.util.logging.Logger.getLogger("org.jpc").warning("Unimplemented opcode, guest gets #UD: " + error);
        throw ProcessorException.UNDEFINED;
    }

    public boolean isBranch()
    {
        return true;
    }

    public String toString()
    {
        return this.getClass().getName();
    }
}