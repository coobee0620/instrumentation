/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.brutusin.instrumentation;

import java.security.ProtectionDomain;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 *
 * @author ignacio
 */
public interface Filter extends Initializable {
    public boolean instrumentClass(String className, ProtectionDomain protectionDomain, ClassLoader cl);
    public boolean instrumentMethod(ClassNode classNode, MethodNode mn);
}
