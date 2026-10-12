package monthly;

/**
 * 一个自然月的输入。净区间来水允许有依据的负增损；NaN专门表示资料缺失。
 * sourceInput5保留源文件数值；默认按用户确认解释为区间来水，不能固定为优化后的下游入流。
 */
public record MonthlyInput(
        int month, double days, double upstreamInflow, double intervalNetInflow,
        double sourceInput5,
        double upstreamEcologicalFlow, double downstreamEcologicalFlow,
        double upstreamLevelLimit, double downstreamLevelLimit) {

    public MonthlyInput {
        double[] values = {days, upstreamInflow, sourceInput5,
                upstreamEcologicalFlow, downstreamEcologicalFlow, upstreamLevelLimit, downstreamLevelLimit};
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("月输入必须为有限数");
        }
        if (month < 1 || month > 12 || days <= 0 || !Double.isFinite(days * 86_400.0)
                || upstreamInflow < 0 || sourceInput5 < 0 || Double.isInfinite(intervalNetInflow)
                || upstreamEcologicalFlow < 0 || downstreamEcologicalFlow < 0) {
            throw new IllegalArgumentException("月份、天数、参考泄流、入流或生态下泄输入不合法");
        }
    }

    public double seconds() { return days * 86_400.0; }
    public double hours() { return days * 24.0; }
}
