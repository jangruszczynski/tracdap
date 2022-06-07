/*
 * Copyright 2022 Accenture Global Solutions Limited
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

package org.finos.tracdap.common.codec;

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.finos.tracdap.common.codec.arrow.ArrowFileCodec;
import org.finos.tracdap.common.codec.arrow.ArrowSchema;
import org.finos.tracdap.common.codec.arrow.ArrowStreamCodec;
import org.finos.tracdap.common.codec.csv.CsvCodec;
import org.finos.tracdap.common.codec.json.JsonCodec;
import org.finos.tracdap.common.concurrent.Flows;
import org.finos.tracdap.common.data.DataBlock;
import org.finos.tracdap.common.exception.EDataCorruption;
import org.finos.tracdap.common.exception.EUnexpected;
import org.finos.tracdap.metadata.SchemaDefinition;
import org.finos.tracdap.test.data.SampleData;
import org.finos.tracdap.test.helpers.TestResourceHelpers;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.EmptyByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.*;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.finos.tracdap.test.concurrent.ConcurrentTestHelpers.resultOf;
import static org.finos.tracdap.test.concurrent.ConcurrentTestHelpers.waitFor;
import static org.finos.tracdap.test.data.SampleData.generateBasicData;


public abstract class CodecTestSuite {

    // Concrete test cases for codecs included in CORE_DATA

    static class ArrowStreamTest extends CodecTestSuite { @BeforeAll static void setup() {
        codec = new ArrowStreamCodec();
        basicData = null;
    } }

    static class ArrowFileTest extends CodecTestSuite { @BeforeAll static void setup() {
        codec = new ArrowFileCodec();
        basicData = null;
    } }

    static class CSVTest extends CodecTestSuite { @BeforeAll static void setup() {
        codec = new CsvCodec();
        basicData = SampleData.BASIC_CSV_DATA_RESOURCE;
    } }

    static class JSONTest extends CodecTestSuite { @BeforeAll static void setup() {
        codec = new JsonCodec();
        basicData = SampleData.BASIC_JSON_DATA_RESOURCE;
    } }

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    static ICodec codec;
    static String basicData;

    private boolean basicDataAvailable() {
        return basicData != null;
    }

    @Test
    void roundTrip_basic() throws Exception {

        var allocator = new RootAllocator();
        var arrowSchema = ArrowSchema.tracToArrow(SampleData.BASIC_TABLE_SCHEMA);
        var root = generateBasicData(allocator);

        roundTrip_impl(SampleData.BASIC_TABLE_SCHEMA, arrowSchema, root, allocator);
    }

    @Test
    void roundTrip_nulls() throws Exception {

        var allocator = new RootAllocator();
        var arrowSchema = ArrowSchema.tracToArrow(SampleData.BASIC_TABLE_SCHEMA);
        var root = generateBasicData(allocator);

        // With the basic test data, we'll get one null value for each data type

        var limit = Math.min(root.getRowCount(), root.getFieldVectors().size());

        for (var i = 0; i < limit; i++) {

            var vector = root.getVector(i);

            if (BaseFixedWidthVector.class.isAssignableFrom(vector.getClass())) {

                var fixedVector = (BaseFixedWidthVector) vector;
                fixedVector.setNull(i);
            }
            else if (BaseVariableWidthVector.class.isAssignableFrom(vector.getClass())) {

                var variableVector = (BaseVariableWidthVector) vector;
                variableVector.setNull(i);
            }
            else {

                throw new EUnexpected();
            }

        }

        roundTrip_impl(SampleData.BASIC_TABLE_SCHEMA, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseIntegers() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_INTEGER, null);
        var field = new Field("string_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var intVec = new BigIntVector("integer_field", allocator);
        intVec.allocateNew(4);

        intVec.set(0, 0);
        intVec.setNull(1);
        intVec.set(2, Long.MAX_VALUE);
        intVec.set(3, Long.MIN_VALUE);

        var root = new VectorSchemaRoot(List.of(field), List.of(intVec));
        root.setRowCount(4);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseFloats() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_FLOAT, null);
        var field = new Field("string_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var floatVec = new Float8Vector("float_field", allocator);
        floatVec.allocateNew(8);

        floatVec.set(0, 0.0);
        floatVec.setNull(1);
        floatVec.set(2, Double.MAX_VALUE);
        floatVec.set(3, Double.MIN_VALUE);
        floatVec.set(4, Double.MIN_NORMAL);
        floatVec.set(5, -Double.MIN_NORMAL);

        // These values may be disallowed in certain places, e.g. checks on data import or model outputs
        // However at the codec level, they should be supported
        floatVec.set(6, Double.POSITIVE_INFINITY);
        floatVec.set(7, Double.NEGATIVE_INFINITY);
        floatVec.set(8, Double.NaN);

        var root = new VectorSchemaRoot(List.of(field), List.of(floatVec));
        root.setRowCount(8);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseDecimals() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_DECIMAL, null);
        var field = new Field("decimal_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var decimalVec = new DecimalVector(field, allocator);
        decimalVec.allocateNew(6);

        var d0 = BigDecimal.ZERO.setScale(12, RoundingMode.UNNECESSARY);
        var d1 = BigDecimal.TEN.pow(25).setScale(12, RoundingMode.UNNECESSARY);
        var d2 = BigDecimal.TEN.pow(25).negate().setScale(12, RoundingMode.UNNECESSARY);
        var d3 = new BigDecimal("0.000000000001").setScale(12, RoundingMode.UNNECESSARY);
        var d4 = new BigDecimal("-0.000000000001").setScale(12, RoundingMode.UNNECESSARY);

        decimalVec.set(0, d0);
        decimalVec.setNull(1);
        decimalVec.set(2, d1);
        decimalVec.set(3, d2);
        decimalVec.set(4, d3);
        decimalVec.set(5, d4);

        var root = new VectorSchemaRoot(List.of(field), List.of(decimalVec));
        root.setRowCount(6);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseStrings() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_STRING, null);
        var field = new Field("string_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var stringVec = new VarCharVector("string_field", allocator);
        stringVec.allocateNew(10);

        stringVec.set(0, "hello".getBytes(StandardCharsets.UTF_8));
        stringVec.setNull(1);
        stringVec.set(2, "".getBytes(StandardCharsets.UTF_8));
        stringVec.set(3, " ".getBytes(StandardCharsets.UTF_8));
        stringVec.set(4, "\r\n\t".getBytes(StandardCharsets.UTF_8));
        stringVec.set(5, "\\\"/\\//\\".getBytes(StandardCharsets.UTF_8));
        stringVec.set(6, " hello\nworld ".getBytes(StandardCharsets.UTF_8));
        stringVec.set(7, "你好世界".getBytes(StandardCharsets.UTF_8));
        stringVec.set(8, "مرحبا بالعالم".getBytes(StandardCharsets.UTF_8));
        stringVec.set(9, "\0".getBytes(StandardCharsets.UTF_8));

        var root = new VectorSchemaRoot(List.of(field), List.of(stringVec));
        root.setRowCount(10);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseDates() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_DATE, null);
        var field = new Field("date_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var dateVec = new DateDayVector("date_field", allocator);
        dateVec.allocateNew(6);

        dateVec.set(0, (int) LocalDate.EPOCH.toEpochDay());
        dateVec.setNull(1);
        dateVec.set(2, (int) LocalDate.of(2001, 1, 1).toEpochDay());
        dateVec.set(3, (int) LocalDate.of(2038, 1, 20).toEpochDay());

        var minDate = LocalDate.ofEpochDay(Integer.MIN_VALUE);
        var maxDate = LocalDate.ofEpochDay(Integer.MAX_VALUE);

        dateVec.set(4, (int) minDate.toEpochDay());
        dateVec.set(5, (int) maxDate.toEpochDay());

        Assertions.assertEquals(minDate, LocalDate.ofEpochDay(dateVec.get(4)));
        Assertions.assertEquals(maxDate, LocalDate.ofEpochDay(dateVec.get(5)));

        var root = new VectorSchemaRoot(List.of(field), List.of(dateVec));
        root.setRowCount(6);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    @Test
    void edgeCaseDateTimes() throws Exception {

        var allocator = new RootAllocator();

        var fieldType = new FieldType(true, ArrowSchema.ARROW_BASIC_DATETIME, null);
        var field = new Field("datetime_field", fieldType, null);
        var arrowSchema = new Schema(List.of(field));
        var tracSchema = ArrowSchema.arrowToTrac(arrowSchema);

        var datetimeVec = new TimeStampMilliVector("datetime_field", allocator);
        datetimeVec.allocateNew(6);

        datetimeVec.set(0, toEpochMillis(LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC)));
        datetimeVec.setNull(1);
        datetimeVec.set(2, toEpochMillis(LocalDateTime.of(2000, 1, 1, 0, 0, 0)));
        datetimeVec.set(3, toEpochMillis(LocalDateTime.of(2038, 1, 19, 3, 14, 8)));

        // Fractional seconds before and after the epoch
        // Test fractions for both positive and negative encoded values
        datetimeVec.set(3, toEpochMillis(LocalDateTime.of(1972, 1, 1, 0, 0, 0, 500000000)));
        datetimeVec.set(3, toEpochMillis(LocalDateTime.of(1968, 1, 1, 23, 59, 59, 500000000)));

        var minDatetime = fromEpochMillis(Integer.MIN_VALUE);
        var maxDatetime = fromEpochMillis(Integer.MAX_VALUE);

        datetimeVec.set(4, toEpochMillis(minDatetime));
        datetimeVec.set(5, toEpochMillis(maxDatetime));

        Assertions.assertEquals(minDatetime, fromEpochMillis(datetimeVec.get(4)));
        Assertions.assertEquals(maxDatetime, fromEpochMillis(datetimeVec.get(5)));

        var root = new VectorSchemaRoot(List.of(field), List.of(datetimeVec));
        root.setRowCount(6);

        roundTrip_impl(tracSchema, arrowSchema, root, allocator);
    }

    private long toEpochMillis(LocalDateTime localDateTime) {

        return localDateTime.toEpochSecond(ZoneOffset.UTC) * 1000 + localDateTime.getNano() / 1000000;
    }

    private LocalDateTime fromEpochMillis(long epochMillis) {

        var epochSeconds = epochMillis / 1000;
        var nanos = (epochMillis % 1000) * 1000000;

        if (epochSeconds < 0 && nanos != 0) {
            --epochSeconds;
            nanos += 1000000000;
        }

        return LocalDateTime.ofEpochSecond(epochSeconds, (int) nanos, ZoneOffset.UTC);
    }

    void roundTrip_impl(
            SchemaDefinition tracSchema, Schema arrowSchema,
            VectorSchemaRoot root, RootAllocator allocator) throws Exception {

        var unloader = new VectorUnloader(root);
        var batch = unloader.getRecordBatch();
        var schemaBlock = DataBlock.forSchema(arrowSchema);
        var dataBlock = DataBlock.forRecords(batch);
        var blockStream = Flows.publish(Stream.of(schemaBlock, dataBlock));

        var encoder = codec.getEncoder(allocator, tracSchema, Map.of());
        var decoder = codec.getDecoder(allocator, tracSchema, Map.of());

        blockStream.subscribe(encoder);
        encoder.subscribe(decoder);

        var roundTrip = Flows.fold(decoder, (bs, b) -> {bs.add(b); return bs;}, new ArrayList<DataBlock>());
        waitFor(TEST_TIMEOUT, roundTrip);
        var rtBlocks = resultOf(roundTrip);

        Assertions.assertEquals(2, rtBlocks.size());
        Assertions.assertEquals(arrowSchema, rtBlocks.get(0).arrowSchema);

        compareBatchToRoot(
                arrowSchema, rtBlocks.get(0).arrowSchema,
                root, rtBlocks.get(1).arrowRecords, allocator);

        var rtBatch = rtBlocks.get(1).arrowRecords;
        rtBatch.close();
        root.close();
        batch.close();
    }

    @Test
    @EnabledIf(value = "basicDataAvailable", disabledReason = "Pre-saved test data not available for this format")
    void decode_basic() throws Exception {

        var allocator = new RootAllocator();
        var arrowSchema = ArrowSchema.tracToArrow(SampleData.BASIC_TABLE_SCHEMA);
        var root = generateBasicData(allocator);

        var testData = TestResourceHelpers.loadResourceAsBytes(basicData);
        var testDataBuf = Unpooled.wrappedBuffer(testData);
        var testDataStream = Flows.publish(List.of(testDataBuf));

        var decoder = codec.getDecoder(allocator, SampleData.BASIC_TABLE_SCHEMA, Map.of());
        testDataStream.subscribe(decoder);

        var roundTrip = Flows.fold(decoder, (bs, b) -> {bs.add(b); return bs;}, new ArrayList<DataBlock>());
        waitFor(TEST_TIMEOUT, roundTrip);
        var rtBlocks = resultOf(roundTrip);

        Assertions.assertEquals(2, rtBlocks.size());
        Assertions.assertEquals(arrowSchema, rtBlocks.get(0).arrowSchema);

        compareBatchToRoot(
                arrowSchema, rtBlocks.get(0).arrowSchema,
                root, rtBlocks.get(1).arrowRecords, allocator);

        var rtBatch = rtBlocks.get(1).arrowRecords;
        rtBatch.close();
        root.close();
    }

    @Test
    void decode_empty() {

        var allocator = new RootAllocator();

        // An empty stream (i.e. with no buffers)

        var noBufStream = Flows.publish(List.<ByteBuf>of());
        var noBufDecoder = codec.getDecoder(allocator, SampleData.BASIC_TABLE_SCHEMA, Map.of());
        noBufStream.subscribe(noBufDecoder);

        var noBufResult = Flows.fold(noBufDecoder, (bs, b) -> {bs.add(b); return bs;}, new ArrayList<DataBlock>());
        waitFor(TEST_TIMEOUT, noBufResult);

        Assertions.assertThrows(EDataCorruption.class, () -> resultOf(noBufResult));

        // A stream containing a series of empty buffers

        var emptyBuffers = List.of(
                new EmptyByteBuf(ByteBufAllocator.DEFAULT),
                new EmptyByteBuf(ByteBufAllocator.DEFAULT),
                new EmptyByteBuf(ByteBufAllocator.DEFAULT));

        var emptyBufStream = Flows.publish(emptyBuffers);
        var emptyBufDecoder = codec.getDecoder(allocator, SampleData.BASIC_TABLE_SCHEMA, Map.of());
        emptyBufStream.subscribe(emptyBufDecoder);

        var emptyBufResult = Flows.fold(emptyBufDecoder, (bs, b) -> {bs.add(b); return bs;}, new ArrayList<DataBlock>());
        waitFor(TEST_TIMEOUT, emptyBufResult);

        Assertions.assertThrows(EDataCorruption.class, () -> resultOf(emptyBufResult));
    }

    @Test
    void decode_garbled() {

        // Send a stream of random bytes - 3 chunks worth

        var testData = List.of(
                new byte[10000],
                new byte[10000],
                new byte[10000]);

        var random = new Random();
        testData.forEach(random::nextBytes);

        var testDataBuf = testData.stream().map(Unpooled::wrappedBuffer).collect(Collectors.toList());
        var testDataStream = Flows.publish(testDataBuf);

        // Run the garbage data through the decoder

        var allocator = new RootAllocator();
        var decoder = codec.getDecoder(allocator, SampleData.BASIC_TABLE_SCHEMA, Map.of());
        testDataStream.subscribe(decoder);

        // Decoder should report EDataCorruption

        var decodeResult = Flows.fold(decoder, (bs, b) -> {bs.add(b); return bs;}, new ArrayList<DataBlock>());
        waitFor(TEST_TIMEOUT, decodeResult);

        Assertions.assertThrows(EDataCorruption.class, () -> resultOf(decodeResult));
    }


    private void compareBatchToRoot(
            Schema expectedSchema, Schema rtSchema,
            VectorSchemaRoot root, ArrowRecordBatch rtBatch, BufferAllocator allocator) {

        Assertions.assertEquals(expectedSchema, rtSchema);

        var rtFields = rtSchema.getFields();
        var rtVectors = rtFields.stream().map(f -> f.createVector(allocator)).collect(Collectors.toList());

        try (var rtRoot = new VectorSchemaRoot(rtFields, rtVectors)) {

            var rtLoader = new VectorLoader(rtRoot);
            rtLoader.load(rtBatch);

            Assertions.assertEquals(root.getRowCount(), rtRoot.getRowCount());

            for (var j = 0; j < root.getFieldVectors().size(); j++) {

                var vec = root.getVector(j);
                var rtVec = rtRoot.getVector(j);

                for (int i = 0; i < root.getRowCount(); i++)
                    Assertions.assertEquals(vec.getObject(i), rtVec.getObject(i), "Mismatch on row " + i);
            }
        }
    }
}