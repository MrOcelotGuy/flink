/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.state.filesystem;

import org.apache.flink.api.common.JobID;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.checkpoint.filemerging.FileMergingSnapshotManager;
import org.apache.flink.runtime.checkpoint.filemerging.FileMergingSnapshotManager.SubtaskKey;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.runtime.state.CheckpointStreamFactory;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;

/** An implementation of file merging checkpoint storage to file systems. */
public class FsMergingCheckpointStorageAccess extends FsCheckpointStorageAccess
        implements Closeable {

    /** FileMergingSnapshotManager manages files and meta information for checkpoints. */
    private final FileMergingSnapshotManager fileMergingSnapshotManager;

    /** The identity of subtask. */
    private final FileMergingSnapshotManager.SubtaskKey subtaskKey;

    public FsMergingCheckpointStorageAccess(
            Path checkpointBaseDirectory,
            @Nullable Path defaultSavepointDirectory,
            JobID jobId,
            int fileSizeThreshold,
            int writeBufferSize,
            FileMergingSnapshotManager fileMergingSnapshotManager,
            Environment environment)
            throws IOException {
        super(
                // Multiple subtask/threads would share one output stream,
                // SafetyNetWrapperFileSystem cannot be used to prevent different threads from
                // interfering with each other when exiting.
                FileSystem.getUnguardedFileSystem(checkpointBaseDirectory.toUri()),
                checkpointBaseDirectory,
                defaultSavepointDirectory,
                false,
                jobId,
                fileSizeThreshold,
                writeBufferSize);
        this.fileMergingSnapshotManager = fileMergingSnapshotManager;
        this.subtaskKey = SubtaskKey.of(environment);
    }

    @Override
    public void initializeBaseLocationsForCheckpoint() throws IOException {
        super.initializeBaseLocationsForCheckpoint();
        fileMergingSnapshotManager.initFileSystem(
                fileSystem,
                checkpointsDirectory,
                sharedStateDirectory,
                taskOwnedStateDirectory,
                writeBufferSize);
        fileMergingSnapshotManager.registerSubtaskForSharedStates(subtaskKey);
    }

    @Override
    public CheckpointStreamFactory resolveCheckpointStorageLocation(
            long checkpointId, CheckpointStorageLocationReference reference) throws IOException {
        if (reference.isDefaultReference()) {
            // default reference, construct the default location for that particular checkpoint
            final Path checkpointDir =
                    createCheckpointDirectory(checkpointsDirectory, checkpointId);

            return new FsMergingCheckpointStorageLocation(
                    subtaskKey,
                    fileSystem,
                    checkpointDir,
                    sharedStateDirectory,
                    taskOwnedStateDirectory,
                    reference,
                    fileSizeThreshold,
                    writeBufferSize,
                    fileMergingSnapshotManager,
                    checkpointId);
        } else {
            // location encoded in the reference
            final Path path = decodePathFromReference(reference);

            return new FsMergingCheckpointStorageLocation(
                    subtaskKey,
                    path.getFileSystem(),
                    path,
                    path,
                    path,
                    reference,
                    fileSizeThreshold,
                    writeBufferSize,
                    fileMergingSnapshotManager,
                    checkpointId);
        }
    }

    /** This will be registered to resource closer of {@code StreamTask}. */
    @Override
    public void close() {
        fileMergingSnapshotManager.unregisterSubtask(subtaskKey);
    }
}
