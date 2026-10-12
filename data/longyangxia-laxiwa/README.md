# 龙羊峡—拉西瓦数据

raw目录保留用户提供文件原件，SHA256SUMS.txt用于核对字节一致性。后缀4为龙羊峡、5为拉西瓦。zPlanLYX按用户要求不参与本次调度，保留归档但程序不读取。

| 文件 | 内容与读取范围 | 用途 |
| --- | --- | --- |
| input4/input5.xlsx | 唯一表A1:A365，日均流量 | 按用户确认：input4为龙羊峡外部来水，input5为两库之间区间来水 |
| resourceData4/5.xlsx | A1:A15，B列字段名 | 电站参数 |
| ecoFlow4/5.xlsx | A1:A12 | 逐月总生态下泄下限 |
| z4/v4.xlsx | A1:A801，一一配对 | 龙羊峡水位库容曲线 |
| z5/v5.xlsx | A1:A561，一一配对 | 拉西瓦水位库容曲线 |
| q4/zTail4.xlsx | A1:A3，一一配对 | 龙羊峡总泄流—尾水位 |
| q5/zTail5.xlsx | A1:A4，一一配对 | 拉西瓦总泄流—尾水位 |
| 数据说明.txt、各水电站input.xlsx的意义.xlsx | 原说明 | 确认电站编号及入库流量含义 |

resourceData各行依次为：1正常蓄水位、2死水位、3初水位、4末水位、5最大发电流量、6出力系数、7保证出力、8最大出力、9最大下泄流量、10生态流量、11额定水头、12机组数量、13单机装机、14单机最大过流、15泵站效率。逐月生态采用ecoFlow文件，额定参数不直接替代动态计算水头，无抽水模型不使用泵站效率。

源表无单位/日期列，默认解释为平年1—12月、水位m、流量m³/s、库容亿m³、出力MW。单位是假设而非已核实元数据，见dispatch.properties。保证出力的单位及保证率尤其需要提供者确认，因此默认不设逐月硬保证。

`dispatch.properties` 保留resourceData原始初末边界、interval.mode=input5-as-interval。下库入流=上库优化总出库+input5月均流量；不使用zPlanLYX，不相减还原区间。原参数本身因12月水量冲突而无解，程序将诊断保存后非零退出。

原附带说明称input5为拉西瓦入库量，与用户本次明确解释不同。实现采用用户确认的区间来水口径，并保留原文差异；未来换数据时需要重新确认含义。

`comparison.properties` 是用户确认的显式对照覆盖：仅将龙羊峡年末改2595.2 m；区间模式仍为input5-as-interval，不采用零区间假设。

需要补真实区间资料时，将interval.mode改为monthly-net并设置interval.file（UTF-8 CSV表头month,interval_net_inflow_m3s与12行）；或daily-upstream-release（无表头单列365行实际总出库XLSX），由input5减去实际出库后聚合。相对路径按数据目录解析。

原曲线低位段异常原样保留：v4第369、520行造成下降；v5第42—46行平段。仅运行水位范围及相邻插值点参与计算，相关范围必须严格递增；未用异常列在summary。q5小于380 m³/s的尾水资料缺失，程序不外推。

运行命令、数学模型、参数解释和无解证明见[完整教程](../../docs/longyangxia-laxiwa-monthly.md)。
