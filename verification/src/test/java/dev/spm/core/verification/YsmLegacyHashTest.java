package dev.spm.core.verification;

import dev.spm.core.codec.legacy.LegacyHash;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen historical-writer outputs from the approved reference, not stock CityHash vectors. */
final class YsmLegacyHashTest {
    @Test void preservesHistoricalReductionAcrossAllLengthBranches() {
        int[] lengths = {0,1,3,4,7,8,16,17,32,33,63,64,65,127,128,1024,8191};
        String[] wanted = {"8042932239147081391","14662329999069294316","803572144813152280",
                "15713305407892776351","13844931084561017689","16340748013193004988","17737655933971482886",
                "10788145213889431210","17947090291158778479","1688872210173529929","9538431262500756175",
                "4565498937205348967","11260580286896762533","15616308421445669944","10089192292417589676",
                "10644972106623026718","7064877764580148889"};
        for (int i = 0; i < lengths.length; i++) {
            byte[] bytes = new byte[lengths[i]];
            for (int p = 0; p < bytes.length; p++) bytes[p] = (byte) (p * 131 + 17);
            assertEquals(wanted[i], Long.toUnsignedString(LegacyHash.seeded(bytes, 0, bytes.length, 0x9e5599db80c67c29L)), "length=" + bytes.length);
        }
    }
}
