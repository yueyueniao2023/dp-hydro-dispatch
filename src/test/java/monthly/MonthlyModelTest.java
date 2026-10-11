package monthly;

import cascade.LinearCurve;
import cascade.StationOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MonthlyModelTest {
    private static MonthlyStation station(String name, double min, double initial, double terminal,
                                          double turbineLimit, double guaranteedPower) {
        return new MonthlyStation(name, min, min + 2, initial, terminal, 1,
                200, turbineLimit, 1000, 8.5, guaranteedPower, 0,
                new LinearCurve(new double[]{min, min + 2}, new double[]{0, 0.02}),
                new LinearCurve(new double[]{0, 200}, new double[]{min == 100 ? 90 : 0, min == 100 ? 90 : 0}));
    }

    private static List<MonthlyInput> year(double ecologicalFlow) {
        int[] days = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        List<MonthlyInput> result = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            double q = month == 10 ? 80 : 20;
            result.add(new MonthlyInput(month, days[month - 1], q, -3, q - 3,
                    ecologicalFlow, 0, month < 10 ? 100 : 102, month < 10 ? 50 : 52));
        }
        return result;
    }

    private static MonthlyProblem problem() {
        return new MonthlyProblem(station("upper", 100, 100, 100, 100, 0),
                station("lower", 50, 50, 50, 35, 0), year(0), false);
    }

    @Test
    void twelveMonthDpMatchesIndependentExhaustivePathsWithNegativeNetInterval() {
        MonthlyProblem problem = problem();
        // 前9月上限强制水位为最低值，仅10月末、11月末各有3×3种联合状态，共81条完整轨迹。
        double expected = Double.NEGATIVE_INFINITY;
        int checked = 0;
        for (int a = 0; a <= 2; a++) {
            for (int b = 0; b <= 2; b++) {
                for (int c = 0; c <= 2; c++) {
                    for (int d = 0; d <= 2; d++) {
                        double[] upper = new double[13];
                        double[] lower = new double[13];
                        java.util.Arrays.fill(upper, 100);
                        java.util.Arrays.fill(lower, 50);
                        upper[10] += a;
                        upper[11] += b;
                        lower[10] += c;
                        lower[11] += d;
                        double energy = 0;
                        for (int month = 1; month <= 12; month++) {
                            MonthlyInput in = problem.periods().get(month - 1);
                            // 独立手算，不调用 MonthlyPhysics 或生产状态转移函数。
                            double ru = in.upstreamInflow()
                                    - (upper[month] - upper[month - 1]) * 1_000_000 / in.seconds();
                            double rd = ru - 3 - (lower[month] - lower[month - 1]) * 1_000_000 / in.seconds();
                            double hu = (upper[month - 1] + upper[month]) / 2 - 90;
                            double hd = (lower[month - 1] + lower[month]) / 2;
                            assertTrue(ru >= 0 && ru <= 200 && rd >= 0 && rd <= 200);
                            energy += 8.5 / 1000 * (Math.min(ru, 100) * hu + Math.min(rd, 35) * hd) * in.hours();
                        }
                        expected = Math.max(expected, energy);
                        checked++;
                    }
                }
            }
        }
        MonthlyResult result = new MonthlyDp(problem).solve();
        assertEquals(81, checked);
        assertEquals(expected, result.totalEnergyMwh(), 1e-8);
        assertEquals(12, result.periods().size());
        assertEquals(365, result.periods().stream().mapToDouble(p -> p.input().days()).sum(), 1e-12);
        assertEquals(result.totalEnergyMwh(), result.periods().stream().mapToDouble(MonthlyPeriodResult::energyMwh).sum(), 1e-8);
        for (MonthlyPeriodResult p : result.periods()) {
            assertEquals(p.upstream().release() - 3, p.downstream().inflow(), 1e-12);
            assertEquals(p.upstream().release(), p.upstream().turbineFlow() + p.upstream().spill(), 1e-12);
            assertEquals(p.downstream().release(), p.downstream().turbineFlow() + p.downstream().spill(), 1e-12);
            double systemChange = (p.upstream().endStorage() - p.upstream().startStorage()
                    + p.downstream().endStorage() - p.downstream().startStorage()) * 1e8;
            assertEquals(systemChange, (p.input().upstreamInflow() - 3 - p.downstream().release()) * p.input().seconds(), 1e-6);
            assertEquals(0, p.upstream().balanceResidualM3(), 1e-6);
            assertEquals(0, p.downstream().balanceResidualM3(), 1e-6);
        }
        assertEquals(100, result.periods().get(11).upstream().endLevel());
        assertEquals(50, result.periods().get(11).downstream().endLevel());
    }

    @Test
    void offGridInitialAndTerminalLevelsAreIncludedExactlyAndReachable() {
        MonthlyStation upper = station("upper", 100, 100.125, 101.375, 100, 0);
        assertArrayEquals(new double[]{100, 100.125, 101, 101.375, 102}, upper.levels());
        List<MonthlyInput> inputs = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            inputs.add(new MonthlyInput(month, 30, 20, 0, 20, 0, 0, 102, 52));
        }
        MonthlyResult result = new MonthlyDp(new MonthlyProblem(upper,
                station("lower", 50, 50.25, 51.25, 100, 0), inputs, false)).solve();
        assertEquals(100.125, result.periods().get(0).upstream().startLevel());
        assertEquals(101.375, result.periods().get(11).upstream().endLevel());
        assertEquals(50.25, result.periods().get(0).downstream().startLevel());
        assertEquals(51.25, result.periods().get(11).downstream().endLevel());
    }

    @Test
    void handCalculationSeparatesTotalReleaseTurbineFlowSpillAndEnergyUnits() {
        MonthlyStation upper = new MonthlyStation("sloping tailwater", 100, 102, 101, 101, .5,
                200, 20, 1, 8.5, 0, 1,
                new LinearCurve(new double[]{100, 102}, new double[]{0, .02}),
                new LinearCurve(new double[]{0, 200}, new double[]{90, 100}));
        StationOperation op = MonthlyPhysics.evaluate(upper, 101, 102, 50, 31, 40, 102, false);
        assertNotNull(op);
        double release = 50 - 1_000_000.0 / (31 * 86_400);
        double tailwater = 90 + .05 * release;
        double head = 101.5 - tailwater - 1;
        double turbine = Math.min(20, 1000 / (8.5 * head));
        assertEquals(release, op.release(), 1e-12);
        assertEquals(tailwater, op.tailwater(), 1e-12);
        assertEquals(head, op.head(), 1e-12);
        assertEquals(turbine, op.turbineFlow(), 1e-12);
        assertEquals(release - turbine, op.spill(), 1e-12);
        assertEquals(1, op.powerMw(), 1e-12);
        assertEquals(744, op.energyMwh(), 1e-9);
        assertEquals(0, op.balanceResidualM3(), 1e-6);
    }

    @Test
    void ecologyUsesTotalReleaseAndGuaranteedPowerIsExplicitlyOptional() {
        MonthlyStation upper = station("upper", 100, 100, 100, 10, 2);
        StationOperation optional = MonthlyPhysics.evaluate(upper, 100, 100, 50, 31, 40, 102, false);
        assertNotNull(optional);
        assertEquals(40, optional.spill());
        assertTrue(optional.turbineFlow() < 40);
        assertNull(MonthlyPhysics.evaluate(upper, 100, 100, 50, 31, 40, 102, true));
        assertNull(MonthlyPhysics.evaluate(upper, 100, 100, 39, 31, 40, 102, false));
        assertNull(MonthlyPhysics.evaluate(upper, 100, 100, -1, 31, 0, 102, false));
    }

    @Test
    void upperSpillReachesLowerReservoirBeforeApplyingNetIntervalLoss() {
        MonthlyProblem problem = new MonthlyProblem(station("upper", 100, 100, 100, 10, 0),
                station("lower", 50, 50, 50, 35, 0), year(0), false);
        MonthlyPeriodResult january = new MonthlyDp(problem).solve().periods().get(0);
        assertEquals(20, january.upstream().release(), 1e-12);
        assertEquals(10, january.upstream().turbineFlow(), 1e-12);
        assertEquals(10, january.upstream().spill(), 1e-12);
        assertEquals(17, january.downstream().inflow(), 1e-12);
        assertEquals(17, january.downstream().turbineFlow(), 1e-12);
    }

    @Test
    void limitedTailwaterCurveRejectsOnlyUncoveredTransitionsWithoutExtrapolating() {
        MonthlyStation upper = new MonthlyStation("coverage", 100, 102, 100, 100, 1,
                200, 100, 1000, 8.5, 0, 0,
                new LinearCurve(new double[]{100, 102}, new double[]{0, .02}),
                new LinearCurve(new double[]{10, 80}, new double[]{90, 91}));
        assertNotNull(MonthlyPhysics.evaluate(upper, 100, 100, 10, 31, 0, 102, false));
        assertNotNull(MonthlyPhysics.evaluate(upper, 100, 100, 80, 31, 0, 102, false));
        assertNull(MonthlyPhysics.evaluate(upper, 100, 100, 9, 31, 0, 102, false));
        assertNull(MonthlyPhysics.evaluate(upper, 100, 100, 81, 31, 0, 102, false));
        assertNull(MonthlyPhysics.evaluate(upper, 102, 101, 20, 31, 0, 101, false));
    }

    @Test
    void originalTerminalEcologyConflictHasQuantifiedGridIndependentDiagnostic() {
        MonthlyStation upper = station("龙羊峡测试", 100, 102, 102, 100, 0);
        List<MonthlyInput> inputs = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            inputs.add(new MonthlyInput(month, 31, 20, 0, 20, 21, 0, 102, 52));
        }
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> new MonthlyDp(
                new MonthlyProblem(upper, station("lower", 50, 50, 50, 100, 0), inputs, false)).solve());
        assertTrue(error.getMessage().contains("12 月原始边界存在水量硬冲突"), error.getMessage());
        assertTrue(error.getMessage().contains("2678400.000 m³"), error.getMessage());
        assertTrue(error.getMessage().contains("与网格步长无关"), error.getMessage());
    }

    @Test
    void acceptsNegativeNetIntervalButRequiresExactlyOneOrderedYear() {
        assertDoesNotThrow(MonthlyModelTest::problem);
        assertThrows(IllegalArgumentException.class, () -> new MonthlyProblem(problem().upper(), problem().lower(),
                year(0).subList(0, 11), false));
        List<MonthlyInput> swapped = new ArrayList<>(year(0));
        java.util.Collections.swap(swapped, 0, 1);
        assertThrows(IllegalArgumentException.class,
                () -> new MonthlyProblem(problem().upper(), problem().lower(), swapped, false));
    }
}
