package dev.spm.core.format.ysm;

import dev.spm.core.api.*;
import java.io.IOException;

/** Independent lowering from archival historical quads to the Core runtime representation. */
public final class YsmModelImporter {
    private YsmModelImporter() { }
    /** Transfers ownership of the historical model on success; the caller retains it after failure. */
    public static YsmImportResult importPayload(YsmHistoricalModel historical, DecodePolicy policy,
                                               ResourceBudget budget, CancellationToken token) throws IOException {
        return HistoricalLowering.lower(historical,policy,budget,token);
    }
    public static YsmImportResult importPayload(YsmWirePayload wire, DecodePolicy policy,
                                               ResourceBudget budget, CancellationToken token) throws IOException {
        YsmHistoricalModel historical=YsmHistoricalCodec.decode(wire.bytes(),policy,budget,token);
        try {return importPayload(historical,policy,budget,token);}
        // Error is included so an OOM/StackOverflow during lowering cannot permanently poison the
        // caller's shared ResourceBudget with reservations nobody can reach any more.
        catch(IOException|RuntimeException|Error failure){historical.close();throw failure;}
    }
}
