package dev.spm.core.scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owner-confined filters; the frame clock advances before scripts supply the next inputs. */
final class MolangPhysics {
    private final Map<String, Filter> filters = new LinkedHashMap<>();
    private double lastSeconds;
    private boolean hasClock;

    MolangPhysics copy() {
        var result = new MolangPhysics();
        result.lastSeconds = lastSeconds;
        result.hasClock = hasClock;
        filters.forEach((key, value) -> result.filters.put(key, value.copy()));
        return result;
    }

    void advance(double seconds, ScriptBudget budget) {
        budget.step(); finite(seconds);
        if (seconds < 0 || (hasClock && seconds < lastSeconds))
            throw invalid("Physics time must be nonnegative and monotonic");
        if (hasClock && seconds == lastSeconds) return;
        if (hasClock && lastSeconds > 0) {
            double elapsed = finite(seconds - lastSeconds);
            // Commit a complete step only after every filter and budget check succeeds.
            var next = new LinkedHashMap<String, Filter>();
            for (var entry : filters.entrySet()) {
                budget.step();
                var filter = entry.getValue().copy();
                filter.advance(elapsed, budget);
                next.put(entry.getKey(), filter);
            }
            filters.clear(); filters.putAll(next);
        }
        lastSeconds = seconds;
        hasClock = true;
    }

    double first(String key, double input, double response, ScriptBudget budget) {
        return call(key, input, response, 0, 0, false, budget);
    }

    double second(String key, double input, double frequency, double damping,
                  double response, ScriptBudget budget) {
        return call(key, input, frequency, damping, response, true, budget);
    }

    private double call(String key, double input, double a, double b, double c,
                        boolean second, ScriptBudget budget) {
        budget.step(); Objects.requireNonNull(key);
        if (key.length() > 65_536)
            throw new MolangException(MolangException.Reason.CAPACITY, "Physics key too long");
        if (key.isEmpty()) return 0;
        finite(input); finite(a); finite(b); finite(c);
        var old = filters.get(key);
        boolean type = old == null ? second : old.second;
        if (!type && a <= 0) throw invalid("Physics response must be positive");
        if (old == null) {
            if (filters.size() >= 4096)
                throw new MolangException(MolangException.Reason.CAPACITY, "Physics filter capacity exhausted");
            old = new Filter(type);
            filters.put(key, old);
            old.set(input, a, b, c);
            return input;
        }
        old.set(input, a, b, c);
        return finite(old.position);
    }

    private static double finite(double value) {
        if (!Double.isFinite(value)) throw invalid("Non-finite physics value");
        return value;
    }

    private static MolangException invalid(String message) {
        return new MolangException(MolangException.Reason.NON_FINITE, message);
    }

    private static final class Filter {
        final boolean second;
        double input, a, b, c, previousInput, position, velocity;
        Filter(boolean second) { this.second = second; }
        void set(double input, double a, double b, double c) {
            this.input = input; this.a = a; this.b = b; this.c = c;
        }
        Filter copy() {
            var result = new Filter(second);
            result.set(input, a, b, c);
            result.previousInput = previousInput;
            result.position = position;
            result.velocity = velocity;
            return result;
        }
        void advance(double elapsed, ScriptBudget budget) {
            if (!second) {
                budget.step();
                double fraction = finite(elapsed / a);
                position = finite((1 - fraction) * position + fraction * input);
                return;
            }
            double frequency = Math.max(0,Math.min(5, a));
            // The reference's zero-frequency update runs zero substeps; retain y/v and
            // remember the latest input without constructing non-finite coefficients.
            if(frequency==0) { previousInput=input; return; }
            double damping = Math.max(0, Math.min(1, b));
            double angular = 2 * Math.PI * frequency;
            double k1 = finite(2 * damping / angular);
            double k2 = finite(1 / (angular * angular));
            double k3 = finite(c * damping / angular);
            double derivative = finite((input - previousInput) / elapsed);
            double maximumStep = finite(Math.sqrt(4 * k2 + k1 * k1) - k1);
            if (maximumStep <= 0) throw invalid("Physics stable step is not representable");
            double count = Math.ceil(elapsed / maximumStep);
            if (!Double.isFinite(count) || count > Integer.MAX_VALUE)
                throw new MolangException(MolangException.Reason.STEPS, "Physics substep budget exceeded");
            int steps = Math.max(1, (int) count);
            double dt = finite(elapsed / steps);
            for (int i = 0; i < steps; i++) {
                budget.step();
                position = finite(position + dt * velocity);
                velocity = finite(velocity + dt * (k3 * derivative + input - position - k1 * velocity) / k2);
            }
            previousInput = input;
        }
    }
}
