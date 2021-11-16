/*
 * Copyright Amazon.com Inc. or its affiliates. All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test TestOnSpinWaitAArch64
 * @summary Checks that java.lang.Thread.onSpinWait is intrinsified with instructions specified with '-XX:OnSpinWaitInst' and '-XX:OnSpinWaitInstCount'
 * @bug 8186670
 * @library /test/lib
 *
 * @requires vm.flagless
 * @requires os.arch=="aarch64"
 *
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c2 nop 7
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c2 isb 3
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c2 yield 1
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c2 counter 14
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c1 nop 7
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c1 isb 3
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c1 yield
 * @run driver compiler.onSpinWait.TestOnSpinWaitAArch64 c1 counter 54
 */

package compiler.onSpinWait;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.Asserts;

public class TestOnSpinWaitAArch64 {
    public static void main(String[] args) throws Exception {
        String compiler = args[0];
        String spinWaitInst = args[1];
        String spinWaitInstValue = (args.length == 3) ? args[2] : "1";
        ArrayList<String> command = new ArrayList<String>();
        command.add("-XX:+IgnoreUnrecognizedVMOptions");
        command.add("-showversion");
        command.add("-XX:-BackgroundCompilation");
        command.add("-XX:+UnlockDiagnosticVMOptions");
        command.add("-XX:+PrintAssembly");
        if (compiler.equals("c2")) {
            command.add("-XX:-TieredCompilation");
        } else if (compiler.equals("c1")) {
            command.add("-XX:+TieredCompilation");
            command.add("-XX:TieredStopAtLevel=1");
        } else {
            throw new RuntimeException("Unknown compiler: " + compiler);
        }
        command.add("-Xbatch");
        command.add("-XX:OnSpinWaitInst=" + spinWaitInst);
        if ("counter".equals(spinWaitInst)) {
            command.add("-XX:OnSpinWaitCounterDelay=" + spinWaitInstValue);
        } else {
            command.add("-XX:OnSpinWaitInstCount=" + spinWaitInstValue);
        }
        command.add("-XX:CompileCommand=compileonly," + Launcher.class.getName() + "::" + "test");
        command.add(Launcher.class.getName());

        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(command);

        OutputAnalyzer analyzer = new OutputAnalyzer(pb.start());

        analyzer.shouldHaveExitValue(0);

        System.out.println(analyzer.getOutput());

        if (!"counter".equals(spinWaitInst)) {
            checkOutput(analyzer, spinWaitInst, Integer.parseInt(spinWaitInstValue));
        } else {
            checkCounterOutput(analyzer, Integer.parseInt(spinWaitInstValue));
        }
    }

    private static String getSpinWaitInstHex(String spinWaitInst) {
      if ("nop".equals(spinWaitInst)) {
          return "1f20 03d5";
      } else if ("isb".equals(spinWaitInst)) {
          return "df3f 03d5";
      } else if ("yield".equals(spinWaitInst)) {
          return "3f20 03d5";
      } else {
          throw new RuntimeException("Unknown spin wait instruction: " + spinWaitInst);
      }
    }

    private static void addInstrs(String line, ArrayList<String> instrs) {
        for (String instr : line.split("\\|")) {
            instrs.add(instr.trim());
        }
    }

    // The expected output of PrintAssembly for example for a spin wait with three NOPs:
    //
    // # {method} {0x0000ffff6ac00370} 'test' '()V' in 'compiler/onSpinWait/TestOnSpinWaitAArch64$Launcher'
    // #           [sp+0x40]  (sp of caller)
    // 0x0000ffff9d557680: 1f20 03d5 | e953 40d1 | 3f01 00f9 | ff03 01d1 | fd7b 03a9 | 1f20 03d5 | 1f20 03d5
    //
    // 0x0000ffff9d5576ac: ;*invokestatic onSpinWait {reexecute=0 rethrow=0 return_oop=0}
    //                     ; - compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@0 (line 161)
    // 0x0000ffff9d5576ac: 1f20 03d5 | fd7b 43a9 | ff03 0191
    //
    // The checkOutput method adds hex instructions before 'invokestatic onSpinWait' and from the line after
    // it to a list. The list is traversed from the end to count spin wait instructions.
    //
    // If JVM finds the hsdis library the output is like:
    //
    // # {method} {0x0000ffff63000370} 'test' '()V' in 'compiler/onSpinWait/TestOnSpinWaitAArch64$Launcher'
    // #           [sp+0x20]  (sp of caller)
    // 0x0000ffffa409da80:   nop
    // 0x0000ffffa409da84:   sub sp, sp, #0x20
    // 0x0000ffffa409da88:   stp x29, x30, [sp, #16]         ;*synchronization entry
    //                                                       ; - compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@-1 (line 187)
    // 0x0000ffffa409da8c:   nop
    // 0x0000ffffa409da90:   nop
    // 0x0000ffffa409da94:   nop
    // 0x0000ffffa409da98:   nop
    // 0x0000ffffa409da9c:   nop
    // 0x0000ffffa409daa0:   nop
    // 0x0000ffffa409daa4:   nop                                 ;*invokestatic onSpinWait {reexecute=0 rethrow=0 return_oop=0}
    //                                                           ; - compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@0 (line 187)
    private static void checkOutput(OutputAnalyzer output, String spinWaitInst, int spinWaitInstCount) {
        Iterator<String> iter = output.asLines().listIterator();

        String match = skipTo(iter, "'test' '()V' in 'compiler/onSpinWait/TestOnSpinWaitAArch64$Launcher'");
        if (match == null) {
            throw new RuntimeException("Missing compiler output for the method compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test");
        }

        ArrayList<String> instrs = new ArrayList<String>();
        String line = null;
        boolean hasHexInstInOutput = false;
        while (iter.hasNext()) {
            line = iter.next();
            if (line.contains("*invokestatic onSpinWait")) {
                break;
            }
            if (!hasHexInstInOutput) {
                hasHexInstInOutput = line.contains("|");
            }
            if (line.contains("0x") && !line.contains(";")) {
                addInstrs(line, instrs);
            }
        }

        if (!iter.hasNext() || !iter.next().contains("- compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@0") || !iter.hasNext()) {
            throw new RuntimeException("Missing compiler output for Thread.onSpinWait intrinsic");
        }

        String strToSearch = null;
        if (!hasHexInstInOutput) {
            instrs.add(line.split(";")[0].trim());
            strToSearch = spinWaitInst;
        } else {
            line = iter.next();
            if (!line.contains("0x") || line.contains(";")) {
                throw new RuntimeException("Expected hex instructions");
            }

            addInstrs(line, instrs);
            strToSearch = getSpinWaitInstHex(spinWaitInst);
        }

        int foundInstCount = 0;

        ListIterator<String> instrReverseIter = instrs.listIterator(instrs.size());
        while (instrReverseIter.hasPrevious()) {
            if (instrReverseIter.previous().endsWith(strToSearch)) {
                foundInstCount = 1;
                break;
            }
        }

        while (instrReverseIter.hasPrevious()) {
            if (!instrReverseIter.previous().endsWith(strToSearch)) {
                break;
            }
            ++foundInstCount;
        }

        if (foundInstCount != spinWaitInstCount) {
            throw new RuntimeException("Wrong instruction " + strToSearch + " count " + foundInstCount + "!\n  -- expecting " + spinWaitInstCount);
        }
    }

    // The expected output of onSpinWait with a loop count of 14:
    //
    // compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@-1 (line 223)
    // 0x0000ffff78e31bf0:   mrs     x8, cntvct_el0
    // 0x0000ffff78e31bf4:   add     x8, x8, #0xe
    // 0x0000ffff78e31bf8:   yield
    // 0x0000ffff78e31bfc:   mrs     x9, cntvct_el0
    // 0x0000ffff78e31c00:   cmp     x9, x8
    // 0x0000ffff78e31c04:   b.lt    0x0000ffff78e31bf8          ;*invokestatic onSpinWait {reexecute=0 rethrow=0 return_oop=0}
    //
    // Recognises a run of several different instructions, rather than a single
    // instruction repeated.
    // If the JVM doesn't find the hsdis library, the output is like:
    //
    //  0x0000ffff9432c7ec: ;*synchronization entry
    // ; - compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@-1 (line 223)
    // 0x0000ffff9432c7ec: 0001 3fd6 | 48e0 3bd5 | 0839 0091 | 3f20 03d5 | 49e0 3bd5 | 3f01 08eb
    // 0x0000ffff9432c804: ;*invokestatic onSpinWait {reexecute=0 rethrow=0 return_oop=0}
    // ; - compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test@0 (line 223)
    // 0x0000ffff9432c804: abff ff54 | fd7b 41a9 | ff83 0091
    //
    private static void checkCounterOutput(OutputAnalyzer output, int spinWaitCounterDelay) {
        Iterator<String> iter = output.asLines().listIterator();

        // Check we are passed a value that can be encoded in an immediate.
        Asserts.assertTrue(spinWaitCounterDelay >=0);
        Asserts.assertTrue(spinWaitCounterDelay <= (1 << 12));

        // Generate the add immediate instruction, with the immediate encoded.
        // add x8, x8, #spinWaitCounterDelay
        int addImmediateInsn = (0x91000108 | spinWaitCounterDelay << 10);

        // Find the start of the method.
        String next = skipTo(iter, "compiler/onSpinWait/TestOnSpinWaitAArch64$Launcher");

        if (next == null) {
            throw new RuntimeException("Missing compiler output for the method compiler.onSpinWait.TestOnSpinWaitAArch64$Launcher::test");
        }

        List<String> lines = new ArrayList<String>();
        boolean hasHexInstInOutput = false;

        Pattern addressPattern = Pattern.compile("^0x[0-9a-f]+: ");

        while(iter.hasNext()) {
            next = iter.next();
            // Replace tabs with spaces, reduce runs of spaces to one character and remove padding.
            next = next.replace('\t', ' ').replaceAll(" +"," ").trim().toLowerCase();

            // Marks end of the body.
            if (next.contains("[Exception Handler]") || next.contains("[/Disassembly]")) {
                break;
            }

            // "|" indicates hex output - no hsdis.so .
            if (next.contains("|")) hasHexInstInOutput = true;

            // Remove the instruction address at the start, if present.
            Matcher match = addressPattern.matcher(next);
            if (match.lookingAt()) {
                next = next.substring(match.end());
            }

            // Ignore comments.
            if (next.startsWith(";")) continue;

            // Skip empty lines.
            if (next.isEmpty()) continue;

            if (hasHexInstInOutput) {
                lines.addAll(Arrays.asList(next.split("\\|")));
            } else {
                lines.add(next);
            }
        }

//        if (true) { throw new RuntimeException("SRDM:" + lines);}

        iter = lines.iterator();

        if (hasHexInstInOutput) {
            next = skipTo(iter,"48e0 3bd5");

            match(next, "48e0 3bd5"); // MRS x8, CNTVCT_EL0
            next = iter.next();

            // ADD x8, x8, #spinWaitCounterDelay
            int addInsn = instructionStringToInt(next);
            if (addInsn != addImmediateInsn) {
                throw new RuntimeException("Add instruction mismatch '" +
                            Integer.toHexString(addInsn) + "' should be '"+
                            Integer.toHexString(addImmediateInsn) + "'");
            }

            next = iter.next();
            match(next, "3f20 03d5"); // YIELD
            next = iter.next();
            match(next, "49e0 3bd5"); // MRS x9, CNTVCT_EL0
            next = iter.next();
            match(next, "3f01 08eb"); // CMP x9, x8
            next = iter.next();
            match(next, "abff ff54"); // B.LT {pc}-0xc
        } else {
            next = skipTo(iter,"mrs x8, cntvct_el0");
            match(next, "mrs x8, cntvct_el0");
            next = iter.next();
            match(next, "add x8, x8, #0x" + Integer.toHexString(spinWaitCounterDelay));
            next = iter.next();
            match(next, "yield");
            next = iter.next();
            match(next, "mrs x9, cntvct_el0");
            next = iter.next();
            match(next, "cmp x9, x8");
            next = iter.next();
            match(next, "b.lt");
        }
    }

    // Convert string of the form "3d20 03d5" into an integer like "0xd503203d".
    private static int instructionStringToInt(String hex) {
        return  Integer.reverseBytes(Integer.parseInt(hex.replaceAll(" ",""), 16));
    }

    // Check string a matches string b and throw exception if not.
    private static void match(String a, String b) {
        if (!a.contains(b)) {
            throw new RuntimeException("mismatch '" + a + "' should contain '" + b + "'");
        }
    }

    private static String skipTo(Iterator<String> iter, String substring) {
        while (iter.hasNext()) {
            String nextLine = iter.next();
            if (nextLine.contains(substring)) {
                return nextLine;
            }
        }
        return null;
    }

    static class Launcher {
        public static void main(final String[] args) throws Exception {
            int end = 20_000;

            for (int i=0; i < end; i++) {
                test();
            }
        }
        static void test() {
            java.lang.Thread.onSpinWait();
        }
    }
}
