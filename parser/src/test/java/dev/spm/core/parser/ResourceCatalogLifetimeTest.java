package dev.spm.core.parser;

import dev.spm.core.api.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceCatalogLifetimeTest {
    @Test void catalogIsPreservedAndStreamsPinTheImportedOwner() throws Exception {
        var retired=new AtomicBoolean();
        try(var builder=new ParserResultBuilder()) {
            builder.retain(()->retired.set(true));
            var key=new ResourceKey("functions/test.molang");
            builder.resources(new ResourceCatalog() {
                @Override public Set<ResourceKey> entries() { return Set.of(key); }
                @Override public InputStream open(ResourceKey requested) { return new ByteArrayInputStream(new byte[]{7}); }
            });
            var imported=builder.finish();
            var catalog=assertInstanceOf(ResourceCatalog.class,imported.resources());
            assertEquals(Set.of(key),catalog.entries());
            assertThrows(UnsupportedOperationException.class,()->catalog.entries().clear());
            var stream=catalog.open(key);imported.close();
            assertFalse(retired.get());assertEquals(7,stream.read());stream.close();assertTrue(retired.get());
            assertThrows(IllegalStateException.class,catalog::entries);
        }
    }
}
