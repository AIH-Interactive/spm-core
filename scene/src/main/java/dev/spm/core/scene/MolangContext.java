package dev.spm.core.scene;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One owner-confined script state; query snapshots are immutable and children share state and budget. */
public final class MolangContext {
    private final Map<String, Double> queries;
    private final Map<String, MolangValue> variables;
    private final MolangBindings bindings;
    private final MolangPhysics physics;
    private final boolean effectsAllowed;
    private static final java.util.Set<String> EFFECTS=java.util.Set.of("ysm.sync","ysm.defer","ysm.play_sound","ysm.stop_sound","ysm.stop_all_sounds","ysm.particle","ysm.abs_particle","ysm.bone_color","ysm.bone_transparency","ysm.bone_glow","ctrl.set_animation","ctrl.set_beginning_transition_length","ctrl.reset","ctrl.indicate_reload");
    private static final java.util.Set<String> SOUND_RESULTS=java.util.Set.of("ysm.play_sound","ysm.stop_sound","ysm.stop_all_sounds");
    static void checkArity(String name,int count) {
        boolean valid=switch(name) {
            case "ysm.play_sound" -> count>=2 && count<=5;
            case "ysm.stop_sound", "ctrl.set_animation" -> count==1 || count==2;
            case "ysm.stop_all_sounds" -> count<=1;
            case "ysm.sync" -> count<=16;
            case "ysm.bone_color" -> count==4;
            case "ysm.bone_transparency", "ysm.bone_glow" -> count==2;
            case "ctrl.reset", "ctrl.indicate_reload" -> count==0;
            case "ctrl.set_beginning_transition_length", "query.position", "query.position_delta" -> count==1;
            case "ysm.particle", "ysm.abs_particle" -> count>=1;
            case "ysm.first_order", "ysm.second_order" -> count>=2;
            default -> true;
        };
        if(!valid) throw new MolangException(MolangException.Reason.ARITY,"Wrong arity: "+name);
    }
    MolangValue suppressedEffect(String name) {
        if(effectsAllowed || !EFFECTS.contains(name)) return null;
        return SOUND_RESULTS.contains(name)?MolangValue.bool(false):MolangValue.missing();
    }

    private final java.util.List<PendingCall> pending;
    private record PendingCall(long step,String name,java.util.List<MolangValue> arguments) { }
    private final Map<String, MolangProgram> functions;
    private final Map<String, MolangValue> parameters;
    private final ScriptBudget budget;
    private final RandomState random;

    public MolangContext(Map<String, Double> queries, Map<String, Double> variables,
                         Map<String, MolangProgram> functions, ScriptBudget budget) {
        this(queries, variables, functions, budget, 0);
    }
    public MolangContext(Map<String, Double> queries, Map<String, Double> variables,
                         Map<String, MolangProgram> functions, ScriptBudget budget, long randomSeed) {
        this(boundedCopy(queries), typedNumeric(variables), functionCopy(functions), Map.of(), budget, new RandomState(randomSeed), MolangBindings.empty(), new MolangPhysics(),true,new java.util.ArrayList<>());
    }
    private MolangContext(Map<String, Double> queries, Map<String, MolangValue> variables,
                          Map<String, MolangProgram> functions, Map<String, MolangValue> parameters, ScriptBudget budget, RandomState random, MolangBindings bindings, MolangPhysics physics,boolean effectsAllowed,java.util.List<PendingCall> pending) {
        this.queries = Collections.unmodifiableMap(queries);
        this.variables = variables;
        this.functions = functions;
        this.parameters = parameters;
        this.budget = Objects.requireNonNull(budget);
        this.random = random;
        this.bindings=bindings; this.physics=physics; this.effectsAllowed=effectsAllowed; this.pending=pending;
    }
    public MolangContext child(Map<String, Double> childQueries) {
        return new MolangContext(boundedCopy(childQueries), variables, functions, parameters, budget, random, bindings, physics,effectsAllowed,pending);
    }
    MolangContext overlay(Map<String, Double> overrides) {
        Map<String, Double> combined = new HashMap<>(queries);
        combined.putAll(overrides);
        if(overrides.containsKey("life_time")) physics.advance(overrides.get("life_time"),budget);
        return child(combined);
    }
    MolangContext sampling(Map<String,Double> overrides) {
        // Random-access library sampling has no persistent simulation clock.
        var context=new MolangContext(queries,variables,functions,parameters,budget,random,bindings,new MolangPhysics(),false,new java.util.ArrayList<>());
        return context.overlay(overrides);
    }
    MolangContext invocation(Map<String, MolangValue> arguments) {
        return new MolangContext(queries, variables, functions,
                Collections.unmodifiableMap(new HashMap<>(arguments)), budget, random, bindings, physics,effectsAllowed,pending);
    }
    public ScriptBudget budget() { return budget; }
    public Map<String, Double> variables() {
        Map<String,Double> numeric=new HashMap<>();
        variables.forEach((name,value)->numeric.put(name,value.number()));
        return Map.copyOf(numeric);
    }
    public Map<String,MolangValue> typedVariables() { return Map.copyOf(variables); }
    public MolangContext(SceneInputs inputs,Map<String,MolangValue> variables,Map<String,MolangProgram> functions,ScriptBudget budget) {
        this(inputs.queries(),typedCopy(variables),functionCopy(functions),Map.of(),budget,new RandomState(0),inputs.bindings(),new MolangPhysics(),inputs.effectsAllowed(),new java.util.ArrayList<>());
    }
    MolangContext(SceneInputs inputs,MolangState prior,Map<String,MolangProgram> functions,ScriptBudget budget,long seed) {
        this(inputs.queries(),new HashMap<>(prior.variables),functionCopy(functions),Map.of(),budget,new RandomState(seed),inputs.bindings(),prior.physics.copy(),inputs.effectsAllowed(),new java.util.ArrayList<>());
    }
    MolangState snapshot() { return new MolangState(variables,physics); }
    private static Map<String,MolangValue> typedNumeric(Map<String,Double> source) {
        Map<String,MolangValue> result=new HashMap<>();
        boundedCopy(source).forEach((key,value)->result.put(key,MolangValue.number(value)));
        return result;
    }
    private static Map<String,MolangValue> typedCopy(Map<String,MolangValue> source) {
        if(source.size()>4096) throw new IllegalArgumentException("At most 4096 variables");
        Map<String,MolangValue> result=new HashMap<>();
        source.forEach((key,value)-> {
            if(result.putIfAbsent(Objects.requireNonNull(key).toLowerCase(Locale.ROOT),Objects.requireNonNull(value))!=null)
                throw new IllegalArgumentException("Duplicate variable: "+key);
        });
        return result;
    }
    long randomState() { return random.state; }
    private static final class RandomState {
        long state;
        RandomState(long state) { this.state = state; }
        long next() {
            long value = (state += 0x9E3779B97F4A7C15L);
            value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
            value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
            return value ^ (value >>> 31);
        }
    }
    double random(double first, double second) {
        double low = Math.min(first, second);
        double high = Math.max(first, second);
        if (low == high) return low;
        budget.step();
        double unit = (random.next() >>> 11) * 0x1.0p-53;
        double result = finite(low * (1 - unit) + high * unit);
        return result >= high ? Math.nextDown(high) : Math.max(low, result);
    }
    double randomInteger(double first, double second) {
        if (first < Integer.MIN_VALUE || first > Integer.MAX_VALUE || second < Integer.MIN_VALUE || second > Integer.MAX_VALUE) {
            throw new MolangException(MolangException.Reason.CAPACITY, "Random integer endpoints must fit int32");
        }
        long low = Math.min((int) first, (int) second);
        long high = Math.max((int) first, (int) second);
        long bound = high - low;
        if (bound == 0) return low;
        long bits;
        long result;
        do {
            budget.step();
            bits = random.next() >>> 1;
            result = bits % bound;
        } while (bits - result + bound - 1 < 0);
        return low + result;
    }
    double roll(double count, double low, double high, boolean integer) {
        long times = (long) Math.max(0, count);
        double result = 0;
        for (long i = 0; i < times; i++) {
            budget.step();
            result = finite(result + (integer ? randomInteger(low, high) : random(low, high)));
        }
        return result;
    }
    MolangValue query(String name) {
        if(queries.containsKey(name)) return MolangValue.number(queries.get(name));
        MolangValue typed=bindings.value("query."+name);
        return typed!=MolangValue.missing()?typed:queries.containsKey(name)?MolangValue.number(queries.get(name)):MolangValue.missing();
    }
    MolangValue boundValue(String name) {
        Map<String,Integer> constants=Map.of("ctrl.state_continue",2,"ctrl.state_stop",3,"ctrl.state_pause",4,"ctrl.state_bypass",5,"ctrl.loop",10,"ctrl.play_once",11,"ctrl.hold_on_last_frame",12);
        if(constants.containsKey(name)) return MolangValue.number(constants.get(name));
        MolangValue typed=bindings.value(name);
        return typed!=MolangValue.missing()?typed:queries.containsKey(name)?MolangValue.number(queries.get(name)):MolangValue.missing();
    }
    MolangValue parameter(String name) { return lookup(parameters,name); }
    static MolangValue lookup(Map<String,MolangValue> values,String name) {
        MolangValue exact=values.get(name);
        if(exact!=null) return exact;
        for(int dot=name.lastIndexOf('.');dot>=0;dot=name.lastIndexOf('.',dot-1)) {
            MolangValue parent=values.get(name.substring(0,dot));
            if(parent!=null) return parent.field(name.substring(dot+1));
        }
        return MolangValue.missing();
    }
    MolangValue variable(String name) { return lookup(variables,name); }
    void variable(String name,MolangValue value) { assign(variables,name,value,true); }
    void temporary(Map<String,MolangValue> values,String name,MolangValue value) { assign(values,name,value,false); }
    private void assign(Map<String,MolangValue> values,String name,MolangValue value,boolean persistent) {
        for(int dot=name.lastIndexOf('.');dot>=0;dot=name.lastIndexOf('.',dot-1)) {
            if(values.get(name.substring(0,dot)) instanceof MolangValue.StructValue parent) {
                values.put(name.substring(0,dot),property(parent,name.substring(dot+1),value)); return;
            }
        }
        if(value==MolangValue.missing()) { values.remove(name); return; }
        if(!values.containsKey(name) && values.size()>=4096)
            throw new MolangException(MolangException.Reason.CAPACITY,persistent?"Persistent variable capacity exceeded":"Temporary capacity exceeded");
        if(value instanceof MolangValue.StructValue) {
            for(String key:new java.util.ArrayList<>(values.keySet())) {
                budget.step(); if(key.startsWith(name+".")) values.remove(key);
            }
        }
        values.put(name,value);
    }
    private MolangValue property(MolangValue parent,String path,MolangValue value) {
        Map<String,MolangValue> fields=new HashMap<>();
        if(parent instanceof MolangValue.StructValue structure) {
            for(var entry:structure.fields().entrySet()) { budget.step(); fields.put(entry.getKey(),entry.getValue()); }
        }
        int dot=path.indexOf('.'); String key=dot<0?path:path.substring(0,dot);
        MolangValue replacement=dot<0?value:property(fields.getOrDefault(key,MolangValue.missing()),path.substring(dot+1),value);
        if(replacement==MolangValue.missing()) fields.remove(key); else fields.put(key,replacement);
        return MolangValue.struct(fields);
    }
    MolangValue call(String name,java.util.List<MolangValue> arguments) {
        if(name.equals("query.position_delta") || name.equals("query.position")) {
            if(arguments.size()!=1) throw new MolangException(MolangException.Reason.ARITY,"Position query requires one axis");
            int axis=(int)arguments.get(0).number();
            if(axis<0 || axis>2) return MolangValue.missing();
            MolangValue vector=bindings.value(name);
            if(vector!=MolangValue.missing()) return vector.field(axis==0?"x":axis==1?"y":"z");
            return bindings.call(name,java.util.List.of(MolangValue.number(axis)));
        }
        if(EFFECTS.contains(name)) {
            checkArity(name,arguments.size());
            if(name.equals("ysm.defer") && (arguments.isEmpty() || !(arguments.get(0) instanceof MolangValue.TextValue text) || text.value().isEmpty()))
                return MolangValue.missing();
            MolangValue result=MolangValue.missing();
            if(effectsAllowed && (SOUND_RESULTS.contains(name) || name.equals("ysm.particle") || name.equals("ysm.abs_particle")))
                result=bindings.call(name,arguments);
            if(effectsAllowed) {
                budget.step();
                if(pending.size()>=4096) throw new MolangException(MolangException.Reason.CAPACITY,"Effect queue capacity exceeded");
                double time=queries.getOrDefault("life_time",0.0);
                pending.add(new PendingCall(Math.max(0,Math.round(time*60)),name,java.util.List.copyOf(arguments)));
            }
            return result;
        }
        if(name.equals("ysm.first_order") || name.equals("ysm.second_order")) {
            int minimum=2;
            if(arguments.size()<minimum) throw new MolangException(MolangException.Reason.ARITY,"Expected key and input: "+name);
            String key=arguments.get(0) instanceof MolangValue.TextValue text?text.value():"";
            double input=arguments.get(1).number();
            double a=arguments.size()>2?arguments.get(2).number():1;
            if(name.equals("ysm.first_order")) return MolangValue.number(physics.first(key,input,a,budget));
            double b=arguments.size()>3?arguments.get(3).number():1;
            double c=arguments.size()>4?arguments.get(4).number():1;
            return MolangValue.number(physics.second(key,input,a,b,c,budget));
        }
        return bindings.call(name,arguments);
    }
    void appendEffects(java.util.List<EffectCommand> target,long owner,long generation,long sequence) {
        for(PendingCall call:pending) {
            budget.step();
            target.add(new EffectCommand(owner,generation,Math.addExact(sequence,target.size()),call.step(),call.name(),call.name(),Map.of(),call.arguments()));
        }
        target.sort(java.util.Comparator.comparingLong(EffectCommand::step));
        for(int index=0;index<target.size();index++) {
            budget.step(); EffectCommand effect=target.get(index);
            target.set(index,new EffectCommand(owner,generation,Math.addExact(sequence,index),effect.step(),effect.kind(),effect.resource(),effect.parameters(),effect.arguments()));
        }
    }
    MolangProgram function(String name) {
        MolangProgram program = functions.get(name);
        if (program == null) throw new MolangException(MolangException.Reason.UNKNOWN_FUNCTION, "Unknown function: " + name);
        return program;
    }
    MolangProgram findFunction(String name) { return functions.get(name); }
    static Map<String, Double> numericCopy(Map<String, Double> source) {
        Objects.requireNonNull(source);
        Map<String, Double> result = new HashMap<>();
        source.forEach((key, value) -> {
            String normalized = Objects.requireNonNull(key).toLowerCase(Locale.ROOT);
            if (result.putIfAbsent(normalized, finite(Objects.requireNonNull(value))) != null) {
                throw new IllegalArgumentException("Duplicate case-insensitive numeric key: " + key);
            }
        });
        return result;
    }
    private static Map<String, Double> boundedCopy(Map<String, Double> source) {
        if (source.size() > 4096) throw new IllegalArgumentException("At most 4096 numeric slots");
        return numericCopy(source);
    }
    private static Map<String, MolangProgram> functionCopy(Map<String, MolangProgram> source) {
        if (source.size() > 4096) throw new IllegalArgumentException("At most 4096 functions");
        Map<String, MolangProgram> normalized = new HashMap<>();
        source.forEach((name, program) -> {
            if (normalized.putIfAbsent(name.toLowerCase(Locale.ROOT), Objects.requireNonNull(program)) != null) {
                throw new IllegalArgumentException("Duplicate case-insensitive function name");
            }
        });
        return Map.copyOf(normalized);
    }
    static double finite(double value) {
        if (!Double.isFinite(value)) throw new MolangException(MolangException.Reason.NON_FINITE, "Non-finite script value");
        return value;
    }
}
