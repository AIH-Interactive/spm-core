package dev.spm.core.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLeaseTest {
    @Test void sameReferenceReplacementDoesNotCloseActiveGenerationOrDebitNewOne() {
        var registry=new ResourceRegistry<String>();
        AtomicInteger oldClosed=new AtomicInteger(),nextClosed=new AtomicInteger();
        long oldGeneration=registry.publish("same","old",oldClosed::incrementAndGet);
        var old=registry.acquire("same").orElseThrow();
        long nextGeneration=registry.publish("same","new",nextClosed::incrementAndGet);
        assertTrue(nextGeneration>oldGeneration);
        assertEquals(0,oldClosed.get());
        var next=registry.acquire("same").orElseThrow();
        assertEquals("old",old.value());assertEquals("new",next.value());
        old.close();old.close();assertEquals(1,oldClosed.get());assertEquals(0,nextClosed.get());
        registry.close();assertTrue(registry.acquire("same").isEmpty());assertEquals(0,nextClosed.get());
        next.close();next.close();assertEquals(1,nextClosed.get());
    }
    @Test void staleAsynchronousPublicationCannotReplaceNewerWorkAndIsClosed() {
        var registry=new ResourceRegistry<String>();AtomicInteger closed=new AtomicInteger();
        long first=registry.begin("model"),second=registry.begin("model");
        assertFalse(registry.publishIfCurrent("model",first,"stale",closed::incrementAndGet));
        assertEquals(1,closed.get());
        assertTrue(registry.publishIfCurrent("model",second,"fresh",closed::incrementAndGet));
        try(var lease=registry.acquire("model").orElseThrow()){assertEquals("fresh",lease.value());}
        registry.retire("model");assertTrue(registry.acquire("model").isEmpty());
        assertEquals(2,closed.get());
    }
    @Test void publishAfterShutdownClosesTheRejectedAssetInsteadOfDroppingIt() {
        var registry=new ResourceRegistry<String>();AtomicInteger closed=new AtomicInteger();
        registry.publish("model","first",()->{});
        registry.close();
        assertThrows(IllegalStateException.class,()->registry.publish("model","late",closed::incrementAndGet));
        assertEquals(1,closed.get(),"a late publish must close its asset rather than leak it");
        assertThrows(IllegalStateException.class,()->registry.begin("model"));
    }
    @Test void aLeaseOutlivingItsRetiredGenerationIsReportedRatherThanForgotten() {
        var registry=new ResourceRegistry<String>();AtomicInteger closed=new AtomicInteger();
        registry.publish("model","value",closed::incrementAndGet);
        var lease=registry.acquire("model").orElseThrow();
        registry.retire("model");
        assertEquals(0,closed.get(),"a leased asset is not closed while the lease lives");
        assertTrue(registry.leakedLeases().contains("model"));
        registry.close();
        assertEquals(0,closed.get());
        assertTrue(registry.leakedLeases().contains("model"),"the leak stays visible after close");
        lease.close();
        assertEquals(1,closed.get(),"returning the last lease finally closes the asset");
        assertTrue(registry.leakedLeases().isEmpty());
    }
    @Test void returnedLeaseRefusesAccessEvenWhileTheGenerationIsStillCurrent() {
        var registry=new ResourceRegistry<String>();
        registry.publish("model","value",()->{});
        var lease=registry.acquire("model").orElseThrow();
        assertEquals("value",lease.value());
        lease.close();
        assertThrows(IllegalStateException.class,lease::value);
        lease.close();
        registry.close();
    }
}
