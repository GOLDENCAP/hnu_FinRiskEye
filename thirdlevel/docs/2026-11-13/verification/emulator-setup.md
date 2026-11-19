# HNU FinRiskEye 第三级：Android 模拟器环境搭建与启动记录

- 任务：task-5（无真机条件下用 Android 模拟器完成第三级 E2E 联调证据）
- 执行：teammate `emulator-verifier`，2026-11-13 21:54 ~ 22:20（Asia/Shanghai）
- **结论：环境（SDK/系统镜像/AVD/WHPX）已全部就绪，但模拟器在本会话中无法完成 boot（qemu 进程被外部挂起），模拟器 E2E 本轮不可用。** 详见第 6、7 节。

---

## 1. 主机环境（采集于 `diagnostics/system-info.txt`）

| 项目 | 值 |
| --- | --- |
| OS | Microsoft Windows 11 家庭版 中文版 10.0.26200 Build 26200 |
| CPU | AMD Ryzen 7 7735H with Radeon Graphics，8C/16T |
| RAM | 15.24 GB（当时可用 2.38 GB） |
| C 盘 | 装完系统镜像后可用约 18.0 GB |
| Hypervisor | HypervisorPresent = True（Hyper-V 已开） |
| PowerShell | 5.1.26100.7705 |
| JDK | Temurin 21.0.13（`dev-env\jdk\jdk-21`） |
| adb | Android Debug Bridge 1.0.41 / Version 37.0.1-15733141 |
| emulator | 37.2.13.0（build_id 16740729），`emulator-check accel` → `WHPX(10.0.26200) is installed and usable.`（见 `diagnostics/accel-check-and-version.txt`） |
| 安全软件 | **火绒 Huorong HIPS（HipsDaemon Running）**、McAfee WebAdvisor、Windows Defender、360/深信服残留目录（见 `diagnostics/security-software.txt`） |

## 2. 沙箱约束与统一环境变量（所有 adb/emulator/sdkmanager 命令必须前置）

```powershell
$ws='C:\Users\79047\Documents\deepseek-harness\default-workspace\finriskeye'
$env:JAVA_HOME="$ws\dev-env\jdk\jdk-21"
$env:ANDROID_HOME="$ws\dev-env\android-sdk"; $env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_USER_HOME="$ws\build-home\android-user-home"
$env:ANDROID_AVD_HOME="$ws\emulator-home\avd"
$env:TEMP="$ws\build-home\tmp"; $env:TMP=$env:TEMP
$env:JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$ws\build-home\tmp -XX:-UsePerfData"
$env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\emulator;$env:Path"
```

坑：本环境中**不能直接用 PowerShell `& sdkmanager.bat`**（报 `Program 'sdkmanager.bat' failed to run: Valid values are between 0 and 65535, inclusive. Parameter name: codepage`），必须 `cmd /c "...sdkmanager.bat ..."` 方式调用（avdmanager 同理）。
另外 `avdmanager` 不接受 `--sdk_root` 作为全局参数（该版本会报 `Flag '--sdk_root=...' is not a valid global flag`），设置 `ANDROID_HOME` 后直接调用即可。

## 3. SDK 组件安装

SDK 位于 `finriskeye\dev-env\android-sdk`，原有 platform-tools/build-tools 36/platform 36/cmake/ndk/cmdline-tools 19.0。
`emulator` 组件此前已在（37.2.13，无需下载）。需补装系统镜像：

```cmd
sdkmanager.bat --sdk_root="<ANDROID_HOME>" "emulator" "system-images;android-36;google_apis;x86_64"
```

- **第 1 次（21:55:31–21:57:24）失败**：
  `Warning: An error occurred while preparing SDK package Google APIs Intel x86_64 Atom System Image: ...\android-sdk\.temp\PackageOperation01\unzip\x86_64\vendor.img.`，EXIT=1。
  日志：`evidence/emulator/sdkmanager-install.log`。
- **根因**：`system-images\android-36\google_apis\x86_64\.installer\.installData` 残留了上次中断安装的 `BasicInstaller` 状态，导致 vendor.img 解压阶段报错。
- **修复**：删除残留目标目录 `system-images\android-36\google_apis\x86_64`（仅写范围内 dev-env）后重试。
- **第 2 次（21:58:34 起）成功**，约 1.5 分钟完成下载+解压：
  `Info: "Install Google APIs Intel x86_64 Atom System Image API 36 (revision 7)" complete.`
  日志：`evidence/emulator/sdkmanager-install2.log`。
- 结果目录：`dev-env\android-sdk\system-images\android-36\google_apis\x86_64`（system.img 4.4 GB、vendor.img 103 MB 等）。

## 4. AVD 创建与配置

```cmd
avdmanager.bat create avd -n finriskeye_api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6 --force
```

- AVD 路径：`finriskeye\emulator-home\avd\finriskeye_api36.avd`（`finriskeye_api36.ini` 同级），符合"AVD 放 workspace"的约束。
- 对 `config.ini` 的调整（完整副本见 `diagnostics/avd-config.ini`、`diagnostics/hardware-qemu.ini`）：

```
hw.cpu.ncore=4          hw.ramSize=2G          vm.heapSize=256M
disk.dataPartition.size=4G                     hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect               showDeviceFrame=no
hw.keyboard=yes         hw.audioInput=no       hw.audioOutput=no
hw.lcd.width=1080       hw.lcd.height=2400     hw.lcd.density=420
image.sysdir.1=system-images\android-36\google_apis\x86_64\
```

启动封装脚本（已写入 `finriskeye\build-home\`）：
`start-emulator.cmd`（agent 后台 job 版）、`start-emulator-detached.cmd`（WMI 脱离 job 版）、
`start-emulator-tcg.cmd`（-accel off）、`start-emulator-windowed.cmd`。

## 5. 四次启动尝试（均失败于 boot 前）

统一参数基线：
```
emulator -avd finriskeye_api36 -no-window -no-audio -no-boot-anim \
  -gpu swiftshader_indirect -no-snapshot -accel auto -no-metrics \
  -port 5554 -memory 2048 -cores 4
```

| # | 时间 | 差异 | 结果 |
| --- | --- | --- | --- |
| 1 | 22:00:22 | 后台 job 启动，WHPX | 启动 4 秒后 AVD 文件停更；qemu 52/52 线程挂起，CPU 冻结；adb 一直 offline；日志 22:00:57 后停更 |
| 2 | 22:11:41 | WMI `Win32_Process.Create`（脱离 agent job 对象） | t+20s 起 51 线程 Suspended，80 秒 CPU delta=0；code=1 |
| 3 | 22:13:48 | `-accel off -gpu guest -feature -VirtioWifi`（纯 TCG） | t+10s 起 68/68 线程 Suspended，90 秒 CPU 恒 0.78125s；code=1 |
| 4 | 22:16:05 | 去掉 `-no-window`（窗口模式） | 无 qemu 子进程、无法创建窗口，code=-1073741819 (0xC0000005) |

`adb devices` 全部尝试期间只有 `emulator-5554 offline`（见 `diagnostics/adb-devices.txt`）；
`adb -s emulator-5554 shell getprop sys.boot_completed` 无法执行 → **从未 boot 过，无任何可截图/安装 APK 的窗口**。

## 6. 失败根因分析（详见 `diagnostics/emulator-process-thread-states.txt`）

**现象本质：qemu/system 进程组在 QEMU main loop 启动后约 10~20 秒被"外部挂起"（Windows 线程级 Suspend），不是启动慢、不是 crash。**

- 线程状态证据：`Get-Process` 显示 qemu 全部线程 `Wait, Suspended`（52/52、51/51、68/68），CPU 累计时间不再增长。
- 手动 `NtResumeProcess` 可短暂恢复（qemu 重启 netsim 后仍退出，code=-36863），说明确实是外部 Suspend 而非死锁。
- 排除 WHPX（accel-check OK 且 TCG 同样挂起）、GPU（swiftshader/guest 一致）、netsim（-VirtioWifi 无效）、后台 job 归属（WMI 脱管无效）。
- **主因假设：主机安全软件（火绒 HIPS 主动防御为主，McAfee/Defender 并存）拦截并挂起了虚拟化进程**，等待人工确认；本会话无管理员权限、无交互桌面、无授权弹窗，故进程无限期挂起。
  旁证：每次强杀都伴随 `crashpad_handler.exe` APPCRASH 事件（`diagnostics/windows-app-crash-events.txt`），而 qemu 自身无崩溃转储。

## 7. 当前就绪状态与复测步骤（需管理员/桌面会话）

已就绪：`emulator` 37.2.13、`system-images;android-36;google_apis;x86_64` rev7、AVD `finriskeye_api36`（4C/2GB/4G data/1080x2400）、WHPX 可用、adb 可用、启动脚本齐全。

白名单完成后复测（约 10 分钟）：
1. 火绒 HIPS + McAfee/Defender 放行：`emulator.exe`、`qemu-system-x86_64-headless.exe`、`netsimd.exe`、`crashpad_handler.exe`；或临时暂停 HIPS 实时防护。
2. 启动：`cmd /c finriskeye\build-home\start-emulator-detached.cmd`（日志 `evidence\emulator\emulator-console-detached.log`）。
3. 等待 boot（最长 15 分钟）：
   ```powershell
   adb wait-for-device; adb shell getprop sys.boot_completed   # 期望 1
   adb exec-out screencap -p > evidence\emulator\screenshots\boot.png
   ```
4. 按 `emulator-e2e-report.md` 第 4/5 节的阶段二/三步骤执行（装 APK → appops → 第三级 → MediaProjection → 演示画面风险/普通切换 → 截图+logcat+服务端日志）。

## 8. 证据文件索引（`finriskeye\evidence\emulator\`）

| 文件 | 说明 |
| --- | --- |
| `sdkmanager-install.log` | 系统镜像第 1 次安装失败日志（stale .installer → vendor.img 解压错误） |
| `sdkmanager-install2.log` | 系统镜像第 2 次安装成功日志（API36 rev7 complete） |
| `emulator-console.log` | 启动尝试 #1（WHPX，后台 job）console 日志，含 WHPX operational 与停在 netsim 的位置 |
| `emulator-console-detached.log` | 尝试 #2（WMI 脱管）console 日志 |
| `emulator-console-tcg.log` | 尝试 #3（-accel off TCG，-verbose）完整日志，含 "Starting QEMU main loop" 后停住 |
| `emulator-console-windowed.log` | 尝试 #4（窗口模式）日志 |
| `diagnostics/system-info.txt` | OS/CPU/RAM/磁盘/Hypervisor/PowerShell 信息 |
| `diagnostics/accel-check-and-version.txt` | emulator-check accel + emulator -version |
| `diagnostics/avd-config.txt` / `avd-config.ini` / `hardware-qemu.ini` | AVD 配置快照 |
| `diagnostics/adb-devices.txt` | adb devices / get-state 输出（offline） |
| `diagnostics/emulator-process-thread-states.txt` | 四次尝试的线程挂起/CPU 冻结证据与根因分析 |
| `diagnostics/windows-app-crash-events.txt` | Windows 应用日志 crashpad APPCRASH 摘录 |
| `diagnostics/security-software.txt` | 火绒/McAfee/Defender/360/深信服 清单（主因假设依据） |
| `diagnostics/v2-apk-static-verification.txt` | v1/v2 APK 的 aapt2 静态校验（包名/Activity/label/SHA256） |
| `diagnostics/server-health.txt` | 宿主机访问 127.0.0.1:3100 /health（v1.1.0） |
| `screenshots/README.md` | 无截图说明 + 复测命名规范 |
