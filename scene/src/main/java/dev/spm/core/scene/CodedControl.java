package dev.spm.core.scene;
/** Lexically installed candidate capability. Never exposed by public state snapshots. */
interface CodedControl {
    void select(String name,CodedLoopMode mode);
    void transition(double ticks);
    void resetPlayback();
    void reload();
}
