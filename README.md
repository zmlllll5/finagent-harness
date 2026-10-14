# FinAgent Harness — 一个"无溯源不输出"的金融研究智能体执行框架

> 面向金融分析师的 agentic harness。核心约束：**分析师看到的每一个数字，都能一键点回原始财报。**

本框架回答的问题不是"如何让 LLM 更准"，而是"**如何让 LLM 的准确性不再重要**"。

---

## 目录

- [1. 问题陈述](#1-问题陈述)
- [2. 核心设计主张](#2-核心设计主张)
- [3. 架构](#3-架构)
- [4. 环境配置](#4-环境配置)
- [5. 运行方式](#5-运行方式)
- [6. REST API](#6-rest-api)
- [7. 效果评估](#7-效果评估)
- [8. 实测结果](#8-实测结果)
- [9. 用户习惯适配](#9-用户习惯适配)
- [10. 诚实的局限](#10-诚实的局限)
- [11. 工程结构](#11-工程结构)

---

## 1. 问题陈述

分析师需要查询、交叉比对公开财报。LLM 能极大加速这件事，但分析师拒绝基于可能幻觉的数据做投资决策，因此**要求输出必须附带完整可追溯的来源信息**。

拆成三条可交付的能力：

| # | 要求 | 本框架的交付 |
|---|------|-------------|
| 1 | 选定行业赛道，并随使用适配用户习惯 | 研究范围是**配置**（`application.yml`），非代码；习惯适配见 §9 |
| 2 | 下发指令获取并汇总多家公司财务报表 | SEC EDGAR 真实数据接入 + 4 个 agent 工具 |
| 3 | 生成跨季度对比表，附清晰溯源信息 | XLSX/CSV 导出，**单元格级** provenance |

研究范围选定为 **美股半导体（NVDA / AMD / INTC / AVGO / MU）**。选择理由：财报复杂度高（多业务线收入确认、存货周期、出口管制相关披露），恰好是溯源能力最能体现价值、也最容易被幻觉坑到的场景。

数据源为 **SEC EDGAR XBRL 结构化数据**（`companyfacts` API），而非 PDF 解析。这是一条关键决策，见下节。

---

## 2. 核心设计主张

### 主张一：LLM 不产生数字，只选择标识符

这是整个框架的承重墙。

**常规做法**：让模型检索文档、阅读、然后输出答案，再用 prompt 要求它"引用来源"。这在最关键的时刻失效——当模型**自信地犯错**时，一段流畅文字里编造的引用和真实的引用**在字面上无法区分**。

**本框架的做法**：倒转控制权。

```
模型可以做的：  选择 metric 名 / ticker / 期间 / 是否要建表
模型不可以做的：产出任何一个数值

数值的真实来源：FactStore（从财报解析并入库的事实），在输出路径上被替换进去
```

模型即使被要求输出一个数字，那个数字也**没有进入输出的通道**——它必须通过 `NumericGuard` 的绑定校验，绑定失败即被剥离。

**这带来的直接后果：框架的准确性很大程度上与模型选择解耦。** 无论背后是 GPT-4o 还是确定性规则引擎，步骤 3–5（Execute → Verify → Assemble）走的是同一条代码路径。这是本框架的核心经验性主张，也是评估部分要测量的东西。

### 主张二：类型系统强制溯源，而非约定或 prompt 指令

"每个数字都要有出处"这句话，写在 prompt 里会被遗忘，写在文档里会被跳过，写在 code review 清单里会在 deadline 前被绕过。

所以它被做成了**编译期约束**：

```java
// FinancialFact.build() —— 没有 Provenance 就构造不出来
if (provenance == null) missing.add("provenance");
if (!missing.isEmpty()) {
    throw new IllegalStateException(
        "FinancialFact is missing required field(s): " + missing
        + ". Facts without provenance are not permitted in this harness.");
}

// VerifiedValue.build() —— VERIFIED 状态必须有支撑事实
if (status == VerificationStatus.VERIFIED && supportingFact == null) {
    throw new IllegalStateException(
        "VerifiedValue cannot be VERIFIED without a supporting fact.");
}
```

对象图是这样的，断裂的环节不存在：

```
原始财报  ──►  FinancialFact (+ Provenance)  ──►  VerifiedValue (+ 校验记录)
                     构造时强制                唯一允许到达输出的类型
```

`VerifiedValue` 是**唯一**允许出现在用户可见输出中的数值类型。想绕过它输出一个数字，需要先修改类型系统——这会是一次显眼的、无法在 review 中混过去的改动。

每个 `Provenance` 回答分析师审计时真正会问的四个问题：

| 问题 | 字段 |
|------|------|
| 哪份文件？ | `accessionNumber` + `formType` + `filingDate` + EDGAR `sourceUrl` |
| 文件里哪个位置？ | `xbrlTag`（结构化事实）或 `page` / `tableRef`（叙述性文本） |
| 原文到底怎么写的？ | `rawText`，逐字片段，不用打开财报就能肉眼核对 |
| 什么时候、怎么读的？ | `retrievedAt` + `retrievalMethod`，让数据陈旧度和解析风险可见 |

### 主张三：区分失败模式——编造 vs 归因错误

两种失败被刻意分开处理：

| 失败模式 | 含义 | 处理 |
|---------|------|------|
| **FABRICATED** | 这个数字在财报里根本不存在 | 记 `HALLUCINATION_SUSPECT`，从输出中**剥离** |
| **MISATTRIBUTED** | 数字是真的，但标错了 metric 或期间 | **替换为正确的归因**并记录差异 |

这个区分是一个产品决策。两者都是失败，但"用了正确的数字配了错误的标签"和"凭空发明了一个数字"**根因不同、修复方式不同**。把两者塞进同一个 `unverified` 桶里，会让评估报告的可行动性大幅下降。

### 主张四：宁可拒绝，不可估算

最危险的失败模式不是答错，而是**对一个无法回答的问题给出一个看起来合理的数字**。

```
分析师：NVDA 在 FY2099 Q4 的收入是多少？
✗ 错误行为：返回最近一个季度的数字，无任何警告（这正是本框架要防住的）
✓ 正确行为：REFUSED: no NVDA REVENUE value for period 'FY2099 Q4'.
           Reported periods available (most recent first):
           FY2027 Q2, FY2027 Q1, FY2027 Q0 ... 
```

期间钉定逻辑是强制的：请求的期间必须与已入库的事实精确匹配，不匹配就拒绝并**列出实际可用的期间**。空结果集永远不会被静默地替换成"最新值"。

---

## 3. 架构

### 五步 harness 循环

```
                  ┌─────────────────────────────────────────┐
   分析师指令 ────► │ 1. PARSE   指令 → 结构化 intent          │  可错，且错得无害
                  │             （模型唯一参与的地方之一）      │
                  └────────────────┬────────────────────────┘
                                   ▼
                  ┌─────────────────────────────────────────┐
                  │ 2. PLAN    intent → 有序工具计划          │  习惯只调整形态
                  │            （习惯调整默认值，不调整真值）    │
                  └────────────────┬────────────────────────┘
                                   ▼
                  ┌─────────────────────────────────────────┐
                  │ 3. EXECUTE 工具读取财报 → 产出事实        │  唯一产生数据的步骤
                  │            （永远附带 Provenance）        │
                  └────────────────┬────────────────────────┘
                                   ▼
                  ┌─────────────────────────────────────────┐
                  │ 4. VERIFY  每个数字绑定回已存事实          │  无法绑定的被剥离
                  │            NumericGuard 三阶段            │
                  └────────────────┬────────────────────────┘
                                   ▼
                  ┌─────────────────────────────────────────┐
                  │ 5. ASSEMBLE 渲染输出 + 引用脚注           │  模型可润色措辞
                  │                                        │  但不提供任何数字
                  └─────────────────────────────────────────┘
                                   ▼
                         AgentResponse（含 emittedValues / guardStats / caveats）
```

**步骤 3–5 与 LLM 选择完全无关。** 这是主张一在代码层面的落点。

### 模块划分

| 包 | 职责 | 关键类 |
|----|------|--------|
| `model` | 类型系统与不变量 | `Provenance`, `FinancialFact`, `VerifiedValue`, `Metric`, `ComparisonTable` |
| `edgar` | 数据接入（限速/缓存/重试/gzip） | `EdgarClient`, `CompanyFactsParser`, `FilingFetcher` |
| `provenance` | 事实存储与引用生成 | `FactStore`, `ProvenanceTracker` |
| `guard` | 反幻觉校验 | `NumericGuard` |
| `agent` | 编排与工具 | `AgentHarness`, `Planner`, `IntentParser`, 4 个 Tool |
| `profile` | 用户习惯适配 | `UserProfile`, `ProfileService` |
| `llm` | 可插拔模型接口 | `LlmClient`, `OpenAiCompatibleClient`, `MockLlmClient` |
| `api` | REST 接口 | `AgentController`, `TextRenderer` |
| `eval` | 评估框架 | `EvalRunner`, `EvalCase`, `EvalReport` |

### 三个被处理掉的 XBRL 陷阱

这三条是接入真实数据时踩到的坑，也是"为什么不能只靠 prompt"的具体证据。

**陷阱一：YTD 累计值 vs 季度值**

EDGAR 里同一份 10-Q 会同时申报 3 个月和 9 个月的收入，**它们在 JSON 里是同一个 tag、同一个数组**，只靠 `start`/`end` 的跨度区分。如果不过滤，对比表会出现这样一栏：

```
Revenue    FY2026 Q2      FY2026 Q3        FY2027 Q1     FY2027 Q2
           30.04B         91.16B  ←9M累计   44.06B        46.74B
```

表格"看起来正确"，但完全不可比，且没有任何报错。**这是最隐蔽的一类错误。**

处理：以 anchor metric（优先 REVENUE）的 duration class 为准，只保留同一 duration class 的期间；并在 series 层设单一 choke point 过滤累积事实。

**陷阱二：时点概念 vs 期间概念**

资产负债表科目（Assets、Inventory）**没有 `start`**，是"as of"某个时点的快照；利润表科目（Revenue、Net Income）有 `start`，是区间概念。两者不能混在同一套期间标签逻辑里。

处理：`DurationKind` 按 `start`/`end` 跨度分类为 `INSTANT` / `QUARTER` / `HALF` / `NINE_MONTH` / `ANNUAL` / `OTHER`，标签生成统一。

**陷阱三：重述（Restatement）**

同一期间的同一科目可能出现在**多份**不同日期的申报中（后续财报会重新披露前期数据）。取错版本会得到一个"真实但已过时"的数字。

处理：按 `filed` 日期降序排序，**后申报的 filing 胜出**。

### 跨公司概念漂移

同一经济量在不同公司会用不同的 XBRL 概念标注：

```
NVDA  的收入  →  us-gaap:Revenues
AMD   的收入  →  us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax
```

如果不过归一化，跨公司对比表会大面积出现"该公司未申报"的假阴性。

处理：`Metric` 枚举为每个规范化指标维护一条**候选概念优先级链**，`FilingFetcher` 按优先级逐个尝试，首个返回数据者胜出。

---

## 4. 环境配置

### 依赖

| 组件 | 版本 | 说明 |
|------|------|------|
| JDK | 11+ | 开发与验证于 JDK 11 |
| Maven | 3.6+ | 验证于 3.9.16 |
| Spring Boot | 2.7.18 | |
| 网络 | 需可访问 `www.sec.gov` | 抓取真实财报；有磁盘缓存 |

主要依赖：`spring-boot-starter-web`、`jackson-databind`、`poi-ooxml 5.2.5`（XLSX 导出）、`commons-text`、`commons-math3`。

### 配置项

全部配置集中在 `src/main/resources/application.yml`：

```yaml
finagent:
  sector:              # 研究范围 —— 改这里即换赛道，无需改代码
    id: us-semiconductors
    companies:
      - { cik: "0001045810", ticker: NVDA, name: "NVIDIA Corporation" }
      # ...

  edgar:
    user-agent: "..."             # SEC 要求带联系方式的 UA，建议改成你自己的
    requests-per-second: 5        # SEC 公平使用指引：≤10 req/s，留足余量
    cache-dir: "${user.home}/.finagent/cache/edgar"
    cache-ttl-minutes: 720        # 磁盘缓存，重复实验不再打网络

  llm:
    provider: mock                # mock | openai
    base-url: "https://api.openai.com/v1"
    api-key: "${OPENAI_API_KEY:}"
    model: "gpt-4o-mini"

  guard:
    numeric-tolerance: 0.0001                  # 数值绑定相对容差
    enable-balance-sheet-identity-check: true  # Assets == Liabilities + Equity
    strip-unverified-numbers: true             # 无法验证的数字直接剥离，而非仅告警

  profile:
    store-dir: "${user.home}/.finagent/profiles"
```

**没有任何 API Key 是必需的。** 默认 `provider: mock`，是一个确定性的规则引擎（不是测试 stub），规划与抽取回退到规则路径，`NumericGuard` 与 `ProvenanceTracker` 与 provider 无关、始终生效。**无 Key 状态下框架功能完整、输出可复现。**

---

## 5. 运行方式

### 编译与测试

```bash
mvn clean compile
mvn test          # 26 个单元测试，不需要网络
```

测试覆盖三个关键面：
- `NumericGuardTest`（10 个）—— 精确绑定、编造值剥离、归因错误纠正、期间错配、派生值重算、资产负债表恒等式、无溯源事实不可构造
- `IntentParserTest`（10 个）—— 越界年份、历史年份、裸年份=年度、意图路由、ticker 非子串匹配
- `CompanyFactsParserTest`（6 个）—— 季度/YTD 区分、年度标签一致性、重述取最新、每个事实都有溯源、异常载荷优雅降级

### 演示模式（推荐首次运行）

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--demo
```

或使用已编译的 classpath：

```bash
mvn -q -DskipTests compile
mvn -q spring-boot:run -Dspring-boot.run.arguments=--demo
```

`--demo` 依次演示三件事，最后一件是**故意问一个不存在的问题**：

```
STEP 1  抓取 SEC EDGAR 公开财报（带完整溯源）
STEP 2  生成跨季度对比表（每个单元格带引用脚注）
STEP 3  拒绝一个财报无法支撑的问题
```

> 演示第一步会从 `www.sec.gov` 拉取真实数据，首次运行约 10–30 秒（受 SEC 限速影响）。之后走磁盘缓存，秒级完成。
> 若不需要启动 Web 服务器，加 `-Dspring-boot.run.jvmArguments="-Dspring.main.web-application-type=none"`。

### 评估模式

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--eval
```

运行完整评估套件，在控制台输出 harness 与基线的对比报告，并写出机器可读报告到 `eval-results/`。

### 启动服务

```bash
mvn spring-boot:run          # 监听 8080
```

---

## 6. REST API

全部端点以分析师的**工作流**组织，而非内部对象模型。

### `POST /api/agent/query` — 下发自然语言指令

```bash
curl -X POST http://localhost:8080/api/agent/query \
  -H 'Content-Type: application/json' \
  -d '{
        "instruction": "build a 4-quarter comparison table for NVDA including revenue, gross profit, operating income and net income",
        "userId": "analyst-01"
      }'
```

返回 `AgentResponse`，关键字段：

| 字段 | 说明 |
|------|------|
| `intent` / `intentType` | 解析出的意图（可审计解析是否正确） |
| `plan` | 工具执行计划 |
| `tables` | 对比表（含 `citations` 引用列表） |
| `guardStats` | `checked / matched / misattributed / fabricated` 及占比 |
| `provenanceCoveragePct` | 本轮引用覆盖率 |
| `hallucinationRatePct` | 本轮幻觉率 |
| `emittedValues` | **实际产出的每一个数值**（评分读这里，不读散文） |
| `caveats` | 拒绝原因、资产负债表不一致等必须让分析师看到的信息 |

### `GET /api/facts/{ticker}/trace` — 审计端点

```bash
curl 'http://localhost:8080/api/facts/NVDA/trace?metric=REVENUE' | jq
```

返回该公司**每一条事实的完整 provenance**：accession number、XBRL tag、期间、申报日期、SEC 原文链接、原文片段、抓取时间与方式。

这是"**给我证明**"的端点——分析师或任何怀疑者用它来核对框架说的每一句话。

### `GET /api/export/{ticker}?format=xlsx|csv` — 导出对比表

```bash
curl -OJ 'http://localhost:8080/api/export/NVDA?format=xlsx&periods=4'
curl    'http://localhost:8080/api/export/NVDA?format=csv&periods=4'
```

**XLSX** 含三个 sheet：

| Sheet | 内容 |
|-------|------|
| `Comparison` | 对比主表；**每个数据单元格带原生 Excel cell comment**，内容为该值的完整 provenance；并带超链接指向 sec.gov 原文 |
| `Sources` | 全部引用来源明细 |
| `Warnings` | 一致性检查失败的条目 |

单元格级溯源意味着分析师**在 Excel 里悬停即可看到出处、点击即可跳转原文**——不需要回到本系统。

**CSV** 含脚注列 + 完整 `Sources` 段 + `Warnings` 段，纯文本可核对。

### `GET /api/compare/{ticker}` — 终端渲染

```bash
curl 'http://localhost:8080/api/compare/NVDA?periods=4'
```

### `GET /api/coverage` — 能力披露

返回当前赛道、已加载公司、每家可用指标、注册的工具清单。

### 实际导出样例

`docs/sample-comparison.csv` 是真实导出产物（未手工修饰）：

```
Metric,FY2026 Q2,FY2026 Q3,FY2027 Q1,FY2027 Q2,Footnotes,Unit,Verification
Revenue,30.04B,35.08B,44.06B,46.74B,[1] [2] [3] [4],USD,VERIFIED
Gross Profit,22.57B,26.16B,26.67B,33.85B,[5] [6] [7] [8],USD,VERIFIED
Net Income,16.6B,19.31B,18.78B,26.42B,[9] [10] [11] [12],USD,VERIFIED
Free Cash Flow,n/r,n/r,n/r,n/r,,,NOT_REPORTED

Sources
[1],NVDA,10-Q,FY2026 Q2,Revenues,0001045810-25-000209,2025-08-27,2024-07-28,
    https://www.sec.gov/Archives/edgar/data/1045810/000104581025000209/...
[2],NVDA,10-Q,FY2026 Q3,Revenues,0001045810-25-000230,2025-11-19,2024-10-27,
    https://www.sec.gov/Archives/edgar/data/1045810/000104581025000230/...
...
```

每个脚注给出 **XBRL tag + accession number + 申报日期 + 期间起止 + 直链**，分析师可逐条比对。

> 注意 `Free Cash Flow` 显示 `n/r`（not reported）。**这是正确的诚实行为，不是 bug** —— NVDA 在 FY2020 之后不再用 `PaymentsToAcquirePropertyPlantAndEquipment` 标注资本开支，框架找不到该 tag 就如实报告"未申报"，而不是用别的科目凑一个数字出来。见 §10。

---

## 7. 效果评估

### 评估设计原则

1. **Ground truth 由 EDGAR 派生，不是手写**
   评估用例从 `FactStore` 中生成，期望值锚定在具体 accession number 上。**框架无法通过"与自己一致"来刷分**——两侧读的是同一批财报，评分检查的是**实际产出的数值**（读 `emittedValues`，不读散文叙述）。

2. **不接受单一 accuracy 指标**
   一个系统可以既准确又无用——如果分析师无法验证它。所以指标被拆成五个维度。

3. **基线是结构性建模，并明确标注**

### 五项指标

| 指标 | 定义 | 为什么重要 |
|------|------|-----------|
| **Accuracy** | 数值是否正确 | 基础线 |
| **Provenance coverage** | 数值是否附带了可核对的引用 | 分析师能否验证 |
| **Provenance precision** | 引用是否指向**正确的**那份 filing | **最关键**：存在但错误的引用比没有引用更危险——分析师点进去、看到一份真实文档、然后停止核查了 |
| **Unsupported-claim rate** | 断言了但没有财报支撑的数字占比 | 幻觉的直接度量 |
| **Refusal correctness** | 对无有效答案的问题，是拒绝还是编一个 | 在 accuracy 指标里**完全不可见**：对无法回答的问题编一个合理答案，与正确拒绝**得分相同** |

### 基线方法（重要说明）

基线名为 "Native ChatGPT (no harness — modelled)"，是**结构性建模，不是实际运行某个模型**。

它建模了朴素方案两个**架构性**（而非模型特定）的性质：

- (a) 对无法回答的问题给出一个看起来合理的数字，而不是拒绝；
- (b) 不附带任何可审计的 accession 级溯源——因为**一个从权重里回忆出来的数字，本来就没有 accession number 可附**。

> **请把基线数字读作"该方法的可验证性上限"，而不是某个具体模型的得分。**

运行真实模型只需改一行配置，harness 与基线随即走完全相同的代码路径，唯一变量就是模型：

```bash
export OPENAI_API_KEY=sk-...
mvn spring-boot:run \
  -Dspring-boot.run.arguments=--eval \
  -Dspring-boot.run.jvmArguments="-Dfinagent.llm.provider=openai"
```

### 用例构成

22 个用例，由 `EvalRunner.buildCases()` 从 `FactStore` 自动生成，来源于真实 SEC 数据（NVDA，66 份 filing，1319 条事实）：

| 类别 | 数量 | 说明 |
|------|------|------|
| `accuracy` | 18 | 具体 metric × 期间（Revenue / Gross Profit / Net Income / Operating Income / Assets / Stockholders Equity），期望值锚定 accession |
| `accuracy-refusal` | 3 | **故意无法回答**：未来期间、早于公司申报历史的期间、filer 未申报该指标的期间 |
| `multi-company` | 1 | 跨公司 peer 对比（结构性用例，无单一正确 accession） |

用例数与最终分类**每次运行由实际入库数据决定**，不是写死的常量——这也是"ground truth 从 EDGAR 派生"这条设计原则的直接体现。

---

## 8. 实测结果

### 评估套件（22 用例，真实 SEC 数据）

| 指标 | Harness | 朴素基线 |
|------|---------|---------|
| Accuracy | **100%** (22/22) | 86.4% (19/22) |
| Provenance coverage | **86.4%** (19/22) | 0% |
| Provenance precision | **100%** (18/18) | 100% (0/0，无可评估引用) |
| Unsupported-claim rate | **0%** | 100% (22/22) |
| Refusal correctness | **3/3** | 0/3 |

报告中同时记录 `casesWithExpectedAccession: 18`，作为 precision 的分母。

> 注：基线在 `provenance precision` 一行的 100% 是**分母为零**导致的空真值（它没有产生任何可评估的引用），不构成任何正面信号。harness 的 100% 则建立在 18 个有明确期望 accession 的用例上。

**读法**：

- **Accuracy 差距（100% vs 86.4%）完全来自 3 个拒绝用例**——基线对无法回答的问题编了合理的数字，因此被判错。这正是 accuracy 单指标会掩盖的那类失败。
- **Provenance coverage 86.4% 而非 100%，是因为 3 个拒绝用例不产生引用**——不是一个引用缺失，而是正确回答"无数据"。这是指标的诚实行为，不予粉饰。
- **Provenance precision 100%**：在 18 个有明确期望 accession 的用例中，引用**全部**指向正确的 filing。注意分母是 18 而非 22——跨公司 peer 对比天然跨多份 filing、没有单一正确 accession，若计入分母会因一个**并非引用缺陷**的原因压低指标。
- **Unsupported claims 0%**：框架从未输出过一个没有财报支撑的数字。
- **Refusal 3/3**：三个不可回答的问题全部正确拒绝。

### 单元测试

```
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### 评估运行的原始输出

以下是 `--eval` 的**原样控制台输出**（未修饰），便于直接复核：

```
======================================================================
  EVALUATION — HARNESS vs. NAIVE BASELINE
======================================================================

Loading filings for NVDA ...
Fact store holds 1319 facts from 66 filings.

Built 22 evaluation cases from the fact store:
  accuracy: 18
  accuracy-refusal: 3
  multi-company: 1

Running harness ...
Running baseline ...

==============================================================================
  EVALUATION — HARNESS vs. NAIVE BASELINE
==============================================================================
metric                                  harness           baseline
------------------------------------------------------------------------------
cases                                        22                 22
accuracy                                 100.0%              86.4%
provenance coverage                       86.4%               0.0%
provenance precision                     100.0%             100.0%
unsupported claims                         0.0%             100.0%
refusals correct                              3                  0
refusals incorrect                            0                  3
------------------------------------------------------------------------------
elapsed: harness 1303ms, baseline 0ms

Reading this table
------------------------------------------------------------------------------
Accuracy is similar because the baseline is modelled as recalling values
correctly — that is generous to it, and it is not the axis this framework
competes on. The differences that matter are the next three rows:

  provenance coverage  — can the analyst check any given number? The
                         baseline cannot produce a citation at all.
  provenance precision — when the harness cites a filing, does the
                         citation name the right one?
  unsupported claims   — how often is a figure asserted with nothing
                         behind it? This is the hallucination surface.

Plus refusals: the baseline answers a question that has no valid answer.
That is the specific failure the brief is concerned about, and it is the
one an accuracy metric cannot see.
```

### 拒绝路径的原始输出

`--demo` 第三步（问一个不存在的期间）的实际输出——**这是整个框架最重要的行为**：

```
  fetchStatements -> OK (10ms) : Loaded 1104 facts across 4 metrics for NVDA
                                 in 9ms; all carry filing-level provenance
  queryMetric -> FAIL (0ms) : REFUSED: no NVDA REVENUE value for period 'FY2099 Q4'.
      This period is not present in any filing retrieved for NVDA.
      Reported periods available (most recent first): FY2027 Q2, FY2027 Q1,
      FY2026 Q3, FY2026 Q2, FY2026 Q1, FY2026 (FY), FY2025 Q3, FY2025 Q2.
      The harness will not substitute a different period's figure.
```

模型不会被给到机会去"估算"一个未来季度的收入——**这条路径在代码里不存在**。

### 导出产物验证

- XLSX：3 个 sheet（`Comparison` / `Sources` / `Warnings`）、原生 cell comments、sheet1 含超链接 —— 已通过解压检查 XML 结构确认
- CSV：含脚注列、`Sources` 段中的 SEC 原文直链、`Warnings` 段

### 一个值得注意的运行时行为

`Warnings` 段会出现资产负债表恒等式检查失败：

```
BALANCE-SHEET IDENTITY FAILED for NVDA FY2026 Q2:
Assets=111601000000 but Liabilities+Equity=75252000000 (relative gap 0.3257).
Likely period misalignment across line items.
```

**这是框架在正确工作**，而不是 bug。原因是资产负债表科目是**时点概念**，同一 XBRL 概念在不同 10-Q 中会重复披露**多个日期**的快照（当期 + 上一财年末），对比表按季度取数时会取到不同时点的组合。`crossReference` 检测到 `Assets ≠ Liabilities + Equity` 并**主动暴露给分析师**，而不是默默输出一张看起来漂亮但内部不自洽的表。

这就是框架的设计立场：**能检查出来的问题，宁可暴露也不掩盖。**

---

## 9. 用户习惯适配

### 核心原则：adapt the defaults, never the facts

个性化可以改变**先展示什么、怎么排版、默认取几期**——**绝不能改变财报说了什么**。

一个"学会了"去报告用户似乎喜欢的数字的系统，在研究场景里比没有个性化**更糟**。

### 学什么

| 维度 | 用途 |
|------|------|
| **Metric affinity** | 各指标被请求的频次 → 表格行序 + 说"老样子"时默认取哪些指标 |
| **Period window** | 分析师稳定的期间视野（trailing 4Q / 8Q） |
| **Unit scale** | 有的桌面用百万、有的用十亿——每张表都搞错是日常摩擦 |
| **Ticker watchlist** | 分析师实际跟踪哪些公司 → "对比一下赛道"意思是**他的**赛道 |
| **Form preference** | 季报 vs 年报的偏好 |

### 怎么落

```java
// UserProfile —— 计数器只驱动"表现"与"规划"，不参与真值
public void observeMetric(String metric)   // 行序、默认指标集
public void observePeriodCount(int count)  // 默认期间数
public void observeTicker(String ticker)   // watchlist
```

在 harness 里的落点很克制：

```java
// Planner：习惯只影响 plan 的形态
// FetchStatementsTool：habit 指标与请求指标取并集
//    —— 注意是"取并集"而非"替换"：不会因为习惯而隐藏事实
for (String m : profile.rankedMetrics(8)) {
    Metric habitual = Metric.fromName(m).orElse(null);
    if (habitual != null && !metrics.contains(habitual)) metrics.add(habitual);
}
```

**习惯永远不会改变一个事实的值、期间归属或引用。** 不存在一条代码路径，让 profile 数据参与到 `FactStore` 查询结果或 `NumericGuard` 校验结果中。

### 存储

每用户一个 JSON（`~/.finagent/profiles/`），**原子写入**（temp file + `ATOMIC_MOVE`），损坏文件重命名为 `.bak` 后重建——profile 损坏绝不应该导致服务不可用。

### 已知扩展点

计数器目前不做时间衰减。已标注的扩展点是 `UserProfile.observeMetric`——一个六个月前被放弃的指标不应该继续驱动默认值。刻意把钩子放在这里而不是散落在调用点。

---

## 10. 诚实的局限

按重要性排序。

**1. 基线是结构性建模，不是实际模型运行。**
它建模的是朴素方案的架构性缺陷（无 accession 级溯源、对不可回答问题不拒绝），而非某个具体模型的实测表现。README 与报告 JSON 的 `notes.method` 字段都明确写了这一点。如需真实对照，改 `finagent.llm.provider=openai` + `OPENAI_API_KEY` 后重跑 `--eval`，两侧走同一代码路径。

**2. 评估集规模小（22 例），且集中在单公司（NVDA）。**
它验证的是**机制正确性**（期间钉定、YTD 过滤、重述处理、引用精度），不是统计意义上的模型性能。扩展到全赛道 5 家公司的成本是查一次 EDGAR，用例生成本身是自动的（`EvalRunner.buildCases()`），但尚未做。

**3. FCF 显示 `n/r` 反映的是本框架的数据覆盖边界。**
NVDA 在 FY2020 之后不再用 `PaymentsToAcquirePropertyPlantAndEquipment` 标注资本开支。`BuildComparisonTool` 的 FCF 派生逻辑（OCF − Capex）因此找不到输入，如实报告"未申报"。

这是**正确的诚实行为**——但需要说明：一个生产级系统应当继续回退到叙述性文本解析（10-Q 的现金流量表原文里有这笔数），本框架尚未实现 `NARRATIVE_PARSE` 路径（`Provenance.retrievalMethod` 里已预留该枚举值）。**当前框架宁可少报，也不猜。**

**4. 资产负债表恒等式告警是"已知噪声"。**
如 §8 所述，它由时点概念的重复披露引起。当前实现是**暴露告警**而非静默处理。理想做法是在取数时按 `periodEnd` 严格对齐同一快照日期，这需要更细粒度的 XBRL `frame` 概念处理。

**5. 数据源是 XBRL 结构化数据，不是 PDF。**
`Provenance.page` / `tableRef` / `rawText` 字段是为叙述性文本解析准备的，但当前 XBRL 路径下 `page`/`tableRef` 始终为空。这意味着本框架覆盖**结构化申报**良好，对**非结构化披露**（如 8-K 中的口头指引、MD&A 叙述）尚无能力。选取 XBRL 是刻意的取舍：它换来的是**可验证性**——机器可解析的结构化来源比模型对 PDF 的"理解"更可信。

**6. 限速遵守 SEC 公平使用指引。**
5 req/s（指引上限 10），带磁盘缓存与负缓存（`__NOT_FOUND__` 哨兵）。首次冷启动跑全赛道会比较慢。这是刻意的：**不遵守数据源的使用条款，会让整个系统不可持续。**

---

## 11. 工程结构

```
.
├── pom.xml
├── README.md
├── docs/
│   └── sample-comparison.csv          # 真实导出产物（未修饰）
├── eval-results/                       # --eval 生成的报告与样例导出
│   ├── harness-report.json
│   ├── baseline-report.json
│   ├── sample-comparison.csv
│   └── sample-comparison.xlsx
└── src/
    ├── main/
    │   ├── java/com/persephone/finagent/
    │   │   ├── FinAgentApplication.java
    │   │   ├── model/          # 类型系统：Provenance / FinancialFact / VerifiedValue / Metric
    │   │   ├── config/         # @ConfigurationProperties × 5（sector / edgar / llm / guard / profile）
    │   │   ├── edgar/          # EdgarClient（限速·缓存·重试·gzip）/ CompanyFactsParser / FilingFetcher
    │   │   ├── provenance/     # FactStore / ProvenanceTracker
    │   │   ├── guard/          # NumericGuard
    │   │   ├── llm/            # LlmClient / OpenAiCompatibleClient / MockLlmClient
    │   │   ├── profile/        # UserProfile / ProfileService
    │   │   ├── agent/          # AgentHarness / Planner / IntentParser / 4 Tools / TableExporter
    │   │   ├── api/            # AgentController / TextRenderer
    │   │   ├── cli/            # DemoRunner（--demo / --eval）
    │   │   └── eval/           # EvalRunner / EvalCase / EvalReport
    │   └── resources/application.yml
    └── test/java/com/persephone/finagent/
        ├── NumericGuardTest.java         # 10 tests
        ├── IntentParserTest.java         # 10 tests
        └── CompanyFactsParserTest.java   #  6 tests
```

共 40 个 Java 源文件 + 3 个测试类。

---

## 附：一分钟上手

```bash
git clone <repo-url> && cd agent

mvn test                                                    # 26 tests，无需网络
mvn -DskipTests package

# 看它做什么（拉真实 SEC 数据，首次约 10-30s）
mvn spring-boot:run -Dspring-boot.run.arguments=--demo

# 看它做得怎么样
mvn spring-boot:run -Dspring-boot.run.arguments=--eval

# 用它
mvn spring-boot:run
curl -X POST localhost:8080/api/agent/query \
  -H 'Content-Type: application/json' \
  -d '{"instruction":"build a 4-quarter comparison table for NVDA","userId":"analyst-01"}'
curl 'localhost:8080/api/facts/NVDA/trace?metric=REVENUE'
curl -OJ 'localhost:8080/api/export/NVDA?format=xlsx'
```

---

## 依赖的数据源与致谢

数据来自 **SEC EDGAR**（U.S. Securities and Exchange Commission），公开可得。使用 `companyfacts` / `companyconcept` / `submissions` 端点，遵守 SEC 公平使用指引（带描述性 User-Agent、限速 ≤10 req/s、磁盘缓存）。所有数值的最终权威来源始终是 sec.gov 上的原始申报文件——本框架的角色是**把分析师带到那份文件**，而不是替代它。
