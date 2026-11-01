package dev.spm.core.verification;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static dev.spm.core.format.ysm.HistoricalValue.*;
import static org.junit.jupiter.api.Assertions.*;

/** Handwritten schema fixture for present optionals/media; no runtime encoder is involved. */
final class YsmRichWireTest {
    @Test void preservesMediaProgramsControllerBlendPointsAndUnsignedTimestamp() throws Exception {
        var budget = new ResourceBudget(1 << 20);
        try (var decoded = YsmHistoricalCodec.decode(rich(), DecodePolicy.conservative(), budget, new CancellationToken())) {
            Struct root = decoded.root();
            Struct common = s(root.field("common"));
            Struct sound = s(m(common.field("sounds")).entries().get(0).value());
            Binary data = (Binary) sound.field("value");
            assertArrayEquals(new byte[] {0, (byte) 255, 9}, data.value());
            byte[] borrowedCopy = data.value(); borrowedCopy[0] = 99;
            assertEquals(0, data.value()[0]);
            Struct player = s(((Presence) root.field("player")).value());
            Struct file = s(s(m(player.field("animations")).entries().get(0).value()).field("value"));
            Struct animation = s(((Sequence) file.field("animations")).values().get(0));
            assertEquals(0x7fc12345, ((Float32) animation.field("length")).rawBits());
            assertTrue(((Presence) animation.field("override_previous")).present());
            assertTrue(((Bool) ((Presence) animation.field("override_previous")).value()).value());
            assertFalse(((Presence) animation.field("weight")).present());
            Struct controllerFile = s(s(m(player.field("controllers")).entries().get(0).value()).field("value"));
            Struct controller = s(m(controllerFile.field("controllers")).entries().get(0).value());
            Struct state = s(m(controller.field("states")).entries().get(0).value());
            assertFalse(((Presence) state.field("length")).present());
            Entry point = m(state.field("points")).entries().get(0);
            assertEquals(0x80000000, ((Float32) point.key()).rawBits());
            assertEquals(0x7fa23456, ((Float32) point.value()).rawBits());
            Struct export = s(((Presence) root.field("export")).value());
            Unsigned timestamp = (Unsigned) export.field("timestamp");
            assertEquals(64, timestamp.width()); assertEquals(-1, timestamp.bits());
            assertEquals(0xffffffffL, ((Unsigned) s(root.field("info")).field("origin")).bits());
            assertEquals("order", ((Text) root.field("order_info")).value());
        }
        assertEquals(0, budget.used());
    }

    private static Struct s(HistoricalValue value) { return (Struct) value; }
    private static Mapping m(HistoricalValue value) { return (Mapping) value; }

    private static byte[] rich() throws IOException {
        var w = new W(); w.fixed(32);
        // Common resources: one raw audio, function source, and language map.
        w.u(1).text("sound").text("hash").bytes(new byte[] {0, (byte) 255, 9});
        w.u(1).text("function").text("").text("return query.life_time;");
        w.u(1).text("en_us").text("").u(1).text("label").text("Name");
        w.u(0).u(0); // vehicles, projectiles
        w.u(1); // player present
        w.u(1).u(1).text("").u(1); // animation type/hash/file list
        w.text("run").fixed(0x7fc12345).u(1);
        w.u(1).u(1).f(1.5f); // start delay: numeric Molang
        w.u(1).u(2).text("variable.delay"); // loop delay: source Molang
        w.u(0).u(1).b(true); // absent weight; present override_previous
        w.u(1).text("bone"); // bone animation
        w.u(1).f(0).u(1); // rotation keyframe tick/easing
        for (int axis = 0; axis < 3; axis++) w.u(1).f(axis);
        w.u(0); // post absent
        w.u(0).u(0); // position and scale keyframes
        w.u(1).u(2).text("a;").text("b;").f(0.5f); // timeline instructions
        w.u(1).text("step").f(0.75f); // animation sound
        w.u(1).text("move").text("").u(1).text("controller").text("default").u(1).text("default");
        w.u(1).text("run").text("true"); // state animations
        w.u(1).text("default").text("query.is_alive"); // transitions
        w.u(1).text("entry;").u(1).text("exit;");
        w.u(0).u(1).fixed(0x80000000).fixed(0x7fa23456); // absent length => raw float blend map
        w.b(false).u(1).text("step");
        w.u(0).u(0); // textures and geometries
        // Info: present metadata with author contact and links.
        w.text("000102030405060708090a0b0c0d0e0f").u(1);
        w.text("name").text("tips").text("license").text("description");
        w.u(1).text("author").text("role").u(1).text("site").text("value").text("comment");
        w.u(1).text("link").text("target");
        w.f(0.7f).f(0.8f).u(0); // properties base
        w.u(1).text("button").text("Button").text("click").u(1);
        w.text("slider").text("title").text("description").text("value").f(1).f(0).f(10).u(1).text("a").text("A");
        w.u(1).text("group").u(1).text("tag").text("value");
        w.text("skin").text("preview");
        for (int i = 0; i < 6; i++) w.b((i & 1) == 0); // free/layers/culling/no_rotation/no_lighting/merge
        w.text("foreground").text("background");
        w.u(1).text("avatar"); image(w);
        w.u(1).text("thumb-icon"); image(w);
        w.u(0xffffffffL);
        w.u(1).text("random").u(-1L).text("export extra").text("order");
        return w.output.toByteArray();
    }
    private static void image(W w) throws IOException { w.bytes(new byte[] {4,5,6}).u(1).u(1).u(3).u(1); }

    private static final class W {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        W u(long bits) {
            do { int next = (int) (bits & 127); bits >>>= 7; output.write(next | (bits == 0 ? 0 : 128)); } while (bits != 0);
            return this;
        }
        W fixed(int value) { for (int i = 0; i < 4; i++) output.write(value >>> (i * 8)); return this; }
        W f(float value) { return fixed(Float.floatToRawIntBits(value)); }
        W b(boolean value) { output.write(value ? 1 : 0); return this; }
        W text(String value) throws IOException { return bytes(value.getBytes(StandardCharsets.UTF_8)); }
        W bytes(byte[] value) throws IOException { u(value.length); output.write(value); return this; }
    }
}
