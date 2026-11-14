package dev.spm.core.parser;

import dev.spm.core.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class FiguraParserIntegrationTest {
    @Test void explicitAndAutoFiguraAssetsUseTheOwnedUnifiedParserPath()throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("avatar.json"));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("model.bbmodel"));zip.write("{\"meta\":{\"format_version\":\"4.10\",\"model_format\":\"free\"},\"name\":\"owned\",\"elements\":[],\"outliner\":[]}".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("image.png"));zip.write(new byte[]{4,5,6});zip.closeEntry();
        }
        for(FormatHint hint:new FormatHint[]{FormatHint.FIGURA,FormatHint.AUTO}) {
            var budget=new ResourceBudget(1<<20);var source=new ModelSource("owned",new ResourceKey("owned.figura.zip"),key->new ByteArrayInputStream(bytes.toByteArray()));
            var result=new SpmModelParser().importModel(new ImportRequest(source,hint,DecodePolicy.conservative(),budget,new CancellationToken()));
            assertEquals(1,result.models().size());var lease=result.models().get(0).asset().acquire();
            var stream=result.resources().open(new ResourceKey("image.png"));result.close();assertNotNull(lease.document());assertTrue(budget.used()>0);
            lease.close();assertTrue(budget.used()>0);assertArrayEquals(new byte[]{4,5,6},stream.readAllBytes());stream.close();assertEquals(0,budget.used());
        }
    }
}
