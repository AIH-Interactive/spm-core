package dev.spm.core.format.common;

import dev.spm.core.api.*;
import dev.spm.core.api.ModelDocument.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Shared bounded JSON reader; all public results use the Core immutable JSON types. */
public final class BoundedJson {
    private BoundedJson() {}
    /** Reads into an existing construction quota, which the caller keeps live until its IR is complete. */
    public static JsonObject read(byte[] bytes, Quota quota) throws IOException {
        Objects.requireNonNull(bytes); Objects.requireNonNull(quota);
        quota.cancellation.check();
        if (bytes.length > quota.policy.maxInputBytes()) throw new IOException("JSON input limit exceeded");
        quota.charge(Math.multiplyExact((long) bytes.length, 5));
        byte[] snapshot = bytes.clone();
        preflight(snapshot, quota.policy, quota.cancellation);
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot)).toString();
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonValue root = value(reader, quota, 0);
            if (!(root instanceof JsonObject object)) throw new IOException("Expected JSON object document");
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON input");
            quota.cancellation.check();
            return object;
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new IOException("Invalid JSON document", exception);
        }
    }

    private static void preflight(byte[] bytes,DecodePolicy policy,CancellationToken cancellation) throws IOException {
        boolean string=false;
        int depth=0, rawString=0;
        for (int i=0;i<bytes.length;i++) {
            if ((i&1023)==0) cancellation.check();
            int c=bytes[i]&255;
            if (string) {
                if (c=='"') { string=false; continue; }
                if (++rawString>policy.maxStringBytes()) throw new IOException("JSON string limit exceeded");
                if (c<32) throw new IOException("Unescaped string control character");
                if (c=='\\') {
                    if (++i>=bytes.length) throw new IOException("Truncated JSON escape");
                    int escaped=bytes[i]&255;
                    if (++rawString>policy.maxStringBytes()) throw new IOException("JSON string limit exceeded");
                    if (escaped=='u') {
                        for (int k=0;k<4;k++) {
                            if (++i>=bytes.length || Character.digit((char)(bytes[i]&255),16)<0)
                                throw new IOException("Invalid Unicode escape");
                            if (++rawString>policy.maxStringBytes()) throw new IOException("JSON string limit exceeded");
                        }
                    } else if ("\"\\/bfnrt".indexOf(escaped)<0) throw new IOException("Invalid JSON escape");
                }
            } else if (c=='"') { string=true; rawString=0; }
            else if (c=='[' || c=='{') {
                if (++depth>Math.min(policy.maxDepth(),256)) throw new IOException("JSON depth limit exceeded");
            } else if (c==']' || c=='}') { if (--depth<0) throw new IOException("Unbalanced JSON"); }
            else if (c>='A' && c<='Z') throw new IOException("JSON literals must be lowercase");
        }
        if (string || depth!=0) throw new IOException("Truncated JSON document");
    }

    private static JsonValue value(JsonReader reader,Quota quota,int depth) throws IOException {
        quota.node();
        if (depth>Math.min(quota.policy.maxDepth(),256)) throw new IOException("JSON depth limit exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                var values=new LinkedHashMap<String,JsonValue>(); reader.beginObject();
                while (reader.hasNext()) {
                    if(values.size()>=quota.policy.maxCollectionEntries())throw new DecodeLimitException(ImportErrorCode.COLLECTION_LIMIT,"JSON object entry limit exceeded");
                    String name=reader.nextName(); quota.string(name);
                    if (values.containsKey(name)) throw new IOException("Duplicate JSON object member: "+name);
                    values.put(name,value(reader,quota,depth+1));
                }
                reader.endObject(); return new JsonObject(values);
            }
            case BEGIN_ARRAY: {
                var values=new ArrayList<JsonValue>(); reader.beginArray();
                while (reader.hasNext()) {
                    if(values.size()>=quota.policy.maxCollectionEntries())throw new DecodeLimitException(ImportErrorCode.COLLECTION_LIMIT,"JSON array entry limit exceeded");
                    values.add(value(reader,quota,depth+1));
                }
                reader.endArray(); return new JsonArray(values);
            }
            case STRING: { String result=reader.nextString(); quota.string(result); return new JsonString(result); }
            case NUMBER: {
                String number=reader.nextString(); quota.string(number);
                // JsonNumber retains a primitive double, so a literal that would not survive the
                // round trip must be rejected rather than silently rounded: archival metadata is
                // supposed to be exact, and an author's 0.1000000000000000001 must not become 0.1
                // without anyone noticing.
                BigDecimal exact;
                try { exact=new BigDecimal(number); }
                catch(NumberFormatException malformed) { throw new IOException("Invalid JSON number: "+number,malformed); }
                double value=exact.doubleValue();
                if(!Double.isFinite(value)||BigDecimal.valueOf(value).compareTo(exact)!=0)
                    throw new IOException("JSON number is not exactly representable: "+number);
                return new JsonNumber(value);
            }
            case BOOLEAN: return new JsonBoolean(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Expected JSON value");
        }
    }

    public static final class Quota implements AutoCloseable {
        public final DecodePolicy policy; private final ResourceBudget budget; public final CancellationToken cancellation;
        final List<ResourceBudget.Reservation> reservations=new ArrayList<>();
        long expanded; int nodes;
        /**
         * Admits one charge per parsed node while holding no ticket per node.
         *
         * <p>Charging each node with an individual {@link ResourceBudget.Reservation} made the accounting
         * objects dominate the import: a corpus model held 704,146 live reservations — 22.5 MB of tickets
         * plus 11.3 MB of their {@code AtomicBoolean}s — for content of only tens of megabytes. The ledger
         * keeps {@code used()} exact after every charge, so admission and rejection are unchanged, but it
         * allocates nothing per charge. Ownership transfer converts the whole amount into a single ticket.
         */
        private final ResourceBudget.Ledger ledger;
        private boolean transferred;
        public Quota(DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) {
            this.policy=policy; this.budget=budget; this.cancellation=cancellation;
            this.ledger=budget.ledger();
        }
        public void charge(long bytes) throws IOException {
            cancellation.check();
            if (bytes<0 || bytes>policy.maxExpandedBytes()-expanded) throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"JSON expanded allocation limit exceeded");
            expanded+=bytes;
            try { ledger.admit(bytes); }
            catch (ResourceBudgetExceededException exception) { throw new IOException("JSON working budget exceeded",exception); }
        }
        void node() throws IOException {
            if (nodes>=policy.maxObjects()) throw new DecodeLimitException(ImportErrorCode.OBJECT_LIMIT,"JSON node limit exceeded");
            nodes++; charge(128);
        }
        void string(String text) throws IOException {
            long utf8=0;
            for (int i=0;i<text.length();i++) {
                char c=text.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) throw new IOException("Unpaired Unicode surrogate");
                    utf8+=4;
                } else if (Character.isLowSurrogate(c)) throw new IOException("Unpaired Unicode surrogate");
                else utf8+=c<128?1:c<2048?2:3;
            }
            if (utf8>policy.maxStringBytes()) throw new IOException("JSON string limit exceeded");
            charge(Math.addExact(64,Math.multiplyExact((long)text.length(),4)));
        }
        /**
         * Hands the quota's whole charge to the caller as one ticket.
         *
         * <p>This is how a retained import stops resetting its own charge to zero on return. Because the
         * charge was accumulated on a ledger rather than as one reservation per node, the transfer is a
         * single ticket instead of hundreds of thousands.
         */
        public List<ResourceBudget.Reservation> transfer() throws IOException {
            if (transferred) throw new IllegalStateException("Construction quota already transferred");
            transferred=true;
            var owned=new ArrayList<>(reservations);
            // The ledger's outstanding amount becomes one reservation owned by the caller.
            var frozen=ledger.freeze();
            if (frozen.bytes()>0) owned.add(frozen);
            else frozen.close();
            reservations.clear();
            return List.copyOf(owned);
        }
        @Override public void close() {
            for (int i=reservations.size()-1;i>=0;i--) reservations.get(i).close();
            reservations.clear();
            ledger.close();
        }
    }

}
