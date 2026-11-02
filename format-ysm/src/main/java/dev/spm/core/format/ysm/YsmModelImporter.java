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
    /**
     * Decodes and lowers in one pass, streaming player animations straight into the scene projection.
     *
     * <p>Prefer this over decoding to a tree and then lowering. The tree form retains every animation
     * keyframe before projecting it; this path projects each clip as it is parsed and drops the tree
     * representation immediately. The projected documents are identical either way — only the peak
     * differs.
     */
    public static YsmImportResult importPayload(YsmWirePayload wire, DecodePolicy policy,
                                               ResourceBudget budget, CancellationToken token) throws IOException {
        YsmHistoricalModel historical=YsmHistoricalCodec.decodeForImport(wire.bytes(),policy,budget,token);
        try {return importPayload(historical,policy,budget,token);}
        // Error is included so an OOM/StackOverflow during lowering cannot permanently poison the
        // caller's shared ResourceBudget with reservations nobody can reach any more.
        catch(IOException|RuntimeException|Error failure){historical.close();throw failure;}
    }
}
