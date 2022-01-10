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

package com.accenture.trac.svc.data.api;

import com.accenture.trac.api.*;
import com.accenture.trac.common.concurrent.Flows;
import com.accenture.trac.metadata.*;

import com.accenture.trac.test.data.SampleDataFormats;
import com.accenture.trac.test.helpers.TestResourceHelpers;
import com.google.common.collect.Streams;
import com.google.protobuf.ByteString;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.accenture.trac.common.metadata.MetadataUtil.selectorFor;
import static com.accenture.trac.test.concurrent.ConcurrentTestHelpers.resultOf;
import static com.accenture.trac.test.concurrent.ConcurrentTestHelpers.waitFor;


class DataRoundTripTest extends DataApiTestBase {

    private static final String BASIC_CSV_DATA = SampleDataFormats.BASIC_CSV_DATA_RESOURCE;
    private static final String BASIC_JSON_DATA = SampleDataFormats.BASIC_JSON_DATA_RESOURCE;

    static final byte[] BASIC_CSV_CONTENT = TestResourceHelpers.loadResourceAsBytes(BASIC_CSV_DATA);

    private final List<Vector<Object>> BASIC_TEST_DATA = DataApiTestHelpers.decodeCsv(
            SampleDataFormats.BASIC_TABLE_SCHEMA,
            List.of(ByteString.copyFrom(BASIC_CSV_CONTENT)));


    @Test
    void roundTrip_arrowStream() throws Exception {

        // Create a single batch of Arrow data

        var allocator = new RootAllocator();
        var root = SampleDataFormats.generateBasicData(allocator);

        // Use a writer to encode the batch as a stream of chunks (arrow record batches, including the schema)

        var writeChannel = new ChunkChannel();

        try (var writer = new ArrowStreamWriter(root, null, writeChannel)) {

            writer.start();
            writer.writeBatch();
            writer.end();
        }

        var mimeType = "application/vnd.apache.arrow.stream";
        roundTripTest(writeChannel.getChunks(), mimeType, mimeType, DataApiTestHelpers::decodeArrowStream, BASIC_TEST_DATA, true);
        roundTripTest(writeChannel.getChunks(), mimeType, mimeType, DataApiTestHelpers::decodeArrowStream, BASIC_TEST_DATA, false);
    }

    @Test
    void roundTrip_csv() throws Exception {

        var testDataStream = getClass().getResourceAsStream(BASIC_CSV_DATA);

        if (testDataStream == null)
            throw new RuntimeException("Test data not found");

        var testDataBytes = testDataStream.readAllBytes();
        var testData = List.of(ByteString.copyFrom(testDataBytes));

        var mimeType = "text/csv";
        roundTripTest(testData, mimeType, mimeType, DataApiTestHelpers::decodeCsv, BASIC_TEST_DATA, true);
        roundTripTest(testData, mimeType, mimeType, DataApiTestHelpers::decodeCsv, BASIC_TEST_DATA, false);
    }

    @Test
    void roundTrip_json() throws Exception {

        var testDataStream = getClass().getResourceAsStream(BASIC_JSON_DATA);

        if (testDataStream == null)
            throw new RuntimeException("Test data not found");

        var testDataBytes = testDataStream.readAllBytes();
        var testData = List.of(ByteString.copyFrom(testDataBytes));

        var mimeType = "text/json";
        roundTripTest(testData, mimeType, mimeType, DataApiTestHelpers::decodeJson, BASIC_TEST_DATA, true);
        roundTripTest(testData, mimeType, mimeType, DataApiTestHelpers::decodeJson, BASIC_TEST_DATA, false);
    }

    private void roundTripTest(
            List<ByteString> content, String writeFormat, String readFormat,
            BiFunction<SchemaDefinition, List<ByteString>, List<Vector<Object>>> decodeFunc,
            List<Vector<Object>> expectedResult, boolean dataInChunkZero) throws Exception {

        var requestParams = DataWriteRequest.newBuilder()
                .setTenant(TEST_TENANT)
                .setSchema(SampleDataFormats.BASIC_TABLE_SCHEMA)
                .setFormat(writeFormat)
                .build();

        var createDatasetRequest = dataWriteRequest(requestParams, content, dataInChunkZero);
        var createDataset = DataApiTestHelpers.clientStreaming(dataClient::createDataset, createDatasetRequest);

        waitFor(TEST_TIMEOUT, createDataset);
        var objHeader = resultOf(createDataset);

        var dataRequest = DataReadRequest.newBuilder()
                .setTenant(TEST_TENANT)
                .setSelector(selectorFor(objHeader))
                .setFormat(readFormat)
                .build();

        var readResponse = Flows.<DataReadResponse>hub(execContext);
        var readResponse0 = Flows.first(readResponse);
        var readByteStream = Flows.map(readResponse, DataReadResponse::getContent);
        var readBytes = Flows.fold(readByteStream, ByteString::concat, ByteString.EMPTY);

        DataApiTestHelpers.serverStreaming(dataClient::readDataset, dataRequest, readResponse);

        waitFor(Duration.ofMinutes(20), readResponse0, readBytes);
        var roundTripResponse = resultOf(readResponse0);
        var roundTripSchema = roundTripResponse.getSchema();
        var roundTripBytes = resultOf(readBytes);

        var roundTripData = decodeFunc.apply(roundTripSchema, List.of(roundTripBytes));

        Assertions.assertEquals(SampleDataFormats.BASIC_TABLE_SCHEMA, roundTripSchema);

        for (int i = 0; i < roundTripSchema.getTable().getFieldsCount(); i++) {

            for (var row = 0; row < expectedResult.size(); row++) {

                var expectedVal = expectedResult.get(i).get(row);
                var roundTripVal = roundTripData.get(i).get(row);

                // Allow comparing big decimals with different scales
                if (expectedVal instanceof BigDecimal)
                    roundTripVal = ((BigDecimal) roundTripVal).setScale(((BigDecimal) expectedVal).scale(), RoundingMode.UNNECESSARY);

                Assertions.assertEquals(expectedVal, roundTripVal);
            }
        }
    }

    private Flow.Publisher<DataWriteRequest> dataWriteRequest(
            DataWriteRequest requestParams,
            List<ByteString> content,
            boolean dataInChunkZero) {

        var chunkZeroBytes = dataInChunkZero
                ? content.get(0)
                : ByteString.EMPTY;

        var requestZero = requestParams.toBuilder()
                .setContent(chunkZeroBytes)
                .build();

        var remainingContent = dataInChunkZero
                ? content.subList(1, content.size())
                : content;

        var requestStream = remainingContent.stream().map(bytes ->
                DataWriteRequest.newBuilder()
                .setContent(bytes)
                .build());

        return Flows.publish(Streams.concat(
                Stream.of(requestZero),
                requestStream));
    }

    private static class ChunkChannel implements WritableByteChannel {

        private final List<ByteString> chunks = new ArrayList<>();
        private boolean isOpen = true;

        public List<ByteString> getChunks() {
            return chunks;
        }

        @Override
        public int write(ByteBuffer chunk) {

            var copied = ByteString.copyFrom(chunk);
            chunks.add(copied);
            return copied.size();
        }

        @Override public boolean isOpen() { return isOpen; }
        @Override public void close() { isOpen = false; }
    }
}