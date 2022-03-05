/*
 * Copyright 2021 Accenture Global Solutions Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.accenture.trac.common.storage;

import com.accenture.trac.common.concurrent.ExecutionContext;
import com.accenture.trac.common.concurrent.IExecutionContext;
import com.accenture.trac.common.storage.local.LocalFileStorage;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Random;


public class FileStorageReadWriteStability {

    /* >>> Test suite for IFileStorage - read/write operations, stability tests

    These tests are implemented purely in terms of the IFileStorage interface. The test suite can be run for
    any storage implementation and a valid storage implementations must pass this test suite.

    NOTE: To test a new storage implementation, setupStorage() must be replaced
    with a method to provide a storage implementation based on a supplied test config.

    Storage implementations may also wish to supply their own tests that use native APIs to set up and control
    tests. This can allow for finer grained control, particularly when testing corner cases and error conditions.
     */

    @TempDir
    static Path storageDir;
    static IFileStorage storage;
    static IExecutionContext execContext;

    @BeforeAll
    static void setupStorage() {

        var storageProps = new Properties();
        storageProps.put(IStorageManager.PROP_STORAGE_KEY, "TEST_STORAGE");
        storageProps.put(LocalFileStorage.CONFIG_ROOT_PATH, storageDir.toString());
        storage = new LocalFileStorage(storageProps);

        execContext = new ExecutionContext(new DefaultEventExecutor(new DefaultThreadFactory("t-events")));
    }

    // Running the rapid fire round trip test may flush out some less common stability issues and resource leaks.
    // Of course there is no guarantee errors will be caught, but in practice this test has been helpful...

    // Using a static storage instance, so all the rapid fire calls go to the same instance

    // For local storage, this test is fast enough to run with the unit tests on every build
    // For remote storage it may need to be marked with @Tag("slow")

    @RepeatedTest(500)
    void rapidFireTest(RepetitionInfo repetitionInfo) throws Exception {

        var storagePath = String.format("rapid_fire_%d.dat", repetitionInfo.getCurrentRepetition());

        var bytes = List.of(  // Selection of different size chunks
                new byte[3],
                new byte[10000],
                new byte[42],
                new byte[4097],
                new byte[1],
                new byte[2000]);

        var random = new Random();
        bytes.forEach(random::nextBytes);

        FileStorageReadWriteTest.roundTripTest(
                storagePath, bytes,
                storage, execContext);
    }
}
