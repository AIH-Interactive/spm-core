package dev.spm.core.verification;

import dev.spm.core.api.ModelDocument;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiIsolationTest {
    @Test void compiledApiConstantPoolsHaveNoHostOrNativeDependencies() throws Exception {
        Path classes=Path.of(ModelDocument.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Gradle's project dependency supplies the API jar rather than a classes directory.
        if(Files.isRegularFile(classes)) {
            try(var jar=new java.util.jar.JarFile(classes.toFile())) {
                var entries=jar.entries();int count=0;
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();if(!entry.getName().endsWith(".class"))continue;
                    try(var input=jar.getInputStream(entry)){check(input.readAllBytes());}count++;
                }
                assertTrue(count>10);
            }
        } else {
            try(var files=Files.walk(classes)) {
                var all=files.filter(p->p.toString().endsWith(".class")).toList();assertTrue(all.size()>10);
                for(var file:all)check(Files.readAllBytes(file));
            }
        }
    }
    private static void check(byte[] bytes) {
        String constants=new String(bytes,StandardCharsets.ISO_8859_1);
        for(String forbidden:new String[]{"net/minecraft/","net/fabricmc/","net/neoforged/","org/joml/",
                "io/netty/","com/google/gson/","org/lwjgl/","dev/spm/core/nativejni/","jdk/incubator/vector/"})
            assertFalse(constants.contains(forbidden),forbidden);
    }
}
