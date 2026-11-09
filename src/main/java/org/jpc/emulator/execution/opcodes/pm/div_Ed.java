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

package org.jpc.emulator.execution.opcodes.pm;

import org.jpc.emulator.execution.*;
import org.jpc.emulator.execution.decoder.*;
import org.jpc.emulator.processor.*;
import org.jpc.emulator.processor.fpu64.*;
import static org.jpc.emulator.processor.Processor.*;

public class div_Ed extends Executable
{
    final int op1Index;

    public div_Ed(int blockStart, int eip, int prefices, PeekableInputStream input)
    {
        super(blockStart, eip);
        int modrm = input.readU8();
        op1Index = Modrm.Ed(modrm);
    }

    public Branch execute(Processor cpu)
    {
        Reg op1 = cpu.regs[op1Index];
        int divisor = op1.get32();
        if (divisor == 0)
            throw ProcessorException.DIVIDE_ERROR;
        // JPC mod: EDX:EAX is an UNSIGNED 64-bit number. The quotient fits in 32 bits exactly when EDX < divisor (unsigned).
        // The old code divided it as a signed long, so a dividend of 2^63 or more (EDX >= 0x80000000) raised a false
        // divide error (a Linux 6.x kernel crashed in do_div with EDX=89ecd6d9, ECX=d06ae4cd).
        int high = cpu.r_edx.get32();
        if (Integer.compareUnsigned(high, divisor) >= 0)
            throw ProcessorException.DIVIDE_ERROR;
        long ldiv = ((0xffffffffL & high) << 32) | (0xffffffffL & cpu.r_eax.get32());
        long d = 0xffffffffL & divisor;
        cpu.r_eax.set32((int) Long.divideUnsigned(ldiv, d));
        cpu.r_edx.set32((int) Long.remainderUnsigned(ldiv, d));
        return Branch.None;
    }

    public boolean isBranch()
    {
        return false;
    }

    public String toString()
    {
        return this.getClass().getName();
    }
}