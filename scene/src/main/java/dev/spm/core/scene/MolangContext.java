package dev.spm.core.scene;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One owner-confined script state; query snapshots are immutable and children share state and budget. */
public final class MolangContext {
    private static final Map<String, MolangValue> CTRL_CONSTANTS = Map.of(
            "ctrl.state_continue", MolangValue.number(2),
            "ctrl.state_stop", MolangValue.number(3),
            "ctrl.state_pause", MolangValue.number(4),
            "ctrl.state_bypass", MolangValue.number(5),
            "ctrl.loop", MolangValue.number(10),
            "ctrl.play_once", MolangValue.number(11),
            "ctrl.hold_on_last_frame", MolangValue.number(12));
    private final Map<String, Double> queries;
    private final Map<String, Double> queryOverrides;
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
            case "ysm.perlin_noise" -> count>=2 && count<=3;
            default -> true;
        };
        if(!valid) throw new MolangException(MolangException.Reason.ARITY,"Wrong arity: "+name);
    }
    MolangValue suppressedEffect(String name) {
        if(codedCommand(name))return codedControl==null?MolangValue.missing():null;
        if(name.equals("ysm.defer"))return null; // Deferred handlers are local simulation, not Host effects.
        if(name.equals("ysm.sync") && hookState.syncMode==SceneInputs.SyncMode.NONE)return MolangValue.missing();
        if(effectsAllowed || !EFFECTS.contains(name)) return null;
        return SOUND_RESULTS.contains(name)?MolangValue.bool(false):MolangValue.missing();
    }

    private final java.util.List<PendingCall> pending;
    private record PendingCall(long step,String name,String resource,Map<String,Double> parameters,java.util.List<MolangValue> arguments,String scope) { }
    private final Map<String, MolangProgram> functions;
    private final Map<String, MolangValue> parameters;
    private final ScriptBudget budget;
    private final RandomState random;
    private HookState hookState=new HookState(MolangHooks.empty(),SceneInputs.SyncMode.HOST);
    private int[] deferredCount={0};
    void retainedDeferred(int count) {
        if(count<0||count>4096-deferredCount[0])throw new MolangException(MolangException.Reason.CAPACITY,"Aggregate deferred capacity exceeded");
        deferredCount[0]+=count;
    }
    private static final class HookState {
        final MolangHooks handlers;
        Map<MolangHooks.Phase,java.util.List<MolangValue>> phaseArguments=Map.of();
        final SceneInputs.SyncMode syncMode;
        final java.util.List<java.util.List<MolangValue>> deferred;
        HookState(MolangHooks handlers,SceneInputs.SyncMode syncMode) {this(handlers,syncMode,new java.util.ArrayList<>());}
        HookState(MolangHooks handlers,SceneInputs.SyncMode syncMode,java.util.List<java.util.List<MolangValue>> deferred) {this.handlers=Objects.requireNonNull(handlers);this.syncMode=Objects.requireNonNull(syncMode);this.deferred=deferred;}
    }
    private CodedControl codedControl;
    private String controllerScope="";
    private int boneScope=-1;
    private Map<String,MolangValue> contextVariables;
    private MolangContext shareHooks(MolangContext child) {child.hookState=hookState;child.deferredCount=deferredCount;child.codedControl=codedControl;child.controllerScope=controllerScope;child.boneScope=boneScope;child.contextVariables=contextVariables;return child;}
    int boneScope() {return boneScope;}
    void boneScope(int value) {boneScope=value;}
    MolangContext memberScope(String name,Map<String,MolangValue> contextState,java.util.List<java.util.List<MolangValue>> deferred) {
        MolangContext child=shareHooks(new MolangContext(queries,queryOverrides,variables,functions,parameters,budget,random,bindings,physics,effectsAllowed,pending));
        child.controllerScope=name;child.contextVariables=contextState;child.hookState=new HookState(hookState.handlers,hookState.syncMode,deferred);child.hookState.phaseArguments=hookState.phaseArguments;return child;
    }
    MolangValue contextVariable(String name) {return contextVariables==null?parameter(name):lookup(contextVariables,name);}
    void contextVariable(String name,MolangValue value) {if(contextVariables!=null)assign(contextVariables,name,value,true);}
    static boolean codedCommand(String name) { return name.equals("ctrl.set_animation")||name.equals("ctrl.set_beginning_transition_length")||name.equals("ctrl.reset")||name.equals("ctrl.indicate_reload"); }
    boolean hasCodedControl() {return codedControl!=null;}
    void codedControl(CodedControl value) {codedControl=value;}

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
        this(Collections.unmodifiableMap(queries), null, variables, functions, parameters, budget, random, bindings, physics, effectsAllowed, pending);
    }
    private MolangContext(Map<String, Double> queries, Map<String, Double> queryOverrides, Map<String, MolangValue> variables,
                          Map<String, MolangProgram> functions, Map<String, MolangValue> parameters, ScriptBudget budget, RandomState random, MolangBindings bindings, MolangPhysics physics,boolean effectsAllowed,java.util.List<PendingCall> pending) {
        this.queries = queries;
        this.queryOverrides = queryOverrides;
        this.variables = variables;
        this.functions = functions;
        this.parameters = parameters;
        this.budget = Objects.requireNonNull(budget);
        this.random = random;
        this.bindings=bindings; this.physics=physics; this.effectsAllowed=effectsAllowed; this.pending=pending;
    }
    public MolangContext child(Map<String, Double> childQueries) {
        return shareHooks(new MolangContext(boundedCopy(childQueries), variables, functions, parameters, budget, random, bindings, physics,effectsAllowed,pending));
    }
    MolangContext overlay(Map<String, Double> overrides) {
        Map<String, Double> normalized = numericCopy(overrides);
        Double lifeTime = normalized.get("life_time");
        if (lifeTime != null) physics.advance(lifeTime, budget);
        Map<String, Double> nextOverrides;
        if (queryOverrides == null) {
            nextOverrides = normalized;
        } else {
            nextOverrides = new HashMap<>(queryOverrides.size() + normalized.size() + 2);
            nextOverrides.putAll(queryOverrides);
            nextOverrides.putAll(normalized);
        }
        return shareHooks(new MolangContext(queries, nextOverrides, variables, functions, parameters, budget, random, bindings, physics, effectsAllowed, pending));
    }
    /** Fast overlay for internal callers passing already-lowercase finite double entries. */
    MolangContext overlayKnown(Map<String, Double> lowercaseOverrides) {
        Double lifeTime = lowercaseOverrides.get("life_time");
        if (lifeTime != null) physics.advance(finite(lifeTime), budget);
        Map<String, Double> nextOverrides;
        if (queryOverrides == null) {
            nextOverrides = lowercaseOverrides;
        } else {
            nextOverrides = new HashMap<>(queryOverrides.size() + lowercaseOverrides.size() + 2);
            nextOverrides.putAll(queryOverrides);
            nextOverrides.putAll(lowercaseOverrides);
        }
        return shareHooks(new MolangContext(queries, nextOverrides, variables, functions, parameters, budget, random, bindings, physics, effectsAllowed, pending));
    }
    MolangContext sampling(Map<String,Double> overrides) {
        // Random-access library sampling has no persistent simulation clock.
        var context=new MolangContext(queries,queryOverrides,variables,functions,parameters,budget,random,bindings,new MolangPhysics(),false,new java.util.ArrayList<>());
        return context.overlay(overrides);
    }
    MolangContext invocation(Map<String, MolangValue> arguments) {
        return shareHooks(new MolangContext(queries, queryOverrides, variables, functions,
                Collections.unmodifiableMap(new HashMap<>(arguments)), budget, random, bindings, physics,effectsAllowed,pending));
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
        hookState=new HookState(MolangHooks.empty(),inputs.syncMode());hookState.phaseArguments=inputs.hookArguments();
    }
    MolangContext(SceneInputs inputs,MolangState prior,Map<String,MolangProgram> functions,ScriptBudget budget,long seed) {
        this(inputs.queries(),null,new HashMap<>(prior.variables),functions,Map.of(),budget,new RandomState(seed),inputs.bindings(),prior.physics.copy(),inputs.effectsAllowed(),new java.util.ArrayList<>());
        hookState=new HookState(MolangHooks.empty(),inputs.syncMode());hookState.phaseArguments=inputs.hookArguments();
    }
    MolangContext(SceneInputs inputs,MolangState prior,Map<String,MolangProgram> functions,ScriptBudget budget,long seed,MolangHooks hooks) {
        this(inputs,prior,functions,budget,seed);hookState=new HookState(hooks,inputs.syncMode());hookState.phaseArguments=inputs.hookArguments();
    }
    void initializeHooks() {invokeHooks(hookState.handlers.initialize(),hookState.phaseArguments.getOrDefault(MolangHooks.Phase.INITIALIZE,java.util.List.of()));}
    void beforeStepHooks() {invokeHooks(hookState.handlers.beforeStep(),hookState.phaseArguments.getOrDefault(MolangHooks.Phase.BEFORE_STEP,java.util.List.of()));}
    void afterStepHooks() {invokeHooks(hookState.handlers.afterStep(),hookState.phaseArguments.getOrDefault(MolangHooks.Phase.AFTER_STEP,java.util.List.of()));}
    void afterFrameHooks() {invokeHooks(hookState.handlers.afterFrame(),hookState.phaseArguments.getOrDefault(MolangHooks.Phase.AFTER_FRAME,java.util.List.of()));}
    private void invokeHooks(java.util.List<MolangProgram> programs,java.util.List<MolangValue> arguments) {
        if (programs.isEmpty()) return;
        Map<String,MolangValue> parameters=new HashMap<>();
        for(int index=0;index<arguments.size();index++)parameters.put("arg"+index,arguments.get(index));
        MolangContext invocation=invocation(parameters);
        for(MolangProgram program:programs) {
            budget.enterFunction();
            try{program.evaluateValue(invocation);}finally{budget.leaveFunction();}
        }
    }
    void flushDeferred() {
        var initial=java.util.List.copyOf(hookState.deferred);hookState.deferred.clear();deferredCount[0]-=initial.size();
        try {
            for(int index=initial.size()-1;index>=0;index--) {
                budget.step();invokeHooks(hookState.handlers.defer(),initial.get(index));
            }
        } finally {deferredCount[0]-=hookState.deferred.size();hookState.deferred.clear();}
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
        long bound = high - low + 1L;
        if (bound <= 1L) return low;
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
        long times = integer ? Math.round(Math.max(0, count)) : (long) Math.max(0, count);
        double result = 0;
        for (long i = 0; i < times; i++) {
            budget.step();
            result = finite(result + (integer ? randomInteger(low, high) : random(low, high)));
        }
        return result;
    }
    private Double lookupQuery(String name) {
        if (queryOverrides != null) {
            Double override = queryOverrides.get(name);
            if (override != null) return override;
        }
        return queries.get(name);
    }
    MolangValue query(String name) {
        Double direct = lookupQuery(name);
        if (direct != null) return MolangValue.number(direct);
        MolangValue typed=bindings.value("query."+name);
        return typed!=MolangValue.missing()?typed:MolangValue.missing();
    }
    MolangValue boundValue(String name) {
        MolangValue constant = CTRL_CONSTANTS.get(name);
        if (constant != null) return constant;
        MolangValue typed=bindings.value(name);
        if (typed!=MolangValue.missing()) return typed;
        Double direct = lookupQuery(name);
        return direct != null ? MolangValue.number(direct) : MolangValue.missing();
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
        if(codedCommand(name)) {
            if(codedControl==null)return MolangValue.missing();
            checkArity(name,arguments.size());
            switch(name) {
                case "ctrl.set_animation" -> {
                    String animation=arguments.get(0) instanceof MolangValue.TextValue text?text.value():"";
                    if(!animation.isEmpty()) {
                        int mode=arguments.size()>1?(int)arguments.get(1).number():0;
                        codedControl.select(animation,switch(mode){case 10->CodedLoopMode.LOOP;case 11->CodedLoopMode.PLAY_ONCE;case 12->CodedLoopMode.HOLD_ON_LAST_FRAME;default->CodedLoopMode.CLIP_DEFAULT;});
                    }
                }
                case "ctrl.set_beginning_transition_length" -> {double ticks=arguments.get(0).number();if(ticks>=0)codedControl.transition(ticks);}
                case "ctrl.reset" -> codedControl.resetPlayback();
                case "ctrl.indicate_reload" -> codedControl.reload();
                default -> throw new IllegalStateException();
            }
            return MolangValue.missing();
        }

        if(name.equals("ysm.defer")) {
            if(arguments.isEmpty() || !(arguments.get(0) instanceof MolangValue.TextValue text) || text.value().isEmpty())return MolangValue.missing();
            budget.step();
            if(hookState.deferred.size()>=4096)throw new MolangException(MolangException.Reason.CAPACITY,"Deferred call capacity exceeded");
            retainedDeferred(1);
            hookState.deferred.add(java.util.List.copyOf(arguments.subList(1,arguments.size())));
            return MolangValue.missing();
        }
        if(name.equals("ysm.sync")) {
            checkArity(name,arguments.size());
            var packed=new java.util.ArrayList<MolangValue>(arguments.size());
            for(MolangValue argument:arguments) {
                budget.step();
                float value=(float)argument.number();
                if(!Float.isFinite(value))throw new MolangException(MolangException.Reason.NON_FINITE,"Sync argument exceeds finite float32");
                packed.add(MolangValue.number(value));
            }
            arguments=java.util.List.copyOf(packed);
        }
        if(name.equals("ysm.sync") && hookState.syncMode!=SceneInputs.SyncMode.HOST) {
            checkArity(name,arguments.size());
            if(effectsAllowed && hookState.syncMode==SceneInputs.SyncMode.LOCAL)invokeHooks(hookState.handlers.sync(),arguments);
            return MolangValue.missing();
        }
        if(name.equals("query.position_delta") || name.equals("query.position")) {
            if(arguments.size()!=1) throw new MolangException(MolangException.Reason.ARITY,"Position query requires one axis");
            int axis=(int)arguments.get(0).number();
            if(axis<0 || axis>2) return MolangValue.missing();
            MolangValue vector=bindings.value(name);
            if(vector!=MolangValue.missing()) return vector.field(axis==0?"x":axis==1?"y":"z");
            return bindings.call(name,java.util.List.of(MolangValue.number(axis)),-1,controllerScope);
        }
        if(EFFECTS.contains(name)) {
            checkArity(name,arguments.size());
            if(name.equals("ysm.defer") && (arguments.isEmpty() || !(arguments.get(0) instanceof MolangValue.TextValue text) || text.value().isEmpty()))
                return MolangValue.missing();
            MolangValue result=MolangValue.missing();
            if(effectsAllowed && pending.size()>=4096) throw new MolangException(MolangException.Reason.CAPACITY,"Effect queue capacity exceeded");
            if(effectsAllowed && (SOUND_RESULTS.contains(name) || name.equals("ysm.particle") || name.equals("ysm.abs_particle")))
                result=bindings.call(name,arguments,pending.stream().filter(call->call.scope().equals(controllerScope)).count(),controllerScope);
            if(effectsAllowed) {
                budget.step();
                if(pending.size()>=4096) throw new MolangException(MolangException.Reason.CAPACITY,"Effect queue capacity exceeded");
                Double lifeVal = lookupQuery("life_time");
                double time = lifeVal != null ? lifeVal : 0.0;
                pending.add(new PendingCall(Math.max(0,Math.round(time*60)),name,name,Map.of(),java.util.List.copyOf(arguments),controllerScope));
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
            return MolangValue.number(physics.second(boneScope,key,input,a,b,c,budget));
        }
        if(name.equals("ysm.perlin_noise")) {
            double x=arguments.get(0).number();
            double y=arguments.get(1).number();
            long seed=arguments.size()>2?(long)arguments.get(2).number():0L;
            return MolangValue.number(perlin2d(x,y,seed));
        }
        return bindings.call(name,arguments,-1,controllerScope);
    }
    private static double perlin2d(double x,double y,long seed) {
        int xi=(int)Math.floor(x), yi=(int)Math.floor(y);
        double xf=x-xi, yf=y-yi;
        double u=fade(xf), v=fade(yf);
        double n00=grad(hash(xi,yi,seed),xf,yf);
        double n10=grad(hash(xi+1,yi,seed),xf-1,yf);
        double n01=grad(hash(xi,yi+1,seed),xf,yf-1);
        double n11=grad(hash(xi+1,yi+1,seed),xf-1,yf-1);
        double x1=n00+u*(n10-n00);
        double x2=n01+u*(n11-n01);
        return finite(x1+v*(x2-x1));
    }
    private static double fade(double t) { return t*t*t*(t*(t*6-15)+10); }
    private static int hash(int x,int y,long seed) {
        long h=seed ^ (x*0x9E3779B97F4A7C15L) ^ (y*0xBF58476D1CE4E5B9L);
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;
        h=(h^(h>>>27))*0x94D049BB133111EBL;
        return (int)(h^(h>>>31));
    }
    private static double grad(int h,double x,double y) {
        return switch(h&7) {
            case 0 -> x+y; case 1 -> -x+y; case 2 -> x-y; case 3 -> -x-y;
            case 4 -> x; case 5 -> -x; case 6 -> y; default -> -y;
        };
    }
    void resetAnimationContext() {flushDeferred();if(contextVariables!=null)contextVariables.clear();}
    void timelineEffects(java.util.List<EffectTimeline.Pending> effects) {
        for(var effect:effects){budget.step();if(pending.size()>=4096)throw new MolangException(MolangException.Reason.CAPACITY,"Effect capacity exceeded");var cue=effect.cue();pending.add(new PendingCall(effect.step(),cue.kind(),cue.resource(),cue.parameters(),effect.arguments(),controllerScope));}
    }
    void appendEffects(java.util.List<EffectCommand> target,long owner,long generation,long sequence) {
        for(PendingCall call:pending) {
            budget.step();
            target.add(new EffectCommand(owner,generation,Math.addExact(sequence,target.size()),call.step(),call.name(),call.resource(),call.parameters(),call.arguments(),call.scope()));
        }
        target.sort(java.util.Comparator.comparingLong(EffectCommand::step));
        for(int index=0;index<target.size();index++) {
            budget.step(); EffectCommand effect=target.get(index);
            target.set(index,new EffectCommand(owner,generation,Math.addExact(sequence,index),effect.step(),effect.kind(),effect.resource(),effect.parameters(),effect.arguments(),effect.controllerScope()));
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
    static Map<String, MolangProgram> functionCopy(Map<String, MolangProgram> source) {
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
