package monthly;

import cascade.StationOperation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/** 两库联合水位状态 DP；只在所给网格与月平均模型内求全局最优。 */
public final class MonthlyDp {
    private final MonthlyProblem problem;

    public MonthlyDp(MonthlyProblem problem) {
        if (problem == null) throw new IllegalArgumentException("月调度问题不能为空");
        this.problem = problem;
    }

    public MonthlyResult solve() {
        checkTerminalWaterAvailability();
        if (problem.periods().stream().anyMatch(p -> Double.isNaN(p.intervalNetInflow()))) {
            throw new IllegalStateException("缺少区间来水：当前显式配置未提供有效区间序列。"
                    + "本次用户确认input5为区间量，可选择interval.mode=input5-as-interval；其它数据集请提供明确的区间资料。"
                    + "zPlanLYX按用户要求不参与计算。");
        }
        MonthlyStation upper = problem.upper();
        MonthlyStation lower = problem.lower();
        double[] zu = upper.levels();
        double[] zd = lower.levels();
        int nu = zu.length;
        int nd = zd.length;
        int months = problem.periods().size();
        int endU = upper.indexOf(upper.terminalLevel());
        int endD = lower.indexOf(lower.terminalLevel());
        double[][] previous = emptyValues(nu, nd);
        previous[upper.indexOf(upper.initialLevel())][lower.indexOf(lower.initialLevel())] = 0;
        int[][][] fromU = new int[months + 1][nu][nd];
        int[][][] fromD = new int[months + 1][nu][nd];
        for (int t = 0; t <= months; t++) {
            for (int u = 0; u < nu; u++) {
                Arrays.fill(fromU[t][u], -1);
                Arrays.fill(fromD[t][u], -1);
            }
        }

        for (int t = 1; t <= months; t++) {
            MonthlyInput input = problem.periods().get(t - 1);
            double[][] next = emptyValues(nu, nd);
            long[] rejectedU = new long[MonthlyPhysics.Rejection.values().length];
            long[] rejectedD = new long[MonthlyPhysics.Rejection.values().length];
            boolean reachable = false;
            // 末月只枚举指定终点；首月 previous 中只有精确的指定初值可达。
            int firstEndU = t == months ? endU : 0;
            int lastEndU = t == months ? endU + 1 : nu;
            int firstEndD = t == months ? endD : 0;
            int lastEndD = t == months ? endD + 1 : nd;
            for (int startU = 0; startU < nu; startU++) {
                boolean upperReachable = false;
                for (double value : previous[startU]) {
                    if (value != Double.NEGATIVE_INFINITY) { upperReachable = true; break; }
                }
                if (!upperReachable) continue;
                for (int finishU = firstEndU; finishU < lastEndU; finishU++) {
                    // 上库转移与下库起点无关，每个 (startU,finishU) 只计算一次。
                    StationOperation opU = MonthlyPhysics.evaluate(upper, zu[startU], zu[finishU],
                            input.upstreamInflow(), input.days(), input.upstreamEcologicalFlow(),
                            input.upstreamLevelLimit(), problem.enforceGuaranteedPower(), rejectedU);
                    if (opU == null) continue;
                    double lowerInflow = opU.release() + input.intervalNetInflow();
                    for (int startD = 0; startD < nd; startD++) {
                        if (previous[startU][startD] == Double.NEGATIVE_INFINITY) continue;
                        for (int finishD = firstEndD; finishD < lastEndD; finishD++) {
                            StationOperation opD = MonthlyPhysics.evaluate(lower, zd[startD], zd[finishD],
                                    lowerInflow, input.days(), input.downstreamEcologicalFlow(),
                                    input.downstreamLevelLimit(), problem.enforceGuaranteedPower(), rejectedD);
                            if (opD == null) continue;
                            double candidate = previous[startU][startD] + opU.energyMwh() + opD.energyMwh();
                            if (!Double.isFinite(candidate)) throw new IllegalArgumentException("累计发电量溢出");
                            if (candidate > next[finishU][finishD]) {
                                next[finishU][finishD] = candidate;
                                fromU[t][finishU][finishD] = startU;
                                fromD[t][finishU][finishD] = startD;
                                reachable = true;
                            }
                        }
                    }
                }
            }
            if (!reachable) {
                throw new IllegalStateException(t + " 月无可达联合状态"
                        + (t == months ? "（严格年末水位不可达）" : "")
                        + "。上库拒绝转移：" + explain(rejectedU) + "；下库拒绝转移：" + explain(rejectedD)
                        + "。请检查初末水位、生态流量、尾水曲线覆盖、保证出力开关和离散步长；程序未放宽约束。");
            }
            previous = next;
        }

        double optimum = previous[endU][endD];
        List<MonthlyPeriodResult> reverse = new ArrayList<>();
        int currentU = endU;
        int currentD = endD;
        for (int t = months; t >= 1; t--) {
            int startU = fromU[t][currentU][currentD];
            int startD = fromD[t][currentU][currentD];
            if (startU < 0 || startD < 0) throw new IllegalStateException("月 DP 回溯前驱缺失");
            MonthlyInput input = problem.periods().get(t - 1);
            StationOperation opU = MonthlyPhysics.evaluate(upper, zu[startU], zu[currentU],
                    input.upstreamInflow(), input.days(), input.upstreamEcologicalFlow(),
                    input.upstreamLevelLimit(), problem.enforceGuaranteedPower());
            if (opU == null) throw new IllegalStateException("回溯上库转移不可行");
            StationOperation opD = MonthlyPhysics.evaluate(lower, zd[startD], zd[currentD],
                    opU.release() + input.intervalNetInflow(), input.days(), input.downstreamEcologicalFlow(),
                    input.downstreamLevelLimit(), problem.enforceGuaranteedPower());
            if (opD == null) throw new IllegalStateException("回溯下库转移不可行");
            reverse.add(new MonthlyPeriodResult(input, opU, opD));
            currentU = startU;
            currentD = startD;
        }
        Collections.reverse(reverse);
        return new MonthlyResult(reverse, optimum);
    }

    /** 网格加密无法修复的必要条件先报错，避免把原始边界冲突误诊为 DP 精度问题。 */
    private void checkTerminalWaterAvailability() {
        MonthlyStation station = problem.upper();
        MonthlyInput last = problem.periods().get(problem.periods().size() - 1);
        MonthlyInput beforeLast = problem.periods().get(problem.periods().size() - 2);
        double maximumStartLevel = Math.min(station.maxLevel(),
                Math.min(last.upstreamLevelLimit(), beforeLast.upstreamLevelLimit()));
        double maximumStartStorage = station.levelStorage().at(maximumStartLevel);
        double terminalStorage = station.levelStorage().at(station.terminalLevel());
        double availableReleaseVolume = (maximumStartStorage - terminalStorage) * 1e8
                + last.upstreamInflow() * last.seconds();
        double ecologicalVolume = last.upstreamEcologicalFlow() * last.seconds();
        double shortage = ecologicalVolume - availableReleaseVolume;
        if (shortage > 1e-5) {
            double maximumTerminalStorage = maximumStartStorage
                    + (last.upstreamInflow() - last.upstreamEcologicalFlow()) * last.seconds() / 1e8;
            String highestEndLevel;
            if (maximumTerminalStorage < station.levelStorage().at(station.minLevel())) {
                highestEndLevel = "满足生态下泄后，月末库容将低于最低运行水位对应库容";
            } else {
                double lo = station.minLevel();
                double hi = maximumStartLevel;
                for (int i = 0; i < 80; i++) {
                    double middle = (lo + hi) / 2;
                    if (station.levelStorage().at(middle) <= maximumTerminalStorage) lo = middle;
                    else hi = middle;
                }
                highestEndLevel = String.format(Locale.ROOT, "满足生态下泄时理论最高年末水位约 %.6f m", lo);
            }
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "%s %d 月原始边界存在水量硬冲突：月入流 %.6f m³/s，生态下泄至少 %.6f m³/s，"
                            + "月初水位最高 %.6f m，指定年末水位 %.6f m；即使从最高允许水位开始，"
                            + "仍缺少水量 %.3f m³（%.6f 亿 m³）。%s。该冲突与网格步长无关，程序未放宽生态或年末约束。",
                    station.name(), last.month(), last.upstreamInflow(), last.upstreamEcologicalFlow(),
                    maximumStartLevel, station.terminalLevel(), shortage, shortage / 1e8, highestEndLevel));
        }
    }

    private static double[][] emptyValues(int nu, int nd) {
        double[][] values = new double[nu][nd];
        for (double[] row : values) Arrays.fill(row, Double.NEGATIVE_INFINITY);
        return values;
    }

    private static String explain(long[] counts) {
        StringJoiner result = new StringJoiner("、");
        for (MonthlyPhysics.Rejection reason : MonthlyPhysics.Rejection.values()) {
            if (counts[reason.ordinal()] != 0) result.add(reason.label + "=" + counts[reason.ordinal()]);
        }
        return result.length() == 0 ? "无（前序没有可行上库转移）" : result.toString();
    }
}
