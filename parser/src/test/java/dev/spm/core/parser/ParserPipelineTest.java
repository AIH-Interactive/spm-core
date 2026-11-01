package dev.spm.core.parser;

import dev.spm.core.api.*;
import dev.spm.core.runtime.ModelFrameRenderer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ParserPipelineTest {
    private static final byte[] BEDROCK=("{\"format_version\":\"1.12.0\",\"minecraft:geometry\":[{\"description\":{\"identifier\":\"geometry.test\",\"texture_width\":16,\"texture_height\":16},\"bones\":[{\"name\":\"root\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[1,1,1],\"uv\":[0,0]}]}]}]}").getBytes(StandardCharsets.UTF_8);
    private static ImportRequest request(byte[] bytes,FormatHint hint,ResourceBudget budget,AtomicInteger closes) {
        return new ImportRequest(new ModelSource("owned-test",new ResourceKey("model.json"),key->new ByteArrayInputStream(bytes){
            @Override public void close() throws IOException {closes.incrementAndGet();super.close();}
        }),hint,DecodePolicy.conservative(),budget,new CancellationToken());
    }
    @Test void autoDetectsAndOwnsDocumentUntilLastRenderedFrameReturns() throws Exception {
        var budget=new ResourceBudget(1<<20);var closes=new AtomicInteger();
        var result=new SpmModelParser().importModel(request(BEDROCK,FormatHint.AUTO,budget,closes));
        assertEquals(1,closes.get());assertEquals(1,result.models().size());assertTrue(result.diagnostics().isEmpty());
        var model=result.models().get(0).asset();var frame=ModelFrameRenderer.render(model,model.document().bindPose(),budget,new CancellationToken());
        result.close();assertTrue(budget.used()>0);assertEquals(1,frame.meshes().size());
        assertThrows(IllegalStateException.class,result::models);
        frame.close();frame.close();assertEquals(0,budget.used());
    }
    @Test void malformedCancelledAndBudgetFailuresHaveStableCodesAndNeverLeak() {
        var budget=new ResourceBudget(1<<20);var closes=new AtomicInteger();var parser=new SpmModelParser();
        var bad=assertThrows(ModelImportException.class,()->parser.importModel(request(new byte[]{1,2,3},FormatHint.AUTO,budget,closes)));
        assertEquals(ImportErrorCode.UNSUPPORTED_FORMAT,bad.diagnostic().code());assertEquals(0,budget.used());assertEquals(1,closes.get());
        var cancelled=request(BEDROCK,FormatHint.BEDROCK,budget,closes);cancelled.cancellation().cancel();
        assertEquals(ImportErrorCode.CANCELLED,assertThrows(ModelImportException.class,()->parser.importModel(cancelled)).diagnostic().code());
        assertEquals(1,closes.get(),"cancelled request must not open source");
        var tiny=new ResourceBudget(1);
        assertEquals(ImportErrorCode.BUDGET_EXCEEDED,assertThrows(ModelImportException.class,()->parser.importModel(request(BEDROCK,FormatHint.BEDROCK,tiny,closes))).diagnostic().code());
        assertEquals(0,tiny.used());
    }
    @Test void encodedInputLimitIsCheckedBeforeGrowingAndSourceIoIsDistinct() {
        var parser=new SpmModelParser();var budget=new ResourceBudget(1<<20);var closes=new AtomicInteger();
        var original=request(BEDROCK,FormatHint.BEDROCK,budget,closes);
        var limited=new ImportRequest(original.source(),original.hint(),new DecodePolicy(8,1<<20,100,100,10),budget,original.cancellation());
        assertEquals(ImportErrorCode.INPUT_LIMIT,assertThrows(ModelImportException.class,()->parser.importModel(limited)).diagnostic().code());
        assertEquals(0,budget.used());assertEquals(1,closes.get());
        var ioSource=new ModelSource("io",new ResourceKey("model.json"),key->new InputStream(){
            @Override public int read() throws IOException {throw new IOException("source unavailable");}
            @Override public void close(){closes.incrementAndGet();}
        });
        var ioRequest=new ImportRequest(ioSource,FormatHint.AUTO,DecodePolicy.conservative(),budget,new CancellationToken());
        assertEquals(ImportErrorCode.SOURCE_IO,assertThrows(ModelImportException.class,()->parser.importModel(ioRequest)).diagnostic().code());
        assertEquals(0,budget.used());assertEquals(2,closes.get());
    }
    @Test void thirdPartyFormatAdapterGetsSameSecurityContextAndAnImmutableBoundedProbe() throws Exception {
        var budget=new ResourceBudget(1<<20);var closes=new AtomicInteger();
        var request=request(new byte[]{'F','U','T','R'},FormatHint.AUTO,budget,closes);
        var called=new AtomicInteger();
        ModelFormatAdapter extension=new ModelFormatAdapter(){
            @Override public String formatId(){return "future-model";}
            @Override public boolean supports(FormatHint hint){return hint==FormatHint.AUTO;}
            @Override public boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix){
                assertTrue(prefix.isReadOnly());assertTrue(prefix.capacity()<=64);
                assertThrows(java.nio.ReadOnlyBufferException.class,()->prefix.put(0,(byte)0));return prefix.get(0)=='F';
            }
            @Override public ImportResult importModel(ImportRequest actual){
                assertSame(request.budget(),actual.budget());assertSame(request.cancellation(),actual.cancellation());
                assertSame(request.policy(),actual.policy());assertEquals(request.source().identity(),actual.source().identity());called.incrementAndGet();
                return new ImportResult(List.of(),List.of(),key->new ByteArrayInputStream(new byte[0]),()->{});
            }
        };
        try(var result=new SpmModelParser(List.of(extension)).importModel(request)){assertTrue(result.models().isEmpty());}
        assertEquals(1,called.get());assertEquals(0,budget.used());assertEquals(1,closes.get());
        assertThrows(IllegalArgumentException.class,()->new SpmModelParser(List.of(extension,extension)));
    }
    @Test void failingSourceCloseCannotLoseTheCompletedSnapshotReservation(){
        var budget=new ResourceBudget(1<<20);
        var source=new ModelSource("close-fault",new ResourceKey("model.json"),key->new ByteArrayInputStream(BEDROCK){
            @Override public void close() throws IOException {throw new IOException("injected source close failure");}
        });
        var request=new ImportRequest(source,FormatHint.BEDROCK,DecodePolicy.conservative(),budget,new CancellationToken());
        assertEquals(ImportErrorCode.SOURCE_IO,assertThrows(ModelImportException.class,()->new SpmModelParser().importModel(request)).diagnostic().code());
        assertEquals(0,budget.used(),"failed source close must not orphan an already constructed snapshot");
    }
    @Test void extensionParsesTheExactProbedBytesFromAOneShotSourceAndPinsOpenStreams() throws Exception {
        var budget=new ResourceBudget(1<<20);var opens=new AtomicInteger();byte[] bytes={'F','U','T','R'};byte[] expected=bytes.clone();
        var source=new ModelSource("one-shot",new ResourceKey("root.future"),key->{
            if(opens.incrementAndGet()!=1)throw new IOException("Source is not replayable");return new ByteArrayInputStream(bytes);
        });
        var request=new ImportRequest(source,FormatHint.AUTO,DecodePolicy.conservative(),budget,new CancellationToken());
        ModelFormatAdapter extension=new ModelFormatAdapter(){
            @Override public String formatId(){return "one-shot-format";}
            @Override public boolean supports(FormatHint hint){return hint==FormatHint.AUTO;}
            @Override public boolean accepts(FormatHint hint,ResourceKey entry,ByteBuffer prefix){boolean matches=prefix.get(0)=='F';bytes[0]='X';return matches;}
            @Override public ImportResult importModel(ImportRequest actual) throws ModelImportException {
                try(var input=actual.source().resolver().open(actual.source().entry())){assertArrayEquals(expected,input.readAllBytes());}
                catch(IOException io){throw new ModelImportException(new ImportDiagnostic(ImportDiagnostic.Severity.ERROR,ImportErrorCode.SOURCE_IO,source.entry(),-1,io.getMessage()),io);}
                return new ImportResult(List.of(),List.of(),actual.source().resolver(),()->{});
            }
        };
        var result=new SpmModelParser(List.of(extension)).importModel(request);
        var stream=result.resources().open(source.entry());result.close();
        assertEquals(1,opens.get());assertTrue(budget.used()>0,"snapshot must remain charged for the borrowed stream");
        assertArrayEquals(expected,stream.readAllBytes());stream.close();stream.close();assertEquals(0,budget.used());
    }
}
