package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

final class YsmWriterHistoricalTest {
    private static byte[] sample(String name) throws IOException {
        try (var input = YsmWriterHistoricalTest.class.getResourceAsStream("/ysm/historical/" + name + ".wire")) {
            assertNotNull(input); return input.readAllBytes();
        }
    }
    @Test void reconstructsAll32WireVersionsFromTypedFieldsByteForByte() throws Exception {
        var names = new ArrayList<String>();
        for (int version = 1; version <= 32; version++) names.add("v" + version);
        names.add("v5-face"); names.add("v32-infinite-animation");
        for (String name : names) {
            byte[] original = sample(name);
            var budget = new ResourceBudget(4L << 20);
            try (var model = YsmHistoricalCodec.decode(original, DecodePolicy.conservative(), budget, new CancellationToken())) {
                long before = budget.used();
                try (var written = YsmHistoricalCodec.encode(model, DecodePolicy.conservative(), budget, new CancellationToken())) {
                    assertArrayEquals(original, written.bytes(), name);
                    assertEquals(model.innerVersion(), written.innerVersion());
                    assertEquals(before + original.length, budget.used());
                }
                assertEquals(before, budget.used());
            }
            assertEquals(0, budget.used());
        }
    }
    @Test void editsTypedFieldsAndRejectsExtraSchemaFieldsInsteadOfPassingThroughInput() throws Exception {
        var policy = DecodePolicy.conservative(); var budget = new ResourceBudget(4L << 20);
        try (var model = YsmHistoricalCodec.decode(sample("v32"), policy, budget, new CancellationToken())) {
            var fields = new LinkedHashMap<>(model.root().fields()); fields.put("order_info", new Text("edited content"));
            try (var wire = YsmHistoricalCodec.encodeRoot(32, new Struct(fields), policy, budget, new CancellationToken());
                 var parsed = YsmHistoricalCodec.decode(wire.bytes(), policy, budget, new CancellationToken())) {
                assertEquals("edited content", ((Text) parsed.root().field("order_info")).value());
            }
            fields.put("unrecognized_field", new Unsigned(32, 7));
            long before = budget.used();
            assertThrows(IOException.class, () -> YsmHistoricalCodec.encodeRoot(32, new Struct(fields), policy, budget, new CancellationToken()));
            assertEquals(before, budget.used());
            var cancelled = new CancellationToken(); cancelled.cancel();
            assertThrows(CancellationException.class, () -> YsmHistoricalCodec.encode(model, policy, budget, cancelled));
            for (DecodePolicy limit : List.of(new DecodePolicy(4, 4, 1000, 1000, 100),
                    new DecodePolicy(10000, 10000, 1, 1000, 100), new DecodePolicy(10000,10000,1000,1000,1))) {
                assertThrows(IOException.class, () -> YsmHistoricalCodec.encode(model, limit, budget, new CancellationToken()));
                assertEquals(before, budget.used());
            }
        }
        assertEquals(0, budget.used());
    }

    @Test void writesLatin1SourceHashesWithoutUtf8ReplacementAndRejectsUnmappableHashes() throws Exception {
        var policy = DecodePolicy.conservative(); var budget = new ResourceBudget(4L << 20);
        try (var model = YsmHistoricalCodec.decode(sample("v32"), policy, budget, new CancellationToken())) {
            var common = (Struct) model.root().field("common");
            var commonFields = new LinkedHashMap<>(common.fields());
            var wrapped = new LinkedHashMap<String, HistoricalValue>();
            wrapped.put("hash", new Text("\u00ff\u0080", true)); wrapped.put("value", new Text("return 1;"));
            commonFields.put("functions", new Mapping(List.of(new Entry(new Text("function"), new Struct(wrapped)))));
            var fields = new LinkedHashMap<>(model.root().fields()); fields.put("common", new Struct(commonFields));
            try (var encoded = YsmHistoricalCodec.encodeRoot(32, new Struct(fields), policy, budget, new CancellationToken());
                 var decoded = YsmHistoricalCodec.decode(encoded.bytes(), policy, budget, new CancellationToken())) {
                var functions = (Mapping) ((Struct) decoded.root().field("common")).field("functions");
                var hash = (Text) ((Struct) functions.entries().get(0).value()).field("hash");
                assertEquals("\u00ff\u0080", hash.value()); assertTrue(hash.hashEncoding());
            }
            wrapped.put("hash", new Text("\u0100", true));
            commonFields.put("functions", new Mapping(List.of(new Entry(new Text("function"), new Struct(wrapped)))));
            fields.put("common", new Struct(commonFields));
            long before = budget.used();
            assertThrows(IOException.class, () -> YsmHistoricalCodec.encodeRoot(32, new Struct(fields), policy, budget, new CancellationToken()));
            assertEquals(before, budget.used());
        }
        assertEquals(0, budget.used());
    }
}
