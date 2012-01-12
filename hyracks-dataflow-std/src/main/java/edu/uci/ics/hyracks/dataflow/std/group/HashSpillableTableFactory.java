/*
 * Copyright 2009-2010 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.uci.ics.hyracks.dataflow.std.group;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import edu.uci.ics.hyracks.api.comm.IFrameWriter;
import edu.uci.ics.hyracks.api.context.IHyracksTaskContext;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparator;
import edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory;
import edu.uci.ics.hyracks.api.dataflow.value.INormalizedKeyComputer;
import edu.uci.ics.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ISerializerDeserializer;
import edu.uci.ics.hyracks.api.dataflow.value.ITuplePartitionComputer;
import edu.uci.ics.hyracks.api.dataflow.value.ITuplePartitionComputerFactory;
import edu.uci.ics.hyracks.api.dataflow.value.ITypeTrait;
import edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.dataflow.common.comm.io.FrameTupleAccessor;
import edu.uci.ics.hyracks.dataflow.common.comm.io.FrameTuplePairComparator;
import edu.uci.ics.hyracks.dataflow.common.comm.util.FrameUtils;
import edu.uci.ics.hyracks.dataflow.std.structures.ISerializableTable;
import edu.uci.ics.hyracks.dataflow.std.structures.SerializableHashTable;
import edu.uci.ics.hyracks.dataflow.std.structures.TuplePointer;

/**
 *
 */
public class HashSpillableTableFactory implements ISpillableTableFactory {

    private static final long serialVersionUID = 1L;
    private final ITuplePartitionComputerFactory tpcf;
    private final int tableSize;

    public HashSpillableTableFactory(ITuplePartitionComputerFactory tpcf,
            int tableSize) {
        this.tpcf = tpcf;
        this.tableSize = tableSize;
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * edu.uci.ics.hyracks.dataflow.std.aggregations.ISpillableTableFactory#
     * buildSpillableTable(edu.uci.ics.hyracks.api.context.IHyracksTaskContext,
     * int[], edu.uci.ics.hyracks.api.dataflow.value.IBinaryComparatorFactory[],
     * edu.uci.ics.hyracks.api.dataflow.value.INormalizedKeyComputerFactory,
     * edu.
     * uci.ics.hyracks.dataflow.std.aggregations.IFieldAggregateDescriptorFactory
     * [], edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor,
     * edu.uci.ics.hyracks.api.dataflow.value.RecordDescriptor, int)
     */
    @Override
    public ISpillableTable buildSpillableTable(final IHyracksTaskContext ctx,
            final int[] keyFields,
            IBinaryComparatorFactory[] comparatorFactories,
            INormalizedKeyComputerFactory firstKeyNormalizerFactory,
            IAggregatorDescriptorFactory aggregateFactory,
            RecordDescriptor inRecordDescriptor,
            RecordDescriptor outRecordDescriptor, final int framesLimit)
            throws HyracksDataException {
        final int[] storedKeys = new int[keyFields.length];
        @SuppressWarnings("rawtypes")
        ISerializerDeserializer[] storedKeySerDeser = new ISerializerDeserializer[keyFields.length];
        for (int i = 0; i < keyFields.length; i++) {
            storedKeys[i] = i;
            storedKeySerDeser[i] = inRecordDescriptor.getFields()[keyFields[i]];
        }

        RecordDescriptor internalRecordDescriptor = outRecordDescriptor;
        final FrameTupleAccessor storedKeysAccessor1;
        final FrameTupleAccessor storedKeysAccessor2;
        if (keyFields.length >= outRecordDescriptor.getFields().length) {
            // for the case of zero-aggregations
            ISerializerDeserializer<?>[] fields = outRecordDescriptor
                    .getFields();
            ITypeTrait[] types = outRecordDescriptor.getTypeTraits();
            ISerializerDeserializer<?>[] newFields = new ISerializerDeserializer[fields.length + 1];
            for (int i = 0; i < fields.length; i++)
                newFields[i] = fields[i];
            ITypeTrait[] newTypes = null;
            if (types != null) {
                newTypes = new ITypeTrait[types.length + 1];
                for (int i = 0; i < types.length; i++)
                    newTypes[i] = types[i];
            }
            internalRecordDescriptor = new RecordDescriptor(newFields, newTypes);
        }
        storedKeysAccessor1 = new FrameTupleAccessor(ctx.getFrameSize(),
                internalRecordDescriptor);
        storedKeysAccessor2 = new FrameTupleAccessor(ctx.getFrameSize(),
                internalRecordDescriptor);

        final IBinaryComparator[] comparators = new IBinaryComparator[comparatorFactories.length];
        for (int i = 0; i < comparatorFactories.length; ++i) {
            comparators[i] = comparatorFactories[i].createBinaryComparator();
        }

        final FrameTuplePairComparator ftpcPartial = new FrameTuplePairComparator(
                keyFields, storedKeys, comparators);
        final FrameTuplePairComparator ftpcTuple = new FrameTuplePairComparator(
                storedKeys, storedKeys, comparators);

        final ITuplePartitionComputer tpc = tpcf.createPartitioner();

        final INormalizedKeyComputer nkc = firstKeyNormalizerFactory == null ? null
                : firstKeyNormalizerFactory.createNormalizedKeyComputer();

        int[] keyFieldsInPartialResults = new int[keyFields.length];
        for (int i = 0; i < keyFieldsInPartialResults.length; i++) {
            keyFieldsInPartialResults[i] = i;
        }

        final IAggregatorDescriptor aggregator = aggregateFactory
                .createAggregator(ctx, inRecordDescriptor, outRecordDescriptor,
                        keyFields, keyFieldsInPartialResults);

        final AggregateState aggregateState = aggregator
                .createAggregateStates();

        return new ISpillableTable() {

            private int lastBufIndex;
            private int outFrameOffset;
            private int tupleCountInOutFrame;

            private ByteBuffer outputFrame;

            private final ISerializableTable table = new SerializableHashTable(
                    tableSize, ctx);
            private final TuplePointer storedTuplePointer = new TuplePointer();
            private final List<ByteBuffer> frames = new ArrayList<ByteBuffer>();

            /**
             * A tuple is "pointed" to by 3 entries in the tPointers array. [0]
             * = Frame index in the "Frames" list, [1] = Tuple index in the
             * frame, [2] = Poor man's normalized key for the tuple.
             */
            private int[] tPointers;

            @Override
            public void sortFrames() {
                int sfIdx = storedKeys[0];
                int totalTCount = table.getTupleCount();
                tPointers = new int[totalTCount * 3];
                int ptr = 0;

                for (int i = 0; i < tableSize; i++) {
                    int entry = i;
                    int offset = 0;
                    do {
                        table.getTuplePointer(entry, offset, storedTuplePointer);
                        if (storedTuplePointer.frameIndex < 0)
                            break;
                        tPointers[ptr * 3] = entry;
                        tPointers[ptr * 3 + 1] = offset;
                        table.getTuplePointer(entry, offset, storedTuplePointer);
                        int fIndex = storedTuplePointer.frameIndex;
                        int tIndex = storedTuplePointer.tupleIndex;
                        storedKeysAccessor1.reset(frames.get(fIndex));
                        int tStart = storedKeysAccessor1
                                .getTupleStartOffset(tIndex);
                        int f0StartRel = storedKeysAccessor1
                                .getFieldStartOffset(tIndex, sfIdx);
                        int f0EndRel = storedKeysAccessor1.getFieldEndOffset(
                                tIndex, sfIdx);
                        int f0Start = f0StartRel + tStart
                                + storedKeysAccessor1.getFieldSlotsLength();
                        tPointers[ptr * 3 + 2] = nkc == null ? 0 : nkc
                                .normalize(storedKeysAccessor1.getBuffer()
                                        .array(), f0Start, f0EndRel
                                        - f0StartRel);
                        ptr++;
                        offset++;
                    } while (true);
                }
                /**
                 * Sort using quick sort
                 */
                if (tPointers.length > 0) {
                    sort(tPointers, 0, totalTCount);
                }
            }

            @Override
            public void reset() {
                lastBufIndex = -1;
                tPointers = null;
                table.reset();
                aggregator.reset();
            }

            @Override
            public boolean insert(FrameTupleAccessor accessor, int tIndex)
                    throws HyracksDataException {
                if (lastBufIndex < 0)
                    nextAvailableFrame();
                int entry = tpc.partition(accessor, tIndex, tableSize);
                boolean foundGroup = false;
                int offset = 0;
                do {
                    table.getTuplePointer(entry, offset++, storedTuplePointer);
                    if (storedTuplePointer.frameIndex < 0)
                        break;
                    storedKeysAccessor1.reset(frames
                            .get(storedTuplePointer.frameIndex));
                    int c = ftpcPartial.compare(accessor, tIndex,
                            storedKeysAccessor1, storedTuplePointer.tupleIndex);
                    if (c == 0) {
                        foundGroup = true;
                        break;
                    }
                } while (true);

                if (!foundGroup) {

                    int initLen = aggregator.getBinaryAggregateStateLength(
                            accessor, tIndex, aggregateState);

                    if (FrameToolsForGroupers.isFrameOverflowing(
                            frames.get(lastBufIndex), initLen, (outFrameOffset == 0))) {
                        if (!nextAvailableFrame()) {
                            return false;
                        }
                    }

                    aggregator.init(frames.get(lastBufIndex).array(),
                            outFrameOffset, accessor, tIndex, aggregateState);

                    FrameToolsForGroupers.updateFrameMetaForNewTuple(
                            frames.get(lastBufIndex), initLen, (outFrameOffset == 0));

                    outFrameOffset += initLen;
                    tupleCountInOutFrame++;

                    storedTuplePointer.frameIndex = lastBufIndex;
                    storedTuplePointer.tupleIndex = tupleCountInOutFrame - 1;
                    table.insert(entry, storedTuplePointer);
                } else {

                    aggregator.aggregate(accessor, tIndex, storedKeysAccessor1,
                            storedTuplePointer.tupleIndex, aggregateState);

                }
                return true;
            }

            @Override
            public List<ByteBuffer> getFrames() {
                return frames;
            }

            @Override
            public int getFrameCount() {
                return lastBufIndex;
            }

            @Override
            public void flushFrames(IFrameWriter writer, boolean isPartial)
                    throws HyracksDataException {
                if (outputFrame == null) {
                    outputFrame = ctx.allocateFrame();
                }

                int outputFrameOffset = 0;
                writer.open();

                if (tPointers == null) {
                    // Not sorted
                    for (int i = 0; i < tableSize; ++i) {
                        int entry = i;
                        int offset = 0;
                        do {
                            table.getTuplePointer(entry, offset++,
                                    storedTuplePointer);
                            if (storedTuplePointer.frameIndex < 0)
                                break;
                            int bIndex = storedTuplePointer.frameIndex;
                            int tIndex = storedTuplePointer.tupleIndex;

                            storedKeysAccessor1.reset(frames.get(bIndex));

                            int outputLen;

                            if (isPartial) {

                                outputLen = aggregator.getPartialOutputLength(
                                        storedKeysAccessor1, tIndex,
                                        aggregateState);

                                if (FrameToolsForGroupers.isFrameOverflowing(
                                        outputFrame, outputLen, (outputFrameOffset == 0))) {
                                    FrameUtils.flushFrame(outputFrame, writer);
                                    outputFrameOffset = 0;
                                    if (FrameToolsForGroupers
                                            .isFrameOverflowing(outputFrame,
                                                    outputLen, (outputFrameOffset == 0))) {
                                        throw new HyracksDataException(
                                                "The output item is too large to be fit into a frame.");
                                    }
                                }

                                aggregator.outputPartialResult(
                                        outputFrame.array(), outputFrameOffset,
                                        storedKeysAccessor1, tIndex,
                                        aggregateState);

                            } else {
                                outputLen = aggregator.getFinalOutputLength(
                                        storedKeysAccessor1, tIndex,
                                        aggregateState);

                                if (FrameToolsForGroupers.isFrameOverflowing(
                                        outputFrame, outputLen, (outputFrameOffset == 0))) {
                                    FrameUtils.flushFrame(outputFrame, writer);
                                    outputFrameOffset = 0;
                                    if (FrameToolsForGroupers
                                            .isFrameOverflowing(outputFrame,
                                                    outputLen, (outputFrameOffset == 0))) {
                                        throw new HyracksDataException(
                                                "The output item is too large to be fit into a frame.");
                                    }
                                }

                                aggregator.outputFinalResult(
                                        outputFrame.array(), outputFrameOffset,
                                        storedKeysAccessor1, tIndex,
                                        aggregateState);
                            }

                            FrameToolsForGroupers.updateFrameMetaForNewTuple(
                                    outputFrame, outputLen, (outputFrameOffset == 0));

                            outputFrameOffset += outputLen;

                        } while (true);
                    }
                    if (outputFrameOffset > 0) {
                        FrameUtils.flushFrame(outputFrame, writer);
                        outputFrameOffset = 0;
                    }
                    aggregator.close();
                    return;
                }
                int n = tPointers.length / 3;
                for (int ptr = 0; ptr < n; ptr++) {
                    int tableIndex = tPointers[ptr * 3];
                    int rowIndex = tPointers[ptr * 3 + 1];
                    table.getTuplePointer(tableIndex, rowIndex,
                            storedTuplePointer);
                    int frameIndex = storedTuplePointer.frameIndex;
                    int tupleIndex = storedTuplePointer.tupleIndex;
                    // Get the frame containing the value
                    ByteBuffer buffer = frames.get(frameIndex);
                    storedKeysAccessor1.reset(buffer);

                    int outputLen;

                    if (isPartial) {

                        outputLen = aggregator
                                .getPartialOutputLength(storedKeysAccessor1,
                                        tupleIndex, aggregateState);

                        if (FrameToolsForGroupers.isFrameOverflowing(
                                outputFrame, outputLen, (outputFrameOffset == 0))) {
                            FrameUtils.flushFrame(outputFrame, writer);
                            outputFrameOffset = 0;
                            if (FrameToolsForGroupers.isFrameOverflowing(
                                    outputFrame, outputLen, (outputFrameOffset == 0))) {
                                throw new HyracksDataException(
                                        "The output item is too large to be fit into a frame.");
                            }
                        }

                        aggregator.outputPartialResult(outputFrame.array(),
                                outputFrameOffset, storedKeysAccessor1,
                                tupleIndex, aggregateState);

                    } else {
                        outputLen = aggregator
                                .getFinalOutputLength(storedKeysAccessor1,
                                        tupleIndex, aggregateState);

                        if (FrameToolsForGroupers.isFrameOverflowing(
                                outputFrame, outputLen, (outputFrameOffset == 0))) {
                            FrameUtils.flushFrame(outputFrame, writer);
                            outputFrameOffset = 0;
                            if (FrameToolsForGroupers.isFrameOverflowing(
                                    outputFrame, outputLen, (outputFrameOffset == 0))) {
                                throw new HyracksDataException(
                                        "The output item is too large to be fit into a frame.");
                            }
                        }

                        aggregator.outputFinalResult(outputFrame.array(),
                                outputFrameOffset, storedKeysAccessor1,
                                tupleIndex, aggregateState);
                    }

                    FrameToolsForGroupers.updateFrameMetaForNewTuple(
                            outputFrame, outputLen, (outputFrameOffset == 0));

                    outputFrameOffset += outputLen;
                }
                if (outputFrameOffset > 0) {
                    FrameUtils.flushFrame(outputFrame, writer);
                    outputFrameOffset = 0;
                }
                aggregator.close();
            }

            @Override
            public void close() {
                lastBufIndex = -1;
                tPointers = null;
                table.close();
                frames.clear();
                aggregateState.close();
            }

            /**
             * Set the working frame to the next available frame in the frame
             * list. There are two cases:<br>
             * 1) If the next frame is not initialized, allocate a new frame. 2)
             * When frames are already created, they are recycled.
             * 
             * @return Whether a new frame is added successfully.
             */
            private boolean nextAvailableFrame() {
                // Return false if the number of frames is equal to the limit.
                if (lastBufIndex + 1 >= framesLimit)
                    return false;

                if (frames.size() < framesLimit) {
                    // Insert a new frame
                    ByteBuffer frame = ctx.allocateFrame();
                    frame.position(0);
                    frame.limit(frame.capacity());
                    frames.add(frame);
                    outFrameOffset = 0;
                    tupleCountInOutFrame = 0;
                    lastBufIndex = frames.size() - 1;
                } else {
                    // Reuse an old frame
                    lastBufIndex++;
                    ByteBuffer frame = frames.get(lastBufIndex);
                    frame.position(0);
                    frame.limit(frame.capacity());
                    outFrameOffset = 0;
                    tupleCountInOutFrame = 0;
                }
                return true;
            }

            private void sort(int[] tPointers, int offset, int length) {
                int m = offset + (length >> 1);
                int mTable = tPointers[m * 3];
                int mRow = tPointers[m * 3 + 1];
                int mNormKey = tPointers[m * 3 + 2];

                table.getTuplePointer(mTable, mRow, storedTuplePointer);
                int mFrame = storedTuplePointer.frameIndex;
                int mTuple = storedTuplePointer.tupleIndex;
                storedKeysAccessor1.reset(frames.get(mFrame));

                int a = offset;
                int b = a;
                int c = offset + length - 1;
                int d = c;
                while (true) {
                    while (b <= c) {
                        int bTable = tPointers[b * 3];
                        int bRow = tPointers[b * 3 + 1];
                        int bNormKey = tPointers[b * 3 + 2];
                        int cmp = 0;
                        if (bNormKey != mNormKey) {
                            cmp = ((((long) bNormKey) & 0xffffffffL) < (((long) mNormKey) & 0xffffffffL)) ? -1
                                    : 1;
                        } else {
                            table.getTuplePointer(bTable, bRow,
                                    storedTuplePointer);
                            int bFrame = storedTuplePointer.frameIndex;
                            int bTuple = storedTuplePointer.tupleIndex;
                            storedKeysAccessor2.reset(frames.get(bFrame));
                            cmp = ftpcTuple.compare(storedKeysAccessor2,
                                    bTuple, storedKeysAccessor1, mTuple);
                        }
                        if (cmp > 0) {
                            break;
                        }
                        if (cmp == 0) {
                            swap(tPointers, a++, b);
                        }
                        ++b;
                    }
                    while (c >= b) {
                        int cTable = tPointers[c * 3];
                        int cRow = tPointers[c * 3 + 1];
                        int cNormKey = tPointers[c * 3 + 2];
                        int cmp = 0;
                        if (cNormKey != mNormKey) {
                            cmp = ((((long) cNormKey) & 0xffffffffL) < (((long) mNormKey) & 0xffffffffL)) ? -1
                                    : 1;
                        } else {
                            table.getTuplePointer(cTable, cRow,
                                    storedTuplePointer);
                            int cFrame = storedTuplePointer.frameIndex;
                            int cTuple = storedTuplePointer.tupleIndex;
                            storedKeysAccessor2.reset(frames.get(cFrame));
                            cmp = ftpcTuple.compare(storedKeysAccessor2,
                                    cTuple, storedKeysAccessor1, mTuple);
                        }
                        if (cmp < 0) {
                            break;
                        }
                        if (cmp == 0) {
                            swap(tPointers, c, d--);
                        }
                        --c;
                    }
                    if (b > c)
                        break;
                    swap(tPointers, b++, c--);
                }

                int s;
                int n = offset + length;
                s = Math.min(a - offset, b - a);
                vecswap(tPointers, offset, b - s, s);
                s = Math.min(d - c, n - d - 1);
                vecswap(tPointers, b, n - s, s);

                if ((s = b - a) > 1) {
                    sort(tPointers, offset, s);
                }
                if ((s = d - c) > 1) {
                    sort(tPointers, n - s, s);
                }
            }

            private void swap(int x[], int a, int b) {
                for (int i = 0; i < 3; ++i) {
                    int t = x[a * 3 + i];
                    x[a * 3 + i] = x[b * 3 + i];
                    x[b * 3 + i] = t;
                }
            }

            private void vecswap(int x[], int a, int b, int n) {
                for (int i = 0; i < n; i++, a++, b++) {
                    swap(x, a, b);
                }
            }

        };
    }

}
