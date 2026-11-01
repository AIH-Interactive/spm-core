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
                return new JsonNumber(new BigDecimal(number));
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
        private boolean transferred;
        public Quota(DecodePolicy policy,ResourceBudget budget,CancellationToken cancellation) { this.policy=policy; this.budget=budget; this.cancellation=cancellation; }
        public void charge(long bytes) throws IOException {
            cancellation.check();
            if (bytes<0 || bytes>policy.maxExpandedBytes()-expanded) throw new DecodeLimitException(ImportErrorCode.EXPANDED_LIMIT,"JSON expanded allocation limit exceeded");
            try { reservations.add(budget.reserve(bytes)); } catch (IllegalStateException exception) { throw new IOException("Bedrock working budget exceeded",exception); }
            expanded+=bytes;
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
         * Hands every live reservation to the caller, which becomes responsible for releasing them.
         * This is how a retained import stops resetting its own charge to zero on return.
         */
        public List<ResourceBudget.Reservation> transfer() {
            if (transferred) throw new IllegalStateException("Construction quota already transferred");
            transferred=true;
            var owned=List.copyOf(reservations);
            reservations.clear();
            return owned;
        }
        @Override public void close() {
            for (int i=reservations.size()-1;i>=0;i--) reservations.get(i).close();
            reservations.clear();
        }
    }

}
