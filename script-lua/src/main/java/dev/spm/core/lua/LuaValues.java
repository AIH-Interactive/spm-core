package dev.spm.core.lua;

/** Number parsing, formatting, and value coercion helpers following Lua 5.3 semantics. */
public final class LuaValues {
    private LuaValues() {}

    public static LuaValue first(LuaValue[] values) {
        return values == null || values.length == 0 ? LuaValue.NIL : values[0];
    }

    public static LuaValue arg(LuaValue[] args, int index) {
        return index >= 0 && index < args.length ? args[index] : LuaValue.NIL;
    }

    public static String formatNumber(double value) {
        if (Double.isNaN(value)) return "nan";
        if (value == Double.POSITIVE_INFINITY) return "inf";
        if (value == Double.NEGATIVE_INFINITY) return "-inf";
        if (value == 0.0) return "0";
        double abs = Math.abs(value);
        if (value == Math.rint(value) && abs < 9.007199254740992E15) {
            return Long.toString((long) value);
        }
        String s = Double.toString(value);
        if (s.endsWith(".0")) return s.substring(0, s.length() - 2);
        return s;
    }

    public static Double parseNumberOrNull(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        boolean neg = false;
        if (s.startsWith("-")) {
            neg = true;
            s = s.substring(1);
        } else if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            try {
                if (s.indexOf('.') >= 0 || s.indexOf('p') >= 0 || s.indexOf('P') >= 0) {
                    String hex = s.indexOf('p') >= 0 || s.indexOf('P') >= 0 ? s : s + "p0";
                    double v = Double.parseDouble(hex);
                    return neg ? -v : v;
                }
                long v = Long.parseUnsignedLong(s.substring(2), 16);
                double d = (double) v;
                return neg ? -d : d;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        try {
            double v = Double.parseDouble(s);
            if (!Double.isFinite(v)) return null;
            return neg ? -v : v;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static LuaValue[] empty() { return new LuaValue[0]; }
    public static LuaValue[] single(LuaValue v) { return new LuaValue[] {v}; }
}
