/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.computer.core.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;

import com.baidu.hugegraph.computer.core.common.exception.ComputerException;
import com.baidu.hugegraph.computer.core.config.ComputerOptions;
import com.baidu.hugegraph.computer.core.config.Config;
import com.baidu.hugegraph.computer.core.network.buffer.ManagedBuffer;
import com.baidu.hugegraph.computer.core.sort.flusher.OuterSortFlusher;
import com.baidu.hugegraph.computer.core.sort.flusher.PeekableIterator;
import com.baidu.hugegraph.computer.core.sort.sorting.SortManager;
import com.baidu.hugegraph.computer.core.store.SuperstepFileGenerator;
import com.baidu.hugegraph.computer.core.store.hgkvfile.entry.KvEntry;
import com.baidu.hugegraph.util.Log;

/**
 * Manage the buffers received for a partition and the files generated by
 * sorting the buffers to file. The type of data may be VERTEX, EDGE, and
 * MESSAGE.
 */
public abstract class MessageRecvPartition {

    public static final Logger LOG = Log.logger(MessageRecvPartition.class);

    private MessageRecvBuffers recvBuffers;
    /*
     * Used to sort the buffers that reached threshold
     * ComputerOptions.WORKER_RECEIVED_BUFFERS_BYTES_LIMIT.
     */
    private MessageRecvBuffers sortBuffers;
    private final SortManager sortManager;

    private List<String> outputFiles;
    private final SuperstepFileGenerator fileGenerator;
    private final boolean withSubKv;
    private final int mergeFileNum;
    private long totalBytes;

    private final AtomicReference<Throwable> exception;

    public MessageRecvPartition(Config config,
                                SuperstepFileGenerator fileGenerator,
                                SortManager sortManager,
                                boolean withSubKv) {
        this.fileGenerator = fileGenerator;
        this.sortManager = sortManager;
        this.withSubKv = withSubKv;
        long buffersLimit = config.get(
             ComputerOptions.WORKER_RECEIVED_BUFFERS_BYTES_LIMIT);

        long waitSortTimeout = config.get(
                               ComputerOptions.WORKER_WAIT_SORT_TIMEOUT);
        this.mergeFileNum = config.get(ComputerOptions.HGKV_MERGE_FILES_NUM);
        this.recvBuffers = new MessageRecvBuffers(buffersLimit,
                                                  waitSortTimeout);
        this.sortBuffers = new MessageRecvBuffers(buffersLimit,
                                                  waitSortTimeout);
        this.outputFiles = new ArrayList<>();
        this.totalBytes = 0L;
        this.exception = new AtomicReference<>();
    }

    /**
     * Only one thread can call this method.
     */
    public synchronized void addBuffer(ManagedBuffer buffer) {
        this.totalBytes += buffer.length();
        this.recvBuffers.addBuffer(buffer);
        if (this.recvBuffers.full()) {
            this.sortBuffers.waitSorted();
            this.swapReceiveAndSortBuffers();
            String path = this.fileGenerator.nextPath(this.type());
            this.mergeBuffers(this.sortBuffers, path);
            this.outputFiles.add(path);
        }
    }

    public PeekableIterator<KvEntry> iterator() {
        /*
         * TODO: create iterator directly from buffers if there is no
         *       outputFiles.
         */
        this.flushAllBuffersAndWaitSorted();
        this.mergeOutputFilesIfNeeded();
        if (this.outputFiles.size() == 0) {
            return PeekableIterator.emptyIterator();
        }
        return this.sortManager.iterator(this.outputFiles, this.withSubKv);
    }

    public long totalBytes() {
        return this.totalBytes;
    }

    public RecvMessageStat recvMessageStat() {
        return new RecvMessageStat(0L, this.totalBytes);
    }

    protected abstract OuterSortFlusher outerSortFlusher();

    protected abstract String type();

    /**
     * Flush the receive buffers to file, and wait both recvBuffers and
     * sortBuffers to finish sorting.
     * After this method be called, can not call
     * {@link #addBuffer(ManagedBuffer)} any more.
     */
    private void flushAllBuffersAndWaitSorted() {
        this.sortBuffers.waitSorted();
        if (this.recvBuffers.totalBytes() > 0) {
            String path = this.fileGenerator.nextPath(this.type());
            this.mergeBuffers(this.recvBuffers, path);
            this.outputFiles.add(path);
        }
        this.recvBuffers.waitSorted();
        this.checkException();
    }

    private void mergeBuffers(MessageRecvBuffers buffers, String path) {
        this.checkException();
        this.sortManager.mergeBuffers(buffers.buffers(), path, this.withSubKv,
                                      this.outerSortFlusher())
                        .whenComplete((r , e) -> {
            if (e != null) {
                LOG.error("Failed to merge buffers", e);
                // Just record the first error
                this.exception.compareAndSet(null, e);
            }
            // Signal the buffers to prevent other thread wait indefinitely.
            buffers.signalSorted();
        });
    }

    private void swapReceiveAndSortBuffers() {
        MessageRecvBuffers tmp = this.recvBuffers;
        this.recvBuffers = this.sortBuffers;
        this.sortBuffers = tmp;
    }

    /**
     * Merge outputFiles if needed, like merge 10000 files into 100 files.
     */
    private void mergeOutputFilesIfNeeded() {
        if (this.outputFiles.size() <= 1) {
            return;
        }

        /*
         * TODO Restore genOutputFileNames(sqrt(outputFiles.size()))
         *  after add Sorter#iterator() of subkv
         */
        int mergeFileNum = this.mergeFileNum;
        mergeFileNum = 1;
        List<String> newOutputs = this.genOutputFileNames(mergeFileNum);
        this.sortManager.mergeInputs(this.outputFiles, newOutputs,
                                     this.withSubKv, this.outerSortFlusher());
        this.outputFiles = newOutputs;
    }

    private List<String> genOutputFileNames(int targetSize) {
        List<String> files = new ArrayList<>(targetSize);
        for (int i = 0; i < targetSize; i++) {
            files.add(this.fileGenerator.nextPath(this.type()));
        }
        return files;
    }

    private void checkException() {
        Throwable t = this.exception.get();
        if (t != null) {
            throw new ComputerException(t.getMessage(), t);
        }
    }
}
