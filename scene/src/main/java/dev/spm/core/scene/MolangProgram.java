package dev.spm.core.scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable, bounded numeric Molang subset. No reflection, Java callbacks or host objects. */
public final class MolangProgram {
    private static final int MAX_SOURCE = 65_536;
    private static final int MAX_TOKENS = 8_192;
    private static final int MAX_TREE_DEPTH = 128;
    private static final Map<String, Integer> MATH = Map.ofEntries(
            Map.entry("abs", 1), Map.entry("sin", 1), Map.entry("cos", 1), Map.entry("tan", 1),
            Map.entry("asin", 1), Map.entry("acos", 1), Map.entry("atan", 1), Map.entry("atan2", 2),
            Map.entry("ceil", 1), Map.entry("floor", 1), Map.entry("round", 1), Map.entry("trunc", 1),
            Map.entry("sqrt", 1), Map.entry("pow", 2), Map.entry("exp", 1), Map.entry("ln", 1),
            Map.entry("min", 2), Map.entry("max", 2), Map.entry("clamp", 3), Map.entry("lerp", 3),
            Map.entry("mod", 2), Map.entry("sign", 1), Map.entry("hermite_blend", 1),
            Map.entry("hermite", 1), Map.entry("min_angle", 1), Map.entry("lerprotate", 3),
            Map.entry("random", 2), Map.entry("random_integer", 2), Map.entry("randomi", 2),
            Map.entry("die_roll", 3), Map.entry("die_roll_integer", 3), Map.entry("roll", 3), Map.entry("rolli", 3));
    private final Node root;

    private MolangProgram(Node root) { this.root = root; }
    public static MolangProgram compile(String source) {
        return compile(source,MAX_SOURCE,MAX_TOKENS);
    }
    static MolangProgram compile(String source,int sourceLimit,int tokenLimit) {
        Objects.requireNonNull(source);
        if (sourceLimit<1 || tokenLimit<1) throw new IllegalArgumentException("Molang limits must be positive");
        if (source.length()>sourceLimit) throw syntax("Source exceeds "+sourceLimit+" characters",0);
        return new MolangProgram(new Parser(source,tokenLimit).parse());
    }
    public double evaluate(MolangContext context) {
        Objects.requireNonNull(context);
        return root.run(new Evaluation(context));
    }
    private enum Flow { NONE, RETURN, BREAK, CONTINUE }
    private static final class Evaluation {
        final MolangContext context;
        final Map<String, Double> temporary = new HashMap<>();
        Flow flow = Flow.NONE;
        Evaluation(MolangContext context) { this.context = context; }
    }
    @FunctionalInterface private interface Operation { double run(Evaluation evaluation); }
    private record Node(int height, Operation operation, String writable) {
        double run(Evaluation evaluation) {
            ScriptBudget budget = evaluation.context.budget();
            budget.enter();
            try { return MolangContext.finite(operation.run(evaluation)); }
            finally { budget.leave(); }
        }
    }
    private static Node node(Operation operation, Node... children) {
        int depth = 1;
        for (Node child : children) depth = Math.max(depth, child.height + 1);
        if (depth > MAX_TREE_DEPTH) throw syntax("Expression tree exceeds depth 128", 0);
        return new Node(depth, operation, null);
    }
    private static MolangException syntax(String message, int position) {
        return new MolangException(MolangException.Reason.SYNTAX, message + " at character " + position);
    }
    private static MolangException unsupported(String message, int position) {
        return new MolangException(MolangException.Reason.UNSUPPORTED, message + " at character " + position);
    }
    private static boolean truth(double value) { return value != 0; }
    private static double bool(boolean value) { return value ? 1 : 0; }
    private static double math(String name, double[] a, Evaluation evaluation) {
        return switch (name) {
            case "abs" -> Math.abs(a[0]);
            case "sin" -> Math.sin(Math.toRadians(a[0]));
            case "cos" -> Math.cos(Math.toRadians(a[0]));
            case "tan" -> Math.tan(Math.toRadians(a[0]));
            case "asin" -> Math.toDegrees(Math.asin(a[0]));
            case "acos" -> Math.toDegrees(Math.acos(a[0]));
            case "atan" -> Math.toDegrees(Math.atan(a[0]));
            case "atan2" -> Math.toDegrees(Math.atan2(a[0], a[1]));
            case "ceil" -> Math.ceil(a[0]);
            case "floor" -> Math.floor(a[0]);
            case "round" -> Math.floor(a[0] + 0.5);
            case "trunc" -> a[0] < 0 ? Math.ceil(a[0]) : Math.floor(a[0]);
            case "sqrt" -> Math.sqrt(a[0]);
            case "pow" -> Math.pow(a[0], a[1]);
            case "exp" -> Math.exp(a[0]);
            case "ln" -> Math.log(a[0]);
            case "min" -> Math.min(a[0], a[1]);
            case "max" -> Math.max(a[0], a[1]);
            case "clamp" -> Math.max(a[1], Math.min(a[2], a[0]));
            case "lerp" -> a[0] + (a[1] - a[0]) * a[2];
            case "mod" -> a[1] == 0 ? 0 : a[0] % a[1];
            case "sign" -> Math.signum(a[0]);
            case "hermite_blend", "hermite" -> a[0] * a[0] * (3 - 2 * a[0]);
            case "min_angle" -> angle(a[0]);
            case "lerprotate" -> a[0] + angle(a[1] - a[0]) * a[2];
            case "random" -> evaluation.context.random(a[0], a[1]);
            case "random_integer", "randomi" -> evaluation.context.randomInteger(a[0], a[1]);
            case "die_roll", "roll" -> evaluation.context.roll(a[0], a[1], a[2], false);
            case "die_roll_integer", "rolli" -> evaluation.context.roll(a[0], a[1], a[2], true);
            default -> throw new AssertionError(name);
        };
    }
    private static double angle(double input) {
        double result = input % 360;
        return result >= 180 ? result - 360 : result < -180 ? result + 360 : result;
    }

    private static final class Parser {
        final String source;
        int cursor;
        int position;
        int tokens;
        int nesting;
        int loops;
        final int tokenLimit;
        String token;
        Parser(String source,int tokenLimit) { this.source=source; this.tokenLimit=tokenLimit; next(); }
        Node parse() {
            Node root = sequence("");
            require("");
            return root;
        }
        Node sequence(String end) {
            List<Node> statements = new ArrayList<>();
            while (!token.equals(end)) {
                if (token.isEmpty()) throw syntax("Unterminated block", position);
                if (accept(";")) continue;
                Node statement = expression(0);
                statements.add(statement);
                if (!accept(";") && !token.equals(end)) throw syntax("Expected semicolon", position);
            }
            Node[] children = statements.toArray(Node[]::new);
            return node(e -> {
                double result = 0;
                for (Node statement : children) {
                    result = statement.run(e);
                    if (e.flow != Flow.NONE) break;
                }
                return result;
            }, children);
        }
        Node expression(int minimum) {
            if (++nesting > MAX_TREE_DEPTH) throw syntax("Parser nesting exceeds 128", position);
            try {
                Node left = primary();
                while (true) {
                    if (token.equals("?") && minimum <= 2) {
                        next();
                        Node condition = left;
                        Node yes = expression(0);
                        Node no = accept(":") ? expression(2) : node(e -> 0);
                        left = node(e -> truth(condition.run(e)) ? yes.run(e) : no.run(e), condition, yes, no);
                        continue;
                    }
                    int precedence = precedence(token);
                    if (precedence < minimum || precedence < 0) break;
                    String op = token;
                    next();
                    Node right = expression(op.equals("=") ? precedence : precedence + 1);
                    Node first = left;
                    if (op.equals("=")) {
                        String target = first.writable;
                        if (target == null) throw syntax("Assignment requires variable or temp", position);
                        left = node(e -> {
                            double value = right.run(e);
                            if (target.startsWith("v.")) e.context.variable(target.substring(2), value);
                            else e.temporary.put(target.substring(2), value);
                            return value;
                        }, right);
                    } else {
                        left = node(e -> binary(op, first, right, e), first, right);
                    }
                }
                return left;
            } finally { nesting--; }
        }
        Node primary() {
            if (accept("(")) {
                Node result = expression(0);
                require(")");
                return result;
            }
            if (accept("{")) {
                Node result = sequence("}");
                require("}");
                return result;
            }
            if (token.equals("-") || token.equals("+") || token.equals("!")) {
                String op = token;
                next();
                Node value = expression(9);
                return node(e -> {
                    double number = value.run(e);
                    return op.equals("-") ? -number : op.equals("!") ? bool(!truth(number)) : number;
                }, value);
            }
            if (accept("return")) {
                Node value = token.equals(";") || token.equals("}") || token.isEmpty() ? node(e -> 0) : expression(0);
                return node(e -> { double result = value.run(e); e.flow = Flow.RETURN; return result; }, value);
            }
            if (token.equals("break") || token.equals("continue")) {
                if (loops == 0) throw syntax("Loop control outside loop", position);
                Flow flow = token.equals("break") ? Flow.BREAK : Flow.CONTINUE;
                next();
                return node(e -> { e.flow = flow; return 0; });
            }
            if (token.isEmpty()) throw syntax("Expected expression", position);
            String name = token;
            int start = position;
            next();
            if (Character.isDigit(name.charAt(0)) || name.charAt(0) == '.') {
                try {
                    double number = Double.parseDouble(name);
                    if (!Double.isFinite(number)) throw syntax("Non-finite numeric literal", start);
                    return node(e -> number);
                } catch (NumberFormatException ex) { throw syntax("Invalid number", start); }
            }
            if (accept("(")) {
                if (name.equals("loop")) {
                    Node count = expression(0);
                    require(",");
                    loops++;
                    Node body;
                    try { body = expression(0); } finally { loops--; }
                    require(")");
                    return node(e -> {
                        long times = (long) Math.max(0, Math.floor(count.run(e)));
                        double result = 0;
                        for (long iteration = 0; iteration < times; iteration++) {
                            e.context.budget().step();
                            result = body.run(e);
                            if (e.flow == Flow.RETURN) return result;
                            if (e.flow == Flow.BREAK) { e.flow = Flow.NONE; break; }
                            e.flow = Flow.NONE;
                        }
                        return result;
                    }, count, body);
                }
                List<Node> arguments = new ArrayList<>();
                if (!token.equals(")")) {
                    do {
                        if (arguments.size() == 64) throw unsupported("At most 64 function arguments", position);
                        arguments.add(expression(0));
                    } while (accept(","));
                }
                require(")");
                Node[] args = arguments.toArray(Node[]::new);
                if (name.startsWith("math.")) {
                    String function = name.substring(5);
                    Integer arity = MATH.get(function);
                    if (arity == null) throw unsupported("Unsupported math function: " + name, start);
                    if (arity != args.length) throw new MolangException(MolangException.Reason.ARITY, "Wrong arity: " + name);
                    return node(e -> math(function, arguments(args, e), e), args);
                }
                if (!name.startsWith("function.") && !name.startsWith("f.")) {
                    throw unsupported("Unsupported function: " + name, start);
                }
                String function = name.substring(name.indexOf('.') + 1);
                return node(e -> {
                    double[] values = arguments(args, e);
                    Map<String, Double> parameters = new HashMap<>();
                    for (int i = 0; i < values.length; i++) parameters.put("arg" + i, values[i]);
                    return e.context.function(function).evaluate(e.context.invocation(parameters));
                }, args);
            }
            if (name.equals("true")) return node(e -> 1);
            if (name.equals("false")) return node(e -> 0);
            if (name.equals("math.pi")) return node(e -> Math.PI);
            if (name.equals("math.e")) return node(e -> Math.E);
            int dot = name.indexOf('.');
            if (dot <= 0 || dot == name.length() - 1) {
                throw unsupported("Unsupported name: " + name, start);
            }
            String namespace = name.substring(0, dot);
            String field = name.substring(dot + 1);
            if (field.indexOf('.') >= 0 && !namespace.equals("variable") && !namespace.equals("v")) {
                throw unsupported("Unsupported name: " + name, start);
            }
            return switch (namespace) {
                case "query", "q" -> node(e -> e.context.query(field));
                case "ysm" -> node(e -> e.context.query("ysm." + field));
                case "context", "c" -> node(e -> e.context.parameter(field));
                case "variable", "v" -> new Node(1, e -> e.context.variable(field), "v." + field);
                case "temp", "t" -> new Node(1, e -> e.temporary.getOrDefault(field, 0.0), "t." + field);
                default -> throw unsupported("Unsupported namespace: " + namespace, start);
            };
        }
        boolean accept(String expected) {
            if (!token.equals(expected)) return false;
            next();
            return true;
        }
        void require(String expected) {
            if (!accept(expected)) throw syntax("Expected '" + expected + "', found '" + token + "'", position);
        }
        void next() {
            while (true) {
                while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
                if (source.startsWith("//", cursor)) {
                    while (cursor < source.length() && source.charAt(cursor) != '\n') cursor++;
                } else if (source.startsWith("/*", cursor)) {
                    int end = source.indexOf("*/", cursor + 2);
                    if (end < 0) throw syntax("Unterminated comment", cursor);
                    cursor = end + 2;
                } else break;
            }
            position = cursor;
            if (++tokens>tokenLimit) throw syntax("Token count exceeds "+tokenLimit,cursor);
            if (cursor == source.length()) { token = ""; return; }
            char c = source.charAt(cursor++);
            if (Character.isDigit(c) || (c == '.' && cursor < source.length() && Character.isDigit(source.charAt(cursor)))) {
                while (cursor < source.length() && (Character.isDigit(source.charAt(cursor)) || source.charAt(cursor) == '.')) cursor++;
                if (cursor < source.length() && (source.charAt(cursor) == 'e' || source.charAt(cursor) == 'E')) {
                    cursor++;
                    if (cursor < source.length() && (source.charAt(cursor) == '+' || source.charAt(cursor) == '-')) cursor++;
                    while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
                }
            } else if (Character.isLetter(c) || c == '_') {
                while (cursor < source.length()) {
                    char n = source.charAt(cursor);
                    if (!Character.isLetterOrDigit(n) && n != '_' && n != '.') break;
                    cursor++;
                }
            } else if ("+-*/%<>=!&|?:;,(){}".indexOf(c) >= 0) {
                if (cursor < source.length()) {
                    String pair = source.substring(position, cursor + 1);
                    if (List.of("==", "!=", "<=", ">=", "&&", "||").contains(pair)) cursor++;
                    if (pair.equals("->") || pair.equals("??")) throw unsupported("Unsupported operator: " + pair, position);
                }
            } else throw unsupported("Unsupported character: " + c, position);
            token = source.substring(position, cursor).toLowerCase(Locale.ROOT);
        }
    }
    private static double[] arguments(Node[] args, Evaluation e) {
        double[] values = new double[args.length];
        for (int i = 0; i < args.length; i++) values[i] = args[i].run(e);
        return values;
    }
    private static int precedence(String operator) {
        return switch (operator) {
            case "=" -> 1;
            case "||" -> 3;
            case "&&" -> 4;
            case "==", "!=" -> 5;
            case "<", "<=", ">", ">=" -> 6;
            case "+", "-" -> 7;
            case "*", "/", "%" -> 8;
            default -> -1;
        };
    }
    private static double binary(String op, Node left, Node right, Evaluation e) {
        double a = left.run(e);
        if (op.equals("&&")) return bool(truth(a) && truth(right.run(e)));
        if (op.equals("||")) return bool(truth(a) || truth(right.run(e)));
        double b = right.run(e);
        return switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> b == 0 ? 0 : a / b;
            case "%" -> b == 0 ? 0 : a % b;
            case "==" -> bool(a == b);
            case "!=" -> bool(a != b);
            case "<" -> bool(a < b);
            case "<=" -> bool(a <= b);
            case ">" -> bool(a > b);
            case ">=" -> bool(a >= b);
            default -> throw new AssertionError(op);
        };
    }
}
