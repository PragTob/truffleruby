/*
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.platform;

import jnr.ffi.Runtime;
import jnr.ffi.provider.MemoryManager;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

public class Pointer {

    public static Pointer malloc(long size) {
        return new Pointer(UNSAFE.allocateMemory(size));
    }

    public static Pointer mallocAutorelease(MemoryManager memoryManager, int size) {
        return new Pointer(memoryManager.allocateDirect(size));
    }

    private final jnr.ffi.Pointer pointer;

    public Pointer(long address) {
        this(Runtime.getSystemRuntime().getMemoryManager().newPointer(address));
    }

    public Pointer(jnr.ffi.Pointer pointer) {
        this.pointer = pointer;
    }

    public Pointer add(long offset) {
        return new Pointer(pointer.address() + offset);
    }

    public void put(int i, byte[] bytes, int i1, int length) {
        pointer.put(i, bytes, i1, length);
    }

    public void putByte(int length, byte b) {
        pointer.putByte(length, b);
    }

    public byte getByte(int index) {
        return pointer.getByte(index);
    }

    public void get(int from, byte[] buffer, int bufferPos, int i) {
        pointer.get(from, buffer, bufferPos, i);
    }

    public void putLong(long value) {
        pointer.putLong(0, value);
    }

    public void free() {
        UNSAFE.freeMemory(pointer.address());
    }

    public long getAddress() {
        return pointer.address();
    }

    public jnr.ffi.Pointer getPointer() {
        return pointer;
    }

    @SuppressWarnings("restriction")
    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    private static final Unsafe UNSAFE = getUnsafe();
}