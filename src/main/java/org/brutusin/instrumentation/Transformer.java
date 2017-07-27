/*
 * Copyright 2017 brutusin.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.brutusin.instrumentation;

import org.brutusin.instrumentation.runtime.InstrumentationImpl;
import org.brutusin.instrumentation.spi.Plugin;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import org.brutusin.instrumentation.runtime.Callback;
import org.brutusin.instrumentation.utils.Helper;
import org.brutusin.instrumentation.utils.TreeInstructions;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 *
 * @author Ignacio del Valle Alles idelvall@brutusin.org
 */
public class Transformer implements ClassFileTransformer {

    private final Plugin[] plugins;
    private final InstrumentationImpl[] instrumentations;

    public Transformer(Plugin[] plugins, InstrumentationImpl[] instrumentations) {
        this.plugins = plugins;
        this.instrumentations = instrumentations;
        Callback.plugins = plugins;
    }

    public Plugin[] getPlugins() {
        return plugins;
    }

    @Override
    public byte[] transform(final ClassLoader loader,
            final String className, final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer)
            throws IllegalClassFormatException {

        LinkedList<Integer> pluginsInterceptingClass = getPluginsInterceptingClass(className, protectionDomain, loader);
        if (pluginsInterceptingClass == null || pluginsInterceptingClass.isEmpty()) {
            return classfileBuffer;
        }

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean transformed = transformMethods(cn, pluginsInterceptingClass);
        if (!transformed) {
            return classfileBuffer;
        } else {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
            cn.accept(cw);
            return cw.toByteArray();
        }
    }

    private LinkedList<Integer> getPluginsInterceptingClass(String className, ProtectionDomain protectionDomain, ClassLoader loader) {
        for (Plugin plugin : plugins) {
            if (classBelongsToPlugin(className, plugin)) {
                return null;
            }
        }
        if (className.startsWith("org/brutusin/instrumentation")) {
            return null;
        }
        LinkedList<Integer> ret = new LinkedList<Integer>();
        for (int i = 0; i < plugins.length; i++) {
            if (plugins[i].getFilter().instrumentClass(className, protectionDomain, loader)) {
                ret.add(i);
            }
            instrumentations[i].removeTransformedClass(className);
        }
        return ret;
    }

    private boolean transformMethods(ClassNode cn, LinkedList<Integer> pluginsInterceptingClass) {
        List<MethodNode> methods = cn.methods;
        boolean transformed = false;
        for (MethodNode mn : methods) {
            if (Helper.isAbstract(mn) || Helper.isNative(mn)) {
                continue;
            }
            LinkedList<Integer> pluginsToUse = new LinkedList<Integer>();
            for (Integer i : pluginsInterceptingClass) {
                if (plugins[i].getFilter().instrumentMethod(cn, mn)) {
                    pluginsToUse.add(i);
                    instrumentations[i].addTransformedClass(cn.name);
                }
            }
            if (!pluginsToUse.isEmpty()) {
                modifyMethod(cn, mn, pluginsToUse);
                transformed = true;
            }
        }
        return transformed;
    }

    private static boolean classBelongsToPlugin(String className, Plugin plugin) {
        return className.equals(plugin.getClass().getCanonicalName())
                || className.equals(plugin.getFilter().getClass().getCanonicalName())
                || className.equals(plugin.getListener().getClass().getCanonicalName());
    }

    private boolean modifyMethod(ClassNode cn, MethodNode mn, LinkedList<Integer> pluginsToUse) {
        int frameDataVarIndex = addTraceStart(cn, mn, pluginsToUse);
        addTraceReturn(mn, frameDataVarIndex, pluginsToUse);
//        addTraceThrow();
//        addTraceThrowablePassed();
        return true;
    }

    private int addTraceStart(ClassNode cn, MethodNode mn, LinkedList<Integer> pluginsToUse) {
        InsnList il = new InsnList();
        if (Helper.isStatic(mn) || mn.name.equals("<init>")) {
            il.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        il.add(new LdcInsnNode(cn.name));
        il.add(new LdcInsnNode(mn.name + mn.desc));
        addMethodParametersVariable(il, mn);
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "org/brutusin/instrumentation/runtime/FrameData", "getInstance",
                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Lorg/brutusin/instrumentation/runtime/FrameData;", false));

        il.add(new InsnNode(Opcodes.DUP));
        il.add(new VarInsnNode(Opcodes.ASTORE, mn.maxLocals));
        mn.maxLocals++;
        for (int i = 0; i < pluginsToUse.size(); i++) {
            Integer index = pluginsToUse.get(i);
            if (i < pluginsToUse.size() - 1) {
                il.add(new InsnNode(Opcodes.DUP));
            }
            il.add(TreeInstructions.getPushInstruction(index));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/brutusin/instrumentation/runtime/Callback", "onStart",
                    "(Lorg/brutusin/instrumentation/runtime/FrameData;I)Ljava/lang/Object;", false));

            il.add(new InsnNode(Opcodes.POP));

        }

        mn.instructions.insert(il);
        return mn.maxLocals - 1;
    }

    /**
     * Creates a the parameter object array reference on top of the operand
     * stack
     *
     * @param il
     * @param mn
     */
    private void addMethodParametersVariable(InsnList il, MethodNode mn) {
        Type[] methodArguments = Type.getArgumentTypes(mn.desc);
        if (methodArguments.length == 0) {
            il.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            il.add(TreeInstructions.getPushInstruction(methodArguments.length));
            il.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            int index = Helper.isStatic(mn) ? 0 : 1;
            for (int i = 0; i < methodArguments.length; i++) {
                il.add(new InsnNode(Opcodes.DUP));
                il.add(TreeInstructions.getPushInstruction(i));
                il.add(TreeInstructions.getLoadInst(methodArguments[i], index));
                MethodInsnNode mNode = TreeInstructions.getWrapperContructionInst(methodArguments[i]);
                if (mNode != null) {
                    il.add(mNode);
                }
                il.add(new InsnNode(Opcodes.AASTORE));
                index += methodArguments[i].getSize();
            }
        }
    }

    private void addTraceReturn(MethodNode mn, int frameDataVarIndex, LinkedList<Integer> pluginsToUse) {

        InsnList il = mn.instructions;
        Iterator<AbstractInsnNode> it = il.iterator();
        Type returnType = Type.getReturnType(mn.desc);

        while (it.hasNext()) {
            AbstractInsnNode abstractInsnNode = it.next();

            switch (abstractInsnNode.getOpcode()) {
                case Opcodes.RETURN:
                    il.insertBefore(abstractInsnNode, getVoidReturnTraceInstructions(frameDataVarIndex, pluginsToUse));
                    break;
                case Opcodes.IRETURN:
                case Opcodes.LRETURN:
                case Opcodes.FRETURN:
                case Opcodes.ARETURN:
                case Opcodes.DRETURN:
                    il.insertBefore(abstractInsnNode, getReturnTraceInstructions(returnType, frameDataVarIndex, pluginsToUse));
            }
        }
    }

    private InsnList getVoidReturnTraceInstructions(int frameDataVarIndex, LinkedList<Integer> pluginsToUse) {
        InsnList il = new InsnList();
        Iterator<Integer> descendingIterator = pluginsToUse.descendingIterator();
        while (descendingIterator.hasNext()) {
            Integer index = descendingIterator.next();
            il.add(new InsnNode(Opcodes.ACONST_NULL));
            il.add(new VarInsnNode(Opcodes.ALOAD, frameDataVarIndex));
            il.add(TreeInstructions.getPushInstruction(index));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/brutusin/instrumentation/runtime/Callback", "onFinishedReturn",
                    "(Ljava/lang/Object;Lorg/brutusin/instrumentation/runtime/FrameData;I)V", false));
        }

        return il;
    }

    private InsnList getReturnTraceInstructions(Type returnType, int frameDataVarIndex, LinkedList<Integer> pluginsToUse) {
        InsnList il = new InsnList();
        Iterator<Integer> descendingIterator = pluginsToUse.descendingIterator();
        while (descendingIterator.hasNext()) {
            Integer index = descendingIterator.next();
            if (returnType.getSize() == 1) {
                il.add(new InsnNode(Opcodes.DUP));
            } else {
                il.add(new InsnNode(Opcodes.DUP2));
            }
            MethodInsnNode mNode = TreeInstructions.getWrapperContructionInst(returnType);
            if (mNode != null) {
                il.add(mNode);
            }
            il.add(new VarInsnNode(Opcodes.ALOAD, frameDataVarIndex));
            il.add(TreeInstructions.getPushInstruction(index));
            il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "org/brutusin/instrumentation/runtime/Callback", "onFinishedReturn",
                    "(Ljava/lang/Object;Lorg/brutusin/instrumentation/runtime/FrameData;I)V", false));
        }
        return il;
    }
}
