package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.format.ysm.YsmHistoricalCodec;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ParserPolicyTest {
    @Test void resourceCountDoesNotAccidentallyLimitTheWholeDecodedObjectTree() throws Exception {
        byte[] wire=Files.readAllBytes(Path.of("../verification/src/test/resources/ysm/historical/v1.wire"));
        var budget=new ResourceBudget(1<<20);
        var policy=new DecodePolicy(1<<20,1<<20,1,10000,64,10000,10000);
        try(var historical=YsmHistoricalCodec.decode(wire,policy,budget,new CancellationToken())){assertEquals(1,historical.innerVersion());}
        assertEquals(0,budget.used());
        var tooFewObjects=new DecodePolicy(1<<20,1<<20,10000,10000,64,1,10000);
        var error=assertThrows(DecodeLimitException.class,()->YsmHistoricalCodec.decode(wire,tooFewObjects,budget,new CancellationToken()));
        assertEquals(ImportErrorCode.OBJECT_LIMIT,error.code());assertEquals(0,budget.used());
    }
    @Test void oldPoliciesKeepTheirLimitsAndLocalModelsOptIntoSeparateObjectCredits() {
        var old=new DecodePolicy(1000,2000,3,100,8);
        assertEquals(3,old.maxObjects());assertEquals(3,old.maxCollectionEntries());
        var local=DecodePolicy.localModel();
        assertEquals(16384,local.maxEntries());assertTrue(local.maxObjects()>local.maxEntries());
        assertEquals(16384,DecodePolicy.conservative().maxObjects());
    }
}
