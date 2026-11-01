package dev.spm.core.scene;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One owner-confined script state; query snapshots are immutable and children share state and budget. */
public final class MolangContext {
    private final Map<String, Double> queries;
    private final Map<String, Double> variables;
    private final Map<String, MolangProgram> functions;
    private final Map<String, Double> parameters;
    private final ScriptBudget budget;
    private final RandomState random;

    public MolangContext(Map<String, Double> queries, Map<String, Double> variables,
                         Map<String, MolangProgram> functions, ScriptBudget budget) {
        this(queries, variables, functions, budget, 0);
    }
    public MolangContext(Map<String, Double> queries, Map<String, Double> variables,
                         Map<String, MolangProgram> functions, ScriptBudget budget, long randomSeed) {
        this(boundedCopy(queries), boundedCopy(variables), functionCopy(functions), Map.of(), budget, new RandomState(randomSeed));
    }
    private MolangContext(Map<String, Double> queries, Map<String, Double> variables,
                          Map<String, MolangProgram> functions, Map<String, Double> parameters, ScriptBudget budget, RandomState random) {
        this.queries = Collections.unmodifiableMap(queries);
        this.variables = variables;
        this.functions = functions;
        this.parameters = parameters;
        this.budget = Objects.requireNonNull(budget);
        this.random = random;
    }
    public MolangContext child(Map<String, Double> childQueries) {
        return new MolangContext(boundedCopy(childQueries), variables, functions, parameters, budget, random);
    }
    MolangContext overlay(Map<String, Double> overrides) {
        Map<String, Double> combined = new HashMap<>(queries);
        combined.putAll(overrides);
        return child(combined);
    }
    MolangContext invocation(Map<String, Double> arguments) {
        return new MolangContext(queries, variables, functions, Map.copyOf(arguments), budget, random);
    }
    public ScriptBudget budget() { return budget; }
    public Map<String, Double> variables() { return Collections.unmodifiableMap(variables); }
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
    double query(String name) { return queries.getOrDefault(name, 0.0); }
    double parameter(String name) { return parameters.getOrDefault(name, 0.0); }
    double variable(String name) { return variables.getOrDefault(name, 0.0); }
    void variable(String name, double value) {
        if (!variables.containsKey(name) && variables.size() >= 4096) {
            throw new MolangException(MolangException.Reason.CAPACITY, "Persistent variable capacity exceeded");
        }
        variables.put(name, finite(value));
    }
    MolangProgram function(String name) {
        MolangProgram program = functions.get(name);
        if (program == null) throw new MolangException(MolangException.Reason.UNKNOWN_FUNCTION, "Unknown function: " + name);
        return program;
    }
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
