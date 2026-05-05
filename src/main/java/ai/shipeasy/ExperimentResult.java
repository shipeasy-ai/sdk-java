package ai.shipeasy;

public final class ExperimentResult {
    public final boolean inExperiment;
    public final String group;
    public final Object params;

    public ExperimentResult(boolean inExperiment, String group, Object params) {
        this.inExperiment = inExperiment;
        this.group = group;
        this.params = params;
    }
}
