package monthly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** 原参数默认保留；不同边界条件必须通过独立的覆盖配置显式选择。 */
public final class MonthlyMain {
    private MonthlyMain() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println("用法：monthly.MonthlyMain [数据目录] [结果目录] [覆盖配置文件]");
            System.out.println("默认数据：data/longyangxia-laxiwa；结果：results/longyangxia-laxiwa/original");
            System.out.println("原始参数存在末月水量冲突：程序会输出核算文件及 summary.md，再以非零状态退出。");
            System.out.println("对照场景须显式传入覆盖配置，并使用独立结果目录；详见 docs/longyangxia-laxiwa-monthly.md。");
            return;
        }
        if (args.length > 3 || (args.length > 0 && args[0].startsWith("--"))) {
            throw new IllegalArgumentException("参数不正确，使用 --help 查看用法");
        }
        Path input = Path.of(args.length > 0 ? args[0] : "data/longyangxia-laxiwa");
        Path output = Path.of(args.length > 1 ? args[1] : "results/longyangxia-laxiwa/original");
        Path override = args.length > 2 ? Path.of(args[2]) : null;
        MonthlyDataReader.LoadedData data = MonthlyDataReader.read(input, override);
        MonthlyProblem problem = data.problem();
        System.out.printf(Locale.ROOT, "龙羊峡—拉西瓦：12个月、365天，水位网格 %d × %d；年末水位 %.3f / %.3f m%n",
                problem.upper().levels().length, problem.lower().levels().length,
                problem.upper().terminalLevel(), problem.lower().terminalLevel());
        MonthlyResult result;
        try {
            result = new MonthlyDp(problem).solve();
        } catch (IllegalStateException ex) {
            MonthlyOutputWriter.write(output, data, null, ex.getMessage());
            System.err.println("无解诊断已保存：" + output.resolve("summary.md").toAbsolutePath());
            throw ex;
        }
        MonthlyOutputWriter.write(output, data, result, null);
        System.out.printf(Locale.ROOT, "当前显式场景梯级电量：%.6f MWh = %.6f 亿kWh%n",
                result.totalEnergyMwh(), result.totalEnergyMwh() / 1e5);
        System.out.println("结果与假设：" + output.resolve("summary.md").toAbsolutePath());
    }
}
