package dev.spm.core.lua;

import java.util.ArrayList;
import java.util.List;

/** Bounded pure-Java Lua pattern engine (find, match, gmatch, gsub, format). */
final class LuaPatterns {
    private static final int MAX_CAPTURES = 32;
    private static final int MAX_DEPTH = 64;

    private LuaPatterns() {}

    static final class MatchState {
        final String src;
        final String pat;
        final LuaBudget budget;
        final int[] capInit = new int[MAX_CAPTURES];
        final int[] capLen = new int[MAX_CAPTURES];
        int level;
        int depth;

        MatchState(String src, String pat, LuaBudget budget) {
            this.src = src;
            this.pat = pat;
            this.budget = budget;
        }
    }

    static LuaValue[] find(LuaVm vm, String s, String pattern, int init, boolean plain) {
        int start = relativePos(init, s.length());
        if (start < 0) start = 0;
        if (start > s.length()) return new LuaValue[] {LuaValue.NIL};
        if (plain || !hasSpecials(pattern)) {
            int idx = s.indexOf(pattern, start);
            if (idx < 0) return new LuaValue[] {LuaValue.NIL};
            return new LuaValue[] {LuaValue.of(idx + 1L), LuaValue.of((long) (idx + pattern.length()))};
        }
        int p = 0;
        boolean anchor = !pattern.isEmpty() && pattern.charAt(0) == '^';
        if (anchor) p = 1;
        var ms = new MatchState(s, pattern, vm.budget());
        for (int si = start; si <= s.length(); si++) {
            ms.budget.step();
            ms.level = 0;
            ms.depth = 0;
            int res = match(ms, si, p);
            if (res >= 0) {
                var caps = extractCaptures(ms, si, res, false);
                var out = new LuaValue[2 + caps.size()];
                out[0] = LuaValue.of(si + 1L);
                out[1] = LuaValue.of((long) res);
                for (int i = 0; i < caps.size(); i++) out[2 + i] = caps.get(i);
                return out;
            }
            if (anchor) break;
        }
        return new LuaValue[] {LuaValue.NIL};
    }

    static LuaValue[] match(LuaVm vm, String s, String pattern, int init) {
        int start = relativePos(init, s.length());
        if (start < 0) start = 0;
        if (start > s.length()) return new LuaValue[] {LuaValue.NIL};
        int p = 0;
        boolean anchor = !pattern.isEmpty() && pattern.charAt(0) == '^';
        if (anchor) p = 1;
        var ms = new MatchState(s, pattern, vm.budget());
        for (int si = start; si <= s.length(); si++) {
            ms.budget.step();
            ms.level = 0;
            ms.depth = 0;
            int res = match(ms, si, p);
            if (res >= 0) {
                var caps = extractCaptures(ms, si, res, true);
                return caps.toArray(LuaValue[]::new);
            }
            if (anchor) break;
        }
        return new LuaValue[] {LuaValue.NIL};
    }

    static LuaFunction gmatch(LuaVm vm, String s, String pattern) {
        final int[] nextPos = {0};
        final int[] lastEnd = {-1};
        return LuaFunction.nativeFn("gmatch_iter", (ignoredVm, args) -> {
            int p = 0;
            boolean anchor = !pattern.isEmpty() && pattern.charAt(0) == '^';
            if (anchor) p = 1;
            var ms = new MatchState(s, pattern, vm.budget());
            for (int si = nextPos[0]; si <= s.length(); si++) {
                ms.budget.step();
                ms.level = 0;
                ms.depth = 0;
                int res = match(ms, si, p);
                if (res >= 0 && res != lastEnd[0]) {
                    lastEnd[0] = res;
                    nextPos[0] = res > si ? res : si + 1;
                    return extractCaptures(ms, si, res, true).toArray(LuaValue[]::new);
                }
                if (anchor) break;
            }
            return LuaValues.empty();
        });
    }

    static LuaValue[] gsub(LuaVm vm, String s, String pattern, LuaValue repl, int maxSubs) {
        int p = 0;
        boolean anchor = !pattern.isEmpty() && pattern.charAt(0) == '^';
        if (anchor) p = 1;
        var out = new StringBuilder();
        var ms = new MatchState(s, pattern, vm.budget());
        int srcPos = 0;
        int lastMatch = -1;
        int n = 0;
        while (n < maxSubs && srcPos <= s.length()) {
            ms.budget.step();
            ms.level = 0;
            ms.depth = 0;
            int e = match(ms, srcPos, p);
            if (e >= 0 && e != lastMatch) {
                n++;
                lastMatch = e;
                out.append(addReplacement(vm, ms, repl, srcPos, e));
                if (e > srcPos) {
                    srcPos = e;
                } else if (srcPos < s.length()) {
                    out.append(s.charAt(srcPos++));
                } else {
                    break;
                }
            } else if (srcPos < s.length()) {
                out.append(s.charAt(srcPos++));
            } else {
                break;
            }
            if (anchor) break;
        }
        if (srcPos < s.length()) out.append(s, srcPos, s.length());
        String result = out.toString();
        vm.budget().chargeMemory((long) result.length() * 2L);
        return new LuaValue[] {LuaValue.of(result), LuaValue.of((long) n)};
    }

    private static String addReplacement(LuaVm vm, MatchState ms, LuaValue repl, int si, int ei) {
        var caps = extractCaptures(ms, si, ei, true);
        if (repl instanceof LuaValue.Str || repl instanceof LuaValue.Num) {
            String tpl = repl.asString();
            String whole = ms.src.substring(si, ei);
            var sb = new StringBuilder();
            for (int i = 0; i < tpl.length(); i++) {
                char c = tpl.charAt(i);
                if (c != '%') {
                    sb.append(c);
                    continue;
                }
                if (i + 1 >= tpl.length()) throw new LuaException(LuaException.Reason.RUNTIME, "invalid use of '%' in replacement string");
                char next = tpl.charAt(++i);
                if (next == '%') {
                    sb.append('%');
                } else if (next == '0') {
                    sb.append(whole);
                } else if (next >= '1' && next <= '9') {
                    int idx = next - '1';
                    if (ms.level == 0 && idx == 0) sb.append(whole);
                    else if (idx < caps.size()) sb.append(vm.evaluator().toLuaString(caps.get(idx)));
                    else throw new LuaException(LuaException.Reason.RUNTIME, "invalid capture index %" + next);
                } else {
                    throw new LuaException(LuaException.Reason.RUNTIME, "invalid use of '%' in replacement string");
                }
            }
            return sb.toString();
        }
        if (repl instanceof LuaTable t) {
            LuaValue key = caps.isEmpty() ? LuaValue.of(ms.src.substring(si, ei)) : caps.get(0);
            LuaValue val = vm.evaluator().getIndex(t, key, 1);
            return val.isTruthy() ? vm.evaluator().toLuaString(val) : ms.src.substring(si, ei);
        }
        if (repl instanceof LuaFunction fn) {
            LuaValue[] args = caps.isEmpty() ? new LuaValue[] {LuaValue.of(ms.src.substring(si, ei))} : caps.toArray(LuaValue[]::new);
            LuaValue res = LuaValues.first(fn.call(vm, args));
            return res.isTruthy() ? vm.evaluator().toLuaString(res) : ms.src.substring(si, ei);
        }
        throw new LuaException(LuaException.Reason.RUNTIME, "string/function/table expected as gsub replacement");
    }

    private static int match(MatchState ms, int s, int p) {
        if (++ms.depth > MAX_DEPTH) throw new LuaException(LuaException.Reason.BUDGET, "pattern too complex");
        try {
            ms.budget.step();
            while (p < ms.pat.length()) {
                char pc = ms.pat.charAt(p);
                switch (pc) {
                    case '(' -> {
                        if (p + 1 < ms.pat.length() && ms.pat.charAt(p + 1) == ')') {
                            return startCapture(ms, s, p + 2, -2);
                        }
                        return startCapture(ms, s, p + 1, -1);
                    }
                    case ')' -> {
                        return endCapture(ms, s, p + 1);
                    }
                    case '$' -> {
                        if (p + 1 == ms.pat.length()) return s == ms.src.length() ? s : -1;
                    }
                    case '%' -> {
                        if (p + 1 < ms.pat.length() && ms.pat.charAt(p + 1) == 'b') {
                            s = matchBalance(ms, s, p + 2);
                            if (s < 0) return -1;
                            p += 4;
                            continue;
                        }
                        if (p + 1 < ms.pat.length() && ms.pat.charAt(p + 1) == 'f') {
                            p += 2;
                            if (p >= ms.pat.length() || ms.pat.charAt(p) != '[') {
                                throw new LuaException(LuaException.Reason.RUNTIME, "missing '[' after '%f' in pattern");
                            }
                            int ep = classEnd(ms.pat, p);
                            char prev = s == 0 ? '\0' : ms.src.charAt(s - 1);
                            char cur = s == ms.src.length() ? '\0' : ms.src.charAt(s);
                            if (!matchBracketClass(prev, ms.pat, p, ep - 1) && matchBracketClass(cur, ms.pat, p, ep - 1)) {
                                p = ep;
                                continue;
                            }
                            return -1;
                        }
                    }
                    default -> {}
                }
                int ep = classEnd(ms.pat, p);
                boolean matched = s < ms.src.length() && singleMatch(ms, ms.src.charAt(s), p, ep);
                char mod = ep < ms.pat.length() ? ms.pat.charAt(ep) : '\0';
                switch (mod) {
                    case '?' -> {
                        if (matched) {
                            int res = match(ms, s + 1, ep + 1);
                            if (res >= 0) return res;
                        }
                        p = ep + 1;
                    }
                    case '*' -> { return maxExpand(ms, s, p, ep); }
                    case '+' -> { return matched ? maxExpand(ms, s + 1, p, ep) : -1; }
                    case '-' -> { return minExpand(ms, s, p, ep); }
                    default -> {
                        if (!matched) return -1;
                        s++;
                        p = ep;
                    }
                }
            }
            return s;
        } finally {
            ms.depth--;
        }
    }

    private static int maxExpand(MatchState ms, int s, int p, int ep) {
        int i = 0;
        while (s + i < ms.src.length() && singleMatch(ms, ms.src.charAt(s + i), p, ep)) i++;
        while (i >= 0) {
            int res = match(ms, s + i, ep + 1);
            if (res >= 0) return res;
            i--;
        }
        return -1;
    }

    private static int minExpand(MatchState ms, int s, int p, int ep) {
        while (true) {
            int res = match(ms, s, ep + 1);
            if (res >= 0) return res;
            if (s < ms.src.length() && singleMatch(ms, ms.src.charAt(s), p, ep)) s++;
            else return -1;
        }
    }

    private static int startCapture(MatchState ms, int s, int p, int what) {
        if (ms.level >= MAX_CAPTURES) throw new LuaException(LuaException.Reason.RUNTIME, "too many captures");
        int lvl = ms.level;
        ms.capInit[lvl] = s;
        ms.capLen[lvl] = what;
        ms.level = lvl + 1;
        int res = match(ms, s, p);
        if (res < 0) ms.level = lvl;
        return res;
    }

    private static int endCapture(MatchState ms, int s, int p) {
        int l = ms.level - 1;
        while (l >= 0 && ms.capLen[l] != -1) l--;
        if (l < 0) throw new LuaException(LuaException.Reason.RUNTIME, "invalid pattern capture");
        ms.capLen[l] = s - ms.capInit[l];
        int res = match(ms, s, p);
        if (res < 0) ms.capLen[l] = -1;
        return res;
    }

    private static int matchBalance(MatchState ms, int s, int p) {
        if (p + 1 >= ms.pat.length()) throw new LuaException(LuaException.Reason.RUNTIME, "malformed pattern (missing arguments to '%b')");
        char open = ms.pat.charAt(p);
        char close = ms.pat.charAt(p + 1);
        if (s >= ms.src.length() || ms.src.charAt(s) != open) return -1;
        int cont = 1;
        int pos = s + 1;
        while (pos < ms.src.length()) {
            char c = ms.src.charAt(pos++);
            if (c == close) {
                if (--cont == 0) return pos;
            } else if (c == open) {
                cont++;
            }
        }
        return -1;
    }

    private static List<LuaValue> extractCaptures(MatchState ms, int si, int ei, boolean wholeOnEmpty) {
        var out = new ArrayList<LuaValue>();
        if (ms.level == 0) {
            if (wholeOnEmpty) out.add(LuaValue.of(ms.src.substring(si, ei)));
            return out;
        }
        for (int i = 0; i < ms.level; i++) {
            int len = ms.capLen[i];
            if (len == -1) throw new LuaException(LuaException.Reason.RUNTIME, "unfinished capture");
            if (len == -2) {
                out.add(LuaValue.of(ms.capInit[i] + 1L));
            } else {
                out.add(LuaValue.of(ms.src.substring(ms.capInit[i], ms.capInit[i] + len)));
            }
        }
        return out;
    }

    private static int classEnd(String pat, int p) {
        char c = pat.charAt(p++);
        switch (c) {
            case '%' -> {
                if (p >= pat.length()) throw new LuaException(LuaException.Reason.RUNTIME, "malformed pattern (ends with '%')");
                return p + 1;
            }
            case '[' -> {
                if (p < pat.length() && pat.charAt(p) == '^') p++;
                do {
                    if (p >= pat.length()) throw new LuaException(LuaException.Reason.RUNTIME, "malformed pattern (missing ']')");
                    if (pat.charAt(p++) == '%' && p < pat.length()) p++;
                } while (p >= pat.length() || pat.charAt(p) != ']');
                return p + 1;
            }
            default -> { return p; }
        }
    }

    private static boolean singleMatch(MatchState ms, char c, int p, int ep) {
        char pc = ms.pat.charAt(p);
        return switch (pc) {
            case '.' -> true;
            case '%' -> matchClass(c, ms.pat.charAt(p + 1));
            case '[' -> matchBracketClass(c, ms.pat, p, ep - 1);
            default -> pc == c;
        };
    }

    private static boolean matchBracketClass(char c, String pat, int p, int ec) {
        boolean sig = true;
        if (p + 1 < ec && pat.charAt(p + 1) == '^') {
            sig = false;
            p++;
        }
        while (++p < ec) {
            char cur = pat.charAt(p);
            if (cur == '%') {
                p++;
                if (matchClass(c, pat.charAt(p))) return sig;
            } else if (p + 2 < ec && pat.charAt(p + 1) == '-') {
                char endRange = pat.charAt(p + 2);
                p += 2;
                if (c >= cur && c <= endRange) return sig;
            } else if (cur == c) {
                return sig;
            }
        }
        return !sig;
    }

    private static boolean matchClass(char c, char cl) {
        boolean res = switch (Character.toLowerCase(cl)) {
            case 'a' -> isAsciiLetter(c);
            case 'c' -> c < 0x20 || c == 0x7f;
            case 'd' -> c >= '0' && c <= '9';
            case 'g' -> c > 0x20 && c < 0x7f;
            case 'l' -> c >= 'a' && c <= 'z';
            case 'p' -> (c >= '!' && c <= '/') || (c >= ':' && c <= '@') || (c >= '[' && c <= '`') || (c >= '{' && c <= '~');
            case 's' -> Character.isWhitespace(c);
            case 'u' -> c >= 'A' && c <= 'Z';
            case 'w' -> isAsciiLetter(c) || (c >= '0' && c <= '9');
            case 'x' -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            case 'z' -> c == '\0';
            default -> { yield cl == c; }
        };
        char lower = Character.toLowerCase(cl);
        if (lower == 'a' || lower == 'c' || lower == 'd' || lower == 'g' || lower == 'l'
                || lower == 'p' || lower == 's' || lower == 'u' || lower == 'w' || lower == 'x' || lower == 'z') {
            return Character.isLowerCase(cl) ? res : !res;
        }
        return res;
    }

    static String format(LuaVm vm, String fmt, LuaValue[] args) {
        var out = new StringBuilder();
        int argIdx = 1;
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '%') {
                out.append(c);
                continue;
            }
            if (i + 1 < fmt.length() && fmt.charAt(i + 1) == '%') {
                out.append('%');
                i++;
                continue;
            }
            int specStart = i;
            i++;
            while (i < fmt.length() && "-+ #0123456789.".indexOf(fmt.charAt(i)) >= 0) i++;
            if (i >= fmt.length()) throw new LuaException(LuaException.Reason.RUNTIME, "invalid format specifier");
            char conv = fmt.charAt(i);
            String spec = "%" + fmt.substring(specStart + 1, i + 1);
            LuaValue arg = LuaValues.arg(args, argIdx++);
            switch (conv) {
                case 'd', 'i', 'u', 'x', 'X', 'o' -> {
                    long iv = (long) arg.asDouble();
                    out.append(String.format(java.util.Locale.ROOT, spec.replace('u', 'd'), conv == 'u' ? Long.toUnsignedString(iv) : iv));
                }
                case 'f', 'e', 'E', 'g', 'G' -> out.append(String.format(java.util.Locale.ROOT, spec, arg.asDouble()));
                case 'c' -> out.append((char) (int) arg.asDouble());
                case 's' -> out.append(String.format(java.util.Locale.ROOT, spec, vm.evaluator().toLuaString(arg)));
                case 'q' -> out.append(quoteLuaString(arg.asString()));
                default -> throw new LuaException(LuaException.Reason.RUNTIME, "invalid conversion '%" + conv + "' to 'format'");
            }
        }
        return out.toString();
    }

    private static String quoteLuaString(String s) {
        var sb = new StringBuilder(s.length() + 4).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) sb.append('\\').append((int) c);
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    static int relativePos(int pos, int len) {
        if (pos > 0) return pos - 1;
        if (pos == 0) return 0;
        return Math.max(0, len + pos);
    }

    private static boolean hasSpecials(String p) {
        for (int i = 0; i < p.length(); i++) {
            if ("^$*+?.([%-".indexOf(p.charAt(i)) >= 0) return true;
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z'); }
}
