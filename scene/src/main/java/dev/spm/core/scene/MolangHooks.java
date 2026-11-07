package dev.spm.core.scene;

import java.util.List;

/** Immutable owner lifecycle programs. Every phase executes within the same unpublished frame candidate. */
public record MolangHooks(List<MolangProgram> initialize,List<MolangProgram> beforeStep,List<MolangProgram> afterStep,
                          List<MolangProgram> afterFrame,List<MolangProgram> defer,List<MolangProgram> sync) {
    public MolangHooks {
        initialize=List.copyOf(initialize);beforeStep=List.copyOf(beforeStep);afterStep=List.copyOf(afterStep);
        afterFrame=List.copyOf(afterFrame);defer=List.copyOf(defer);sync=List.copyOf(sync);
        long count=(long)initialize.size()+beforeStep.size()+afterStep.size()+afterFrame.size()+defer.size()+sync.size();
        if(count>4096)throw new IllegalArgumentException("At most 4096 lifecycle handlers");
    }
    public static MolangHooks empty() { return new MolangHooks(List.of(),List.of(),List.of(),List.of(),List.of(),List.of()); }
}
