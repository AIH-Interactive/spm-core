package dev.spm.core.scene;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MolangValueTest {
    @Test void booleansKeepTheirTypeAndCoerceAtNumericConsumers() {
        assertEquals(1, MolangValue.bool(true).number());
        assertEquals(0, MolangValue.bool(false).number());
        assertTrue(MolangValue.bool(true).truth());
        assertFalse(MolangValue.bool(false).truth());
        assertNotEquals(MolangValue.number(0), MolangValue.bool(false));
        assertNotEquals(MolangValue.text(""), MolangValue.bool(false));
        assertTrue(new MolangValue.BooleanValue(true).value());
        assertSame(MolangValue.missing(), MolangValue.bool(true).field("x"));
    }

    @Test void numericCoercionAndTruthKeepMissingDistinctFromZero() {
        assertEquals(0, MolangValue.missing().number());
        assertFalse(MolangValue.missing().truth());
        assertFalse(MolangValue.number(-0.0).truth());
        assertTrue(MolangValue.number(-2).truth());
        assertEquals(2, MolangValue.number(2).number());
        assertEquals(1, MolangValue.text("").number());
        assertTrue(MolangValue.text("").truth());
        assertEquals(1, MolangValue.struct(Map.of()).number());
        assertTrue(MolangValue.struct(Map.of()).truth());
        assertSame(MolangValue.Missing.INSTANCE, MolangValue.missing());
        assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(MolangValue.number(-0.0).number()));
    }

    @Test void scalarsRejectInvalidInputsAndPreserveTextCase() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> MolangValue.number(invalid));
            assertThrows(IllegalArgumentException.class, () -> new MolangValue.NumberValue(invalid));
        }
        assertEquals("MiXeD", MolangValue.text("MiXeD").value());
        assertDoesNotThrow(() -> MolangValue.text("x".repeat(65_536)));
        assertThrows(IllegalArgumentException.class, () -> MolangValue.text("x".repeat(65_537)));
        assertThrows(NullPointerException.class, () -> MolangValue.text(null));
    }

    @Test void structsAreImmutableAndResolveCaseInsensitiveNestedPaths() {
        var source = new HashMap<String, MolangValue>();
        source.put("Child", MolangValue.struct(Map.of("NaMe", MolangValue.text("Value"))));
        source.put("Gone", MolangValue.missing());
        var value = MolangValue.struct(source);
        source.clear();
        assertEquals(MolangValue.text("Value"), value.field("CHILD.name"));
        assertSame(MolangValue.missing(), value.field("gone"));
        assertSame(MolangValue.missing(), value.field("unknown.name"));
        assertSame(MolangValue.missing(), value.field("child.name.more"));
        assertSame(MolangValue.missing(), MolangValue.number(1).field("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> value.fields().clear());
        Map<String, MolangValue> duplicates = Map.of("A", MolangValue.number(1), "a", MolangValue.number(2));
        assertThrows(IllegalArgumentException.class, () -> MolangValue.struct(duplicates));
        var nullValue = new HashMap<String, MolangValue>();
        nullValue.put("x", null);
        assertThrows(NullPointerException.class, () -> MolangValue.struct(nullValue));
    }

    @Test void structBoundsCountAllNestedFieldsAndDepth() {
        var fields = new HashMap<String, MolangValue>();
        for (int i = 0; i < 4096; i++) fields.put("f" + i, MolangValue.number(i));
        var full = MolangValue.struct(fields);
        assertEquals(4095, full.field("f4095").number());
        assertThrows(IllegalArgumentException.class, () -> MolangValue.struct(Map.of("nested", full)));
        fields.put("overflow", MolangValue.number(1));
        assertThrows(IllegalArgumentException.class, () -> MolangValue.struct(fields));
        MolangValue nested = MolangValue.struct(Map.of());
        for (int i = 1; i < 32; i++) nested = MolangValue.struct(Map.of("child", nested));
        MolangValue deepest = nested;
        assertThrows(IllegalArgumentException.class, () -> MolangValue.struct(Map.of("child", deepest)));
    }
}
