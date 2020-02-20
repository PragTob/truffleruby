/*
 * Copyright (c) 2014, 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.array;

import org.truffleruby.Layouts;
import org.truffleruby.RubyContext;
import org.truffleruby.core.array.library.ArrayStoreLibrary;
import org.truffleruby.core.array.library.ArrayStoreLibrary.ArrayAllocator;
import org.truffleruby.core.array.ArrayBuilderNodeFactory.AppendArrayNodeGen;
import org.truffleruby.core.array.ArrayBuilderNodeFactory.AppendOneNodeGen;
import org.truffleruby.language.RubyContextNode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;

/** Builds a new Array and learns its storage strategy and its expected length. The storage strategy is generalized as
 * needed and the expected length is increased until all elements fit.
 * <p>
 * Append nodes handle only one strategy, but must still return a valid storage when:
 *
 * <li>The element(s) added do not match the strategy.
 * <li>The being-built storage no longer matches the strategy, due to the node having been replaced by another thread or
 * by another usage (e.g. recursive) of this ArrayBuilderNode. */
public abstract class ArrayBuilderNode extends RubyContextNode {

    public static class BuilderState {
        protected int capacity;
        protected int nextIndex = 0;
        protected Object store;

        private BuilderState(Object store, int capacity) {
            this.capacity = capacity;
            this.store = store;
        }
    }

    public static ArrayBuilderNode create() {
        return new ArrayBuilderProxyNode();
    }

    public abstract BuilderState start();

    public abstract BuilderState start(int length);

    public abstract void appendArray(BuilderState state, int index, DynamicObject array);

    public abstract void appendValue(BuilderState state, int index, Object value);

    public abstract Object finish(BuilderState state, int length);

    private static class ArrayBuilderProxyNode extends ArrayBuilderNode {

        @Child StartNode startNode = new StartNode(ArrayStoreLibrary.INITIAL_ALLOCATOR, 0);
        @Child AppendArrayNode appendArrayNode;
        @Child AppendOneNode appendOneNode;

        @Override
        public BuilderState start() {
            return startNode.start();
        }

        @Override
        public BuilderState start(int length) {
            return startNode.start(length);
        }

        @Override
        public void appendArray(BuilderState state, int index, DynamicObject array) {
            getAppendArrayNode().executeAppend(state, index, array);
        }

        @Override
        public void appendValue(BuilderState state, int index, Object value) {
            getAppendOneNode().executeAppend(state, index, value);
        }

        @Override
        public Object finish(BuilderState state, int length) {
            assert length == state.nextIndex;
            return state.store;
        }

        private AppendArrayNode getAppendArrayNode() {
            if (appendArrayNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                appendArrayNode = insert(AppendArrayNode.create(getContext(), startNode.allocator));
            }
            return appendArrayNode;
        }

        private AppendOneNode getAppendOneNode() {
            if (appendOneNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                appendOneNode = insert(AppendOneNode.create(getContext(), startNode.allocator));
            }
            return appendOneNode;
        }

        public synchronized ArrayAllocator updateStrategy(ArrayStoreLibrary.ArrayAllocator newStrategy, int newLength) {
            final ArrayStoreLibrary.ArrayAllocator oldStrategy = startNode.allocator;
            final ArrayStoreLibrary.ArrayAllocator updatedAllocator;
            if (oldStrategy != newStrategy) {
                updatedAllocator = ArrayStoreLibrary.getFactory().getUncached().generalizeForStore(
                        oldStrategy.allocate(0),
                        newStrategy.allocate(0));
            } else {
                updatedAllocator = oldStrategy;
            }

            final int oldLength = startNode.expectedLength;
            final int newExpectedLength = Math.max(oldLength, newLength);

            if (updatedAllocator != oldStrategy || newExpectedLength > oldLength) {
                startNode.replace(new StartNode(updatedAllocator, newExpectedLength));
            }

            if (newStrategy != oldStrategy) {
                if (appendArrayNode != null) {
                    appendArrayNode.replace(AppendArrayNode.create(getContext(), updatedAllocator));
                }
                if (appendOneNode != null) {
                    appendOneNode.replace(AppendOneNode.create(getContext(), updatedAllocator));
                }
            }

            return updatedAllocator;
        }

    }

    public abstract static class ArrayBuilderBaseNode extends RubyContextNode {

        protected ArrayAllocator replaceNodes(ArrayStoreLibrary.ArrayAllocator strategy, int size) {
            final ArrayBuilderProxyNode parent = (ArrayBuilderProxyNode) getParent();
            return parent.updateStrategy(strategy, size);
        }
    }

    public static class StartNode extends ArrayBuilderBaseNode {

        private final ArrayStoreLibrary.ArrayAllocator allocator;
        private final int expectedLength;

        public StartNode(ArrayStoreLibrary.ArrayAllocator allocator, int expectedLength) {
            this.allocator = allocator;
            this.expectedLength = expectedLength;
        }

        public BuilderState start() {
            if (allocator == ArrayStoreLibrary.INITIAL_ALLOCATOR) {
                return new BuilderState(allocator.allocate(0), expectedLength);
            } else {
                return new BuilderState(allocator.allocate(expectedLength), expectedLength);
            }
        }

        public BuilderState start(int length) {
            if (length > expectedLength) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                replaceNodes(allocator, length);
            }
            if (allocator == ArrayStoreLibrary.INITIAL_ALLOCATOR) {
                return new BuilderState(allocator.allocate(0), length);
            } else {
                return new BuilderState(allocator.allocate(length), length);
            }
        }

    }

    @ImportStatic(ArrayGuards.class)
    public abstract static class AppendOneNode extends ArrayBuilderBaseNode {

        public static AppendOneNode create(RubyContext context, ArrayStoreLibrary.ArrayAllocator allocator) {
            return AppendOneNodeGen.create(context, allocator);
        }

        private final RubyContext context;
        protected final ArrayStoreLibrary.ArrayAllocator allocator;

        public AppendOneNode(RubyContext context, ArrayStoreLibrary.ArrayAllocator allocator) {
            this.context = context;
            this.allocator = allocator;
        }

        public abstract void executeAppend(BuilderState array, int index, Object value);

        @Specialization(
                guards = "arrays.acceptsValue(state.store, value)",
                limit = "1")
        protected void appendCompatibleType(BuilderState state, int index, Object value,
                @CachedLibrary("state.store") ArrayStoreLibrary arrays) {
            assert state.nextIndex == index;
            final int length = arrays.capacity(state.store);
            if (index >= length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                final int capacity = ArrayUtils.capacityForOneMore(context, length);
                state.store = arrays.expand(state.store, capacity);
                state.capacity = capacity;
                replaceNodes(arrays.allocator(state.store), capacity);
            }
            arrays.write(state.store, index, value);
            state.nextIndex++;
        }

        @Fallback
        protected void appendNewStrategy(BuilderState state, int index, Object value) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            assert state.nextIndex == index;
            final ArrayStoreLibrary stores = ArrayStoreLibrary.getFactory().getUncached();
            ArrayStoreLibrary.ArrayAllocator newAllocator = stores.generalizeForValue(state.store, value);

            final int currentCapacity = state.capacity;
            final int neededCapacity;
            if (index >= currentCapacity) {
                neededCapacity = ArrayUtils.capacityForOneMore(context, currentCapacity);
            } else {
                neededCapacity = currentCapacity;
            }

            newAllocator = replaceNodes(newAllocator, neededCapacity);

            final Object newStore = newAllocator.allocate(neededCapacity);
            stores.copyContents(state.store, 0, newStore, 0, index);
            stores.write(newStore, index, value);
            state.store = newStore;
            state.capacity = neededCapacity;
            state.nextIndex++;
        }

    }

    @ImportStatic(ArrayGuards.class)
    public abstract static class AppendArrayNode extends ArrayBuilderBaseNode {

        public static AppendArrayNode create(RubyContext context, ArrayStoreLibrary.ArrayAllocator allocator) {
            return AppendArrayNodeGen.create(context, allocator);
        }

        private final RubyContext context;
        protected final ArrayStoreLibrary.ArrayAllocator allocator;

        public AppendArrayNode(RubyContext context, ArrayStoreLibrary.ArrayAllocator allocator) {
            this.context = context;
            this.allocator = allocator;
        }

        public abstract void executeAppend(BuilderState state, int index, DynamicObject value);

        @Specialization(
                guards = { "arrays.acceptsAllValues(state.store, getStore(other))" },
                limit = "1")
        protected void appendCompatibleStrategy(BuilderState state, int index, DynamicObject other,
                @CachedLibrary("state.store") ArrayStoreLibrary arrays,
                @CachedLibrary("getStore(other)") ArrayStoreLibrary others) {
            assert state.nextIndex == index;
            final int otherSize = Layouts.ARRAY.getSize(other);
            final int neededSize = index + otherSize;

            int length = arrays.capacity(state.store);
            if (neededSize > length) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                replaceNodes(arrays.allocator(state.store), neededSize);
                final int capacity = ArrayUtils.capacity(context, length, neededSize);
                state.store = arrays.expand(state.store, capacity);
                state.capacity = capacity;
            }

            final Object otherStore = Layouts.ARRAY.getStore(other);
            others.copyContents(otherStore, 0, state.store, index, otherSize);
            state.nextIndex = state.nextIndex + otherSize;
        }

        @Fallback
        protected void appendNewStrategy(BuilderState state, int index, DynamicObject other) {
            assert state.nextIndex == index;
            final int otherSize = Layouts.ARRAY.getSize(other);
            if (otherSize != 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();

                final ArrayStoreLibrary arrays = ArrayStoreLibrary.getFactory().getUncached();
                final int neededSize = index + otherSize;

                final Object newStore;

                final int currentCapacity = state.capacity;
                final int neededCapacity;
                if (neededSize > currentCapacity) {
                    neededCapacity = ArrayUtils.capacity(context, currentCapacity, neededSize);
                } else {
                    neededCapacity = currentCapacity;
                }

                ArrayAllocator allocator = replaceNodes(
                                                        arrays.generalizeForStore(state.store, Layouts.ARRAY.getStore(other)),
                                                        neededCapacity);
                newStore = allocator.allocate(neededCapacity);

                arrays.copyContents(state.store, 0, newStore, 0, index);

                final Object otherStore = Layouts.ARRAY.getStore(other);
                arrays.copyContents(otherStore, 0, newStore, index, otherSize);

                state.store = newStore;
                state.capacity = neededCapacity;
                state.nextIndex = state.nextIndex + otherSize;
            }
        }

        protected static Object getStore(DynamicObject array) {
            return Layouts.ARRAY.getStore(array);
        }
    }

}
