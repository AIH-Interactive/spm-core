package dev.spm.core.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable skeleton. Pivots are model-space coordinates in Core units. */
public final class Skeleton {
    private final List<String> names;
    private final int[] parents;
    private final float[] pivots;
    private final int[] order;
    public Skeleton(List<String> names, int[] parents, float[] pivots) {
        this.names = List.copyOf(names);
        this.parents = parents.clone();
        this.pivots = pivots.clone();
        int n = names.size();
        if (parents.length != n || pivots.length != Math.multiplyExact(n, 3)) {
            throw new IllegalArgumentException("Skeleton array sizes disagree");
        }
        var unique = new HashSet<String>();
        for (String name : this.names) {
            if (Objects.requireNonNull(name).isBlank() || !unique.add(name)) {
                throw new IllegalArgumentException("Blank or duplicate bone name");
            }
        }
        for (float value : this.pivots) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite pivot");
        }
        int[] firstChild = new int[n];
        int[] next = new int[n];
        Arrays.fill(firstChild, -1);
        Arrays.fill(next, -1);
        order = new int[n];
        int tail = 0;
        for (int i = 0; i < n; i++) {
            int parent = this.parents[i];
            if (parent < -1 || parent >= n || parent == i) throw new IllegalArgumentException("Invalid bone parent");
            if (parent == -1) order[tail++] = i;
            else { next[i] = firstChild[parent]; firstChild[parent] = i; }
        }
        for (int head = 0; head < tail; head++) {
            for (int child = firstChild[order[head]]; child != -1; child = next[child]) order[tail++] = child;
        }
        if (tail != n) throw new IllegalArgumentException("Cyclic skeleton");
    }
    public int boneCount() { return parents.length; }
    public List<String> names() { return names; }
    public int parent(int bone) { return parents[bone]; }
    public float pivot(int bone, int axis) {
        Objects.checkIndex(axis, 3);
        Objects.checkIndex(bone, parents.length);
        return pivots[bone * 3 + axis];
    }
    public int[] parents() { return parents.clone(); }
    public float[] pivots() { return pivots.clone(); }
    public int[] evaluationOrder() { return order.clone(); }
}
