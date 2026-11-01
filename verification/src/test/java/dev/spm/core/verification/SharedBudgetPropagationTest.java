package dev.spm.core.verification;

import dev.spm.core.api.ResourceBudget;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedBudgetPropagationTest {
    @Test void childWorkersCannotEachSpendTheWholeSharedLimit() throws Exception {
        var budget=new ResourceBudget(100);var acquired=new CountDownLatch(1);var release=new CountDownLatch(1);
        var workers=Executors.newFixedThreadPool(2);
        try {
            var first=workers.submit(()->{try(var reservation=budget.reserve(70)){assertEquals(70,reservation.bytes());acquired.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Release timeout");}return true;});
            assertTrue(acquired.await(5,TimeUnit.SECONDS));
            var second=workers.submit(()->assertThrows(IllegalStateException.class,()->budget.reserve(31)));
            second.get(5,TimeUnit.SECONDS);assertEquals(70,budget.used());
            try(var exact=budget.reserve(30)){assertEquals(30,exact.bytes());assertEquals(100,budget.used());}
            release.countDown();assertTrue(first.get(5,TimeUnit.SECONDS));assertEquals(0,budget.used());
        } finally {release.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
