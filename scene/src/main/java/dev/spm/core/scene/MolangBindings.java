package dev.spm.core.scene;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable Host-sampled values and call results. Contains data, never executable callbacks. */
public record MolangBindings(Map<String,MolangValue> values, Map<Call,MolangValue> calls) {
    public record Call(String name,List<MolangValue> arguments) {
        public Call {
            name=normalize(name);
            arguments=List.copyOf(arguments);
            if(arguments.size()>64) throw new IllegalArgumentException("At most 64 binding arguments");
        }
    }
    public MolangBindings {
        if(values.size()+calls.size()>4096) throw new IllegalArgumentException("At most 4096 Host bindings");
        Map<String,MolangValue> normalized=new HashMap<>();
        values.forEach((key,value)-> {
            if(normalized.putIfAbsent(normalize(key),Objects.requireNonNull(value))!=null)
                throw new IllegalArgumentException("Duplicate Host binding: "+key);
        });
        values=Map.copyOf(normalized); calls=Map.copyOf(calls);
    }
    public static MolangBindings empty() { return new MolangBindings(Map.of(),Map.of()); }
    public MolangValue value(String name) { return MolangContext.lookup(values,normalize(name)); }
    public MolangValue call(String name,List<MolangValue> arguments) {
        MolangValue result=calls.get(new Call(name,arguments));
        if(result==null) throw new MolangException(MolangException.Reason.UNKNOWN_FUNCTION,"Host snapshot has no call: "+name);
        return result;
    }
    private static String normalize(String name) {
        String result=Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
        if(result.length()>256 || !result.matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+"))
            throw new IllegalArgumentException("Invalid Host binding name");
        return result.startsWith("q.")?"query."+result.substring(2):result;
    }
}
