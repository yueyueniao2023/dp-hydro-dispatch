# 水电调度 Java 教学：单库 → 双库 → 干支流梯级

## 新增：龙羊峡—拉西瓦12个月调度（实际提供数据）

入口为 [monthly.MonthlyMain](src/main/java/monthly/MonthlyMain.java)，以两站年发电量之和最大为目标。按用户确认，`input4` 为龙羊峡外部来水，`input5` 为两库之间的区间来水，**拉西瓦入库 = 龙羊峡优化总出库 + input5**。不使用 `zPlanLYX`。附带旧数据说明与用户确认口径的差异已记录在[详细教程](docs/longyangxia-laxiwa-monthly.md)。

原参数要求龙羊峡年末2600 m，但12月来水低于生态下泄，即使月初满库，月末也至多约2598.290707 m，故原参数无解。程序不会自动降低生态流量或放宽终点。

```bash
# 原参数：预期报告水量冲突并非零退出
mvn compile exec:java "-Dexec.mainClass=monthly.MonthlyMain"

# 用户确认的对照：仅将龙羊峡年末改为2595.2 m，其余沿用原数据/配置
mvn compile exec:java "-Dexec.mainClass=monthly.MonthlyMain" "-Dexec.args=data/longyangxia-laxiwa results/longyangxia-laxiwa/comparison data/longyangxia-laxiwa/comparison.properties"

mvn test
```

- [完整中文教程与数学模型](docs/longyangxia-laxiwa-monthly.md)
- [数据及字段说明](data/longyangxia-laxiwa/README.md)、[原始Excel](data/longyangxia-laxiwa/raw/)、[基础配置](data/longyangxia-laxiwa/dispatch.properties)、[对照配置](data/longyangxia-laxiwa/comparison.properties)
- [原参数诊断](results/longyangxia-laxiwa/original/summary.md)
- [对照结果及图表](results/longyangxia-laxiwa/comparison/summary.md)、[12个月详细CSV](results/longyangxia-laxiwa/comparison/dispatch.csv)、[中文简表](results/longyangxia-laxiwa/comparison/monthly-summary.csv)
- [本次验证与网格敏感性](docs/monthly-validation.md)

以下保留原有三个教学案例。

一个 Maven 项目中同时保留三个可独立运行的案例，学习“水量平衡 → 发电计算 → 联合状态 → 动态规划 → 回溯”。无需切换 Git 分支，也没有多模块结构。目标均为**整个调度期所有参与电站的总发电量最大**，不包含电价、风光储或电网约束。

双库、四库及新增低流量补点为**教学合成数据，非真实电站资料**。单库原始 Excel 原样保留，原数据来源未在本次核实，不应当作经校准的工程资料。

## 从三个案例开始

| 案例与水系 | 阶段和状态 | 优化目标与学习重点 | 入口 / 核心代码 | 数据 / 中文教程 / 结果 |
| --- | --- | --- | --- | --- |
| 单库 → 出口 | 36旬、365天；基本状态为水位；原有月内极差约束需扩展为(水位,本月最低旬末水位,本月最高旬末水位)，月末可合并 | 单站总电量；理解一条水量平衡、历史约束与状态充分性 | [Main](src/main/java/Main.java) / [DynamicProgramming](src/main/java/DynamicProgramming.java) | [原Excel](data/hydro_basic_data.xlsx)、[补点说明](data/single/README.md) / [单库教程](docs/single-tutorial.md) / [CSV](results/single/dispatch.csv) |
| 上库 → 下库 → 出口 | 36旬、365天；(上库水位,下库水位)，11×11=121状态 | 两站总电量；下库来水依赖上库决策，弃水也进入下库 | [CascadeMain](src/main/java/cascade/CascadeMain.java) / [CascadeDp](src/main/java/cascade/CascadeDp.java) | [数据](data/cascade/) / [双库教程](docs/cascade-tutorial.md) / [CSV](results/cascade/dispatch.csv) |
| 干流 A → B → C，支流 D → B | 6个日时段；(A,B,C,D水位)，3⁴=81状态 | 四站总电量；直接上游集合、汇流、拓扑排序、联合转移 | [NetworkMain](src/main/java/network/NetworkMain.java) / [NetworkDp](src/main/java/network/NetworkDp.java) | [数据](data/network/) / [四库教程](docs/network-tutorial.md) / [CSV](results/network/dispatch.csv) |

四库连接关系：

```text
干流：A ───→ B ───→ C ───→ 流域出口
             ↑
支流：D ─────┘
```

B入库 = A总出库 + D总出库 + B区间来水；C入库 = B总出库 + C区间来水。总出库包含发电流量和弃水。C处不再重复加A和D。

**推荐阅读顺序：单库 → 双库 → 四库。** 第一次看单库，可先理解忽略月内极差时的 `F[t][水位]`，再阅读扩展状态；双库、四库只设本期约束，因此联合水位本身就足够表示状态。

## 直接运行

安装 JDK 17+、Maven 3.x，在仓库根目录（含 `pom.xml`）运行。首次构建需下载依赖。PowerShell 和 Bash 都可复制以下命令：

```bash
mvn compile exec:java "-Dexec.mainClass=Main"
```

```bash
mvn compile exec:java "-Dexec.mainClass=cascade.CascadeMain"
```

```bash
mvn compile exec:java "-Dexec.mainClass=network.NetworkMain"
```

分别写入 `results/single/`、`results/cascade/`、`results/network/`；可任意顺序运行，不覆盖其他案例。单库同时生成辅助Excel，版本库保留便于GitHub查看的CSV。

```bash
mvn test
```

所有源码 UTF-8。IDEA 中打开根目录 `pom.xml`，选 JDK 17、加载 Maven，然后运行相应 Main 类；工作目录设为项目根目录。不要把 Git 的 `main` 分支和 Java 的 `Main` 入口混淆。

双库、四库支持指定输入目录与输出CSV，例如：

```bash
mvn compile exec:java "-Dexec.mainClass=network.NetworkMain" "-Dexec.args=data/network results/network/dispatch.csv"
```

Windows 控制台乱码时可在 PowerShell 当前窗口设置 `$env:MAVEN_OPTS='-Dfile.encoding=UTF-8'`；CSV始终为UTF-8。

## 本次验证与代表性结果

2026-10-02重新运行，三个入口均成功退出。完整证据和复现说明见[验证记录](docs/validation.md)。

| 案例 | 总发电量（MWh） | 严格初末水位（m） | 最大逐库水量残差（m³） |
| --- | ---: | --- | ---: |
| 单库 | 17,803,905.702958 | 830 → 840 | 2.384×10⁻⁷ |
| 双库 | 1,182,374.199（取3位小数） | (210,105) → (210,105) | 2.049×10⁻⁸ |
| 四库 | 8,697.303120 | (201,141,91,181) → 同一状态 | 9.313×10⁻¹⁰ |

三个案例的库容、来水、装机、时长不同，**总电量不能用于比较算法优劣**。精确DP表示对所选离散模型求最优，不是连续水位问题的精确最优。

测试包含物理边界、逐期水量平衡、全流域守恒、含弃水的汇流关系、严格初末状态、CSV汇总、无解提示，以及独立完整路径穷举。单库修复原因见[单库教程](docs/single-tutorial.md#本次修复了什么)。

## 代码组织

```text
src/main/java/
  Main.java, DynamicProgramming.java     单库入口与核心，保留原入口
  ExcelReader.java, SingleCsvWriter.java 原Excel读取与单库CSV
  cascade/                              双库入口、DP、数据和CSV
    HydroPhysics.java                   三例共用的单库单期物理计算
    Reservoir.java, LinearCurve.java     水库参数与插值
    StationOperation.java               单库单期结果及单位
  network/                              四库入口、DP、拓扑、数据和CSV
data/
  hydro_basic_data.xlsx                 原单库输入，保持兼容
  single/                               单库说明及显式合成补点
  cascade/                              双库合成数据
  network/                              四库合成数据及连接表
docs/                                   三篇中文教程及验证记录
results/single/, cascade/, network/      互不覆盖的代表性CSV
src/test/java/                          单库、双库、四库及共用物理测试
```

为减少迁移，公共物理类仍位于原 `cascade` 包，单库与四库直接导入；核心DP分别写在三个清楚可见的文件里，不包装成复杂优化框架。

## 简化边界和后续学习

采用确定性平均流量、分段线性库容曲线、固定效率/综合系数、平均水头；无传播时滞、河道损失或河道调蓄，无机组启停与电网功率平衡。四库尾水位固定，不表示上下库之间存在真实回水耦合。

根目录旧 `results/optimal_dispatch_result*.xlsx` 和 `figures/` 保留为历史成果，**不是修复后代码的结果**，旧年发电量和步长对比不再作为当前结论。可选旧绘图入口改为读取 `results/single/`，输出 `results/single/figures/`；若原参考水位不满足新口径的水量/流量约束，会明确拒绝，不再截断流量。

先做教程末尾的小实验，再考虑风、光、储、负荷和电网约束。许可证：[MIT](LICENSE)。
