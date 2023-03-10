/*
 * This file is part of ViaProxy - https://github.com/RaphiMC/ViaProxy
 * Copyright (C) 2023 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.raphimc.viaproxy.injection;

import net.lenni0451.classtransform.transformer.IBytecodeTransformer;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.Arrays;
import java.util.List;

public class Java17ToJava8 implements IBytecodeTransformer {

    private static final char STACK_ARG_CONSTANT = '\u0001';
    private static final char BSM_ARG_CONSTANT = '\u0002';

    private final IClassProvider classProvider;

    public Java17ToJava8(final IClassProvider classProvider) {
        this.classProvider = classProvider;
    }

    @Override
    public byte[] transform(String className, byte[] bytecode) {
        if (!className.startsWith("com.mojang")) return null;

        ClassNode classNode = ASMUtils.fromBytes(bytecode);
        if (classNode.version <= Opcodes.V1_8) return null;

        classNode.version = Opcodes.V1_8;
        this.makePublic(classNode);
        this.convertStringConcatFactory(classNode);
        this.convertCollections(classNode);
        this.removeRecords(classNode);

        return ASMUtils.toBytes(classNode, this.classProvider);
    }

    private void makePublic(final ClassNode classNode) {
        classNode.access = ASMUtils.setAccess(classNode.access, Opcodes.ACC_PUBLIC);
        for (MethodNode methodNode : classNode.methods) methodNode.access = ASMUtils.setAccess(methodNode.access, Opcodes.ACC_PUBLIC);
        for (FieldNode fieldNode : classNode.fields) fieldNode.access = ASMUtils.setAccess(fieldNode.access, Opcodes.ACC_PUBLIC);
    }

    private void convertStringConcatFactory(final ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.INVOKEDYNAMIC) {
                    InvokeDynamicInsnNode insn = (InvokeDynamicInsnNode) instruction;
                    if (insn.bsm.getOwner().equals("java/lang/invoke/StringConcatFactory") && insn.bsm.getName().equals("makeConcatWithConstants")) {
                        String pattern = (String) insn.bsmArgs[0];
                        Type[] stackArgs = Type.getArgumentTypes(insn.desc);
                        Object[] bsmArgs = Arrays.copyOfRange(insn.bsmArgs, 1, insn.bsmArgs.length);
                        int stackArgsCount = count(pattern, STACK_ARG_CONSTANT);
                        int bsmArgsCount = count(pattern, BSM_ARG_CONSTANT);

                        if (stackArgs.length != stackArgsCount) throw new IllegalStateException("Stack args count does not match");
                        if (bsmArgs.length != bsmArgsCount) throw new IllegalStateException("BSM args count does not match");

                        int freeVarIndex = ASMUtils.getFreeVarIndex(method);
                        int[] stackIndices = new int[stackArgsCount];
                        for (int i = 0; i < stackArgs.length; i++) {
                            stackIndices[i] = freeVarIndex;
                            freeVarIndex += stackArgs[i].getSize();
                        }
                        for (int i = stackIndices.length - 1; i >= 0; i--) {
                            method.instructions.insertBefore(insn, new VarInsnNode(stackArgs[i].getOpcode(Opcodes.ISTORE), stackIndices[i]));
                        }

                        InsnList converted = convertStringConcatFactory(pattern, stackArgs, stackIndices, bsmArgs);
                        method.instructions.insertBefore(insn, converted);
                        method.instructions.remove(insn);
                    }
                }
            }
        }
    }

    private void convertCollections(final ClassNode node) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode min = (MethodInsnNode) insn;

                    Type[] args = Type.getArgumentTypes(min.desc);
                    InsnList list = new InsnList();
                    if (min.owner.equals("java/util/Set") && min.name.equals("of")) {
                        if (args.length != 1 || args[0].getSort() != Type.ARRAY) {
                            int freeVarIndex = ASMUtils.getFreeVarIndex(method);

                            int argCount = args.length;
                            list.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
                            list.add(new InsnNode(Opcodes.DUP));
                            list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashSet", "<init>", "()V"));
                            list.add(new VarInsnNode(Opcodes.ASTORE, freeVarIndex));
                            for (int i = 0; i < argCount; i++) {
                                list.add(new VarInsnNode(Opcodes.ALOAD, freeVarIndex));
                                list.add(new InsnNode(Opcodes.SWAP));
                                list.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z"));
                                list.add(new InsnNode(Opcodes.POP));
                            }
                            list.add(new VarInsnNode(Opcodes.ALOAD, freeVarIndex));
                            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "unmodifiableSet", "(Ljava/util/Set;)Ljava/util/Set;"));
                        }
                    } else if (min.owner.equals("java/util/Map") && min.name.equals("of")) {
                        if (args.length == 0) {
                            list.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
                            list.add(new InsnNode(Opcodes.DUP));
                            list.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V"));
                        }
                    }
                    if (list.size() != 0) {
                        method.instructions.insertBefore(insn, list);
                        method.instructions.remove(insn);
                    }
                }
            }
        }
    }

    private void removeRecords(final ClassNode node) {
        if (node.superName.equals("java/lang/Record")) {
            node.access &= ~Opcodes.ACC_RECORD;
            node.superName = "java/lang/Object";

            List<MethodNode> constructors = ASMUtils.getMethodsFromCombi(node, "<init>");
            for (MethodNode method : constructors) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.equals("java/lang/Record")) {
                            min.owner = "java/lang/Object";
                            break;
                        }
                    }
                }
            }
        }
    }

    private int count(final String s, final char search) {
        char[] chars = s.toCharArray();
        int count = 0;
        for (char c : chars) {
            if (c == search) count++;
        }
        return count;
    }

    private InsnList convertStringConcatFactory(final String pattern, final Type[] stackArgs, final int[] stackIndices, final Object[] bsmArgs) {
        InsnList insns = new InsnList();
        char[] chars = pattern.toCharArray();
        int stackArgsIndex = 0;
        int bsmArgsIndex = 0;
        StringBuilder partBuilder = new StringBuilder();

        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V"));
        for (char c : chars) {
            if (c == STACK_ARG_CONSTANT) {
                if (partBuilder.length() != 0) {
                    insns.add(new LdcInsnNode(partBuilder.toString()));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
                    partBuilder = new StringBuilder();
                }

                Type stackArg = stackArgs[stackArgsIndex++];
                int stackIndex = stackIndices[stackArgsIndex - 1];
                if (stackArg.getSort() == Type.OBJECT) {
                    insns.add(new VarInsnNode(Opcodes.ALOAD, stackIndex));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"));
                } else if (stackArg.getSort() == Type.ARRAY) {
                    insns.add(new VarInsnNode(Opcodes.ALOAD, stackIndex));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString", "([Ljava/lang/Object;)Ljava/lang/String;"));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
                } else {
                    insns.add(new VarInsnNode(stackArg.getOpcode(Opcodes.ILOAD), stackIndex));
                    insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(" + stackArg.getDescriptor() + ")Ljava/lang/StringBuilder;"));
                }
            } else if (c == BSM_ARG_CONSTANT) {
                insns.add(new LdcInsnNode(bsmArgs[bsmArgsIndex++]));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;"));
            } else {
                partBuilder.append(c);
            }
        }
        if (partBuilder.length() != 0) {
            insns.add(new LdcInsnNode(partBuilder.toString()));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;"));
        }
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;"));
        return insns;
    }

}
