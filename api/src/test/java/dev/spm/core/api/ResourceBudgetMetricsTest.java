package dev.spm.core.api;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceBudgetMetricsTest {
    @Test void anOwnerThatSilentlyRefusesInsertionCannotKeepCredits(){
        var budget=new ResourceBudget(100);
        var refused=new java.util.ArrayList<ResourceBudget.Reservation>(){
            private static final long serialVersionUID=1L;
            @Override public boolean add(ResourceBudget.Reservation ticket){return false;}
        };
        assertThrows(IllegalStateException.class,()->budget.reserve(70,refused));assertEquals(0,budget.used());
    }
    @Test void failedOwnerRegistrationReturnsTheAlreadyAdmittedCredits(){
        var budget=new ResourceBudget(100);
        var rejected=new java.util.ArrayList<ResourceBudget.Reservation>(){
            private static final long serialVersionUID=1L;
            @Override public boolean add(ResourceBudget.Reservation ticket){throw new OutOfMemoryError("injected owner growth failure");}
        };
        assertThrows(OutOfMemoryError.class,()->budget.reserve(70,rejected));
        assertEquals(0,budget.used());assertEquals(70,budget.peakUsed());
    }
    @Test void peakTracksAdmittedConcurrentCreditsAndNeverResetsOnRelease(){
        var budget=new ResourceBudget(100);
        assertEquals(0,budget.peakUsed());
        try(var first=budget.reserve(70)){
            assertEquals(70,first.bytes());assertEquals(70,budget.peakUsed());
            try(var second=budget.reserve(20)){assertEquals(20,second.bytes());assertEquals(90,budget.peakUsed());}
            assertThrows(ResourceBudgetExceededException.class,()->budget.reserve(31));
            assertEquals(90,budget.peakUsed());
        }
        assertEquals(0,budget.used());assertEquals(90,budget.peakUsed());
    }
}
