# FinRiskEye 第三级 v2 Android Lint 结果汇总（最终交付版，task-8）

日期：2026-11-13 22:12–22:14 (Asia/Shanghai)
工程：`finriskeye\hnu_FinRiskEye`
命令（沙箱安全 env，同 `tools\build-debug-env.ps1`）：

```powershell
powershell -ExecutionPolicy Bypass -File finriskeye\tools\build-debug-env.ps1 `
  -ProjectPath finriskeye\hnu_FinRiskEye -Task lintDebug -GradleArgs "--no-daemon --stacktrace"
```

## 结果（最终）

| 项 | 值 |
| --- | --- |
| Gradle 结果 | **BUILD SUCCESSFUL in 1m 27s**（墙钟 1.49 min） |
| **error 数** | **0** ✅ |
| **warning 数** | **415** |
| 完整日志 | `finriskeye\evidence\build-v2\lint.log` |
| 文本报告 | `finriskeye\hnu_FinRiskEye\app\build\reports\lint-results-debug.txt`（159 KB） |
| HTML 报告 | `finriskeye\hnu_FinRiskEye\app\build\reports\lint-results-debug.html`（477 KB） |
| XML 报告 | `finriskeye\hnu_FinRiskEye\app\build\reports\lint-results-debug.xml`（505 KB） |

## 与修复前对比

| 轮次 | 结果 | 说明 |
| --- | --- | --- |
| 修复前（归档 `lint-attempt1-5errors.log` / `lint-summary-attempt1-5errors.md`） | **FAILED**：5 errors / 415 warnings | B 前两级遗留代码 4 个 error + `local.properties` PropertyEscape 1 个 |
| **修复后（本次，最终）** | **SUCCESS**：0 errors / 415 warnings | 见下 |

5 个 error 的处理（均未由本构建环节修改源码）：

1. `RemoteAudioRecorder.java:117/129` MissingPermission → android-hardening 加 `@SuppressLint("MissingPermission")`（编译期抑制，不改运行时权限逻辑）；
2. `getposition\BackgroundRecordActivity.java:190` GestureBackNavigation → 同样 `@SuppressLint` 抑制（保留原逻辑，迁移 OnBackPressedDispatcher 列入后续）；
3. `golbal_view_advanced\GlobalTouchAdvancedActivity.java:198` NewApi → 增加 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.O` 保护；
4. `local.properties:1` PropertyEscape → **Lead 已删除该文件**（AGP 通过 `ANDROID_HOME` 定位 SDK，构建正常）。

## warning 分布（415 个，全部为 warning，不影响构建/验收）

| 规则 | 数量 |
| --- | --- |
| HardcodedText | 226 |
| SetTextI18n | 68 |
| ButtonStyle | 39 |
| UseTomlInstead | 14 |
| Autofill | 11 |
| Overdraw | 8 |
| DefaultLocale | 6 |
| NewerVersionAvailable | 6 |
| ClickableViewAccessibility | 5 |
| NotifyDataSetChanged | 5 |
| SdCardPath | 4 |
| ObsoleteSdkInt | 4 |
| 其它长尾（TextFields/UnusedResources/GradleDependency 等） | ~19 |

（HardcodedText/SetTextI18n/ButtonStyle 等来自 B 前两级大量历史布局与源码，属历史欠账，不影响本次验收。）

## 第三级 v2 代码相关 warning（无 error）

| 文件:行 | 规则 | 内容 |
| --- | --- | --- |
| `thirdlevel\android\java\...\RiskOverlayController.java:47` | InflateParams | inflate 时传 null 作为 root |
| `thirdlevel\android\res\layout\activity_third_level_demo.xml:9` | Overdraw | 根布局背景重复绘制 |
| `thirdlevel\android\res\layout\activity_third_level_demo.xml:38,45` | ButtonStyle | 按钮未用 borderless 样式 |
| `thirdlevel\android\res\layout\activity_third_level.xml:39` | TextFields | 输入框未设 inputType |
| `thirdlevel\android\res\layout\activity_third_level.xml:30` | Autofill | 缺 autofillHints |
| `thirdlevel\android\java\...\ThirdLevelActivity.java:53,95,136` | SetTextI18n | setText 字符串拼接（建议 strings.xml 占位符） |

以上均为体验/国际化类 warning，功能与 12/8 验收不受影响；可后续统一清理。

## 结论

- **Lint 0 error 通过**，满足最终交付的 lint 门禁；v2 新增代码与修复后代码均无 error。
- `abortOnError=true` 下 `lintDebug` 任务成功，说明工程可通过 Android Lint 检查。
- 证据：`lint.log`（完整日志）、本文件、归档的修复前日志/汇总、`hnu_FinRiskEye\app\build\reports\lint-results-debug.{txt,html,xml}`。
