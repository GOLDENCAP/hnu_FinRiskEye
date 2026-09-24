package com.example.chapter07.teach.global_view_advanced;

import java.util.ArrayList;
import java.util.List;

public final class GlobalTouchAdvancedPracticeRepository {

    private GlobalTouchAdvancedPracticeRepository() {
    }

    public static List<PracticeItem> buildPractices() {
        List<PracticeItem> items = new ArrayList<>();

        items.add(new PracticeItem(1,
                "教学 01：Activity 作为界面控制器",
                "练习 01：写出一个 DownloadControllerActivity 的骨架",
                "请写出一个控制下载流程的 Activity 骨架说明，至少体现页面初始化、控件绑定、事件绑定、开始下载、停止下载 5 个职责。你可以写伪代码，也可以写职责清单。",
                "输入样例：页面名=DownloadControllerActivity",
                "预期输出：5 行内容，必须分别体现 onCreate、initViews、setupListeners、startDownload、stopDownload。",
                "提示：重点不是完整 Android 代码，而是把控制器职责拆出来。",
                "判题规则：答案中必须同时出现 onCreate、initViews、setupListeners、startDownload、stopDownload 五个关键词。",
                "参考答案：\n1. onCreate：负责首次创建页面并安排初始化顺序。\n2. initViews：负责查找并保存页面控件。\n3. setupListeners：负责给下载、暂停等按钮绑定事件。\n4. startDownload：负责发出开始下载命令。\n5. stopDownload：负责发出停止下载命令。",
                new String[]{"oncreate", "initviews", "setuplisteners", "startdownload", "stopdownload"}));

        items.add(new PracticeItem(2,
                "教学 02：成员变量与界面控件绑定",
                "练习 02：实现控件字段清单生成器",
                "给定 4 个控件名，请输出对应的字段声明文本。答案至少要出现 4 行字段，每行一个成员变量声明。",
                "输入样例：Button btnStart, Button btnStop, TextView tvStatus, EditText etDbName",
                "预期输出：4 行字段声明，例如 private Button btnStart;",
                "提示：每行都应带 private、类型、变量名和分号。",
                "判题规则：答案中至少需要覆盖 btnStart、btnStop、tvStatus、etDbName 四个变量名。",
                "参考答案：\nprivate Button btnStart;\nprivate Button btnStop;\nprivate TextView tvStatus;\nprivate EditText etDbName;",
                new String[]{"btnstart", "btnstop", "tvstatus", "etdbname"}));

        items.add(new PracticeItem(3,
                "教学 03：onCreate 生命周期入口",
                "练习 03：排列初始化顺序",
                "请按照首次进入 Activity 的正确顺序，输出 6 个初始化步骤。",
                "输入样例：调用父类、设置布局、查找控件、绑定点击、注册监听、刷新初始状态",
                "预期输出：1 到 6 的顺序列表。",
                "提示：先准备运行环境，再准备界面，再绑定交互，最后刷新状态。",
                "判题规则：答案中必须出现 super.onCreate、setContentView、findViewById、setOnClickListener、监听、初始状态 这些核心词。",
                "参考答案：\n1. super.onCreate\n2. setContentView\n3. findViewById\n4. setOnClickListener\n5. 注册监听器\n6. 刷新初始状态",
                new String[]{"super.oncreate", "setcontentview", "findviewbyid", "setonclicklistener", "监听", "初始状态"}));

        items.add(new PracticeItem(4,
                "教学 04：setContentView 与布局装配",
                "练习 04：模拟布局映射关系",
                "请根据页面名输出对应布局文件名，至少覆盖 main、detail、teach 三个输入。",
                "输入样例：main / detail / teach",
                "预期输出：activity_main / activity_detail / activity_teach",
                "提示：布局名一般遵循 activity_页面名 的命名规则。",
                "判题规则：答案必须同时出现 activity_main、activity_detail、activity_teach。",
                "参考答案：\nmain -> activity_main\ndetail -> activity_detail\nteach -> activity_teach",
                new String[]{"activity_main", "activity_detail", "activity_teach"}));

        items.add(new PracticeItem(5,
                "教学 05：findViewById 的固定用法",
                "练习 05：控件绑定结果验证",
                "请输出 3 行控件绑定语句，要求分别把字段和对应资源 id 绑定起来。",
                "输入样例：btnStart-btn_start-Button；tvStatus-tv_status-TextView；etDbName-et_db_name-EditText",
                "预期输出：3 行完整赋值语句。",
                "提示：格式类似 btnStart = findViewById(R.id.btn_start);",
                "判题规则：答案中必须出现 findViewById，并覆盖 btn_start、tv_status、et_db_name 三个 id。",
                "参考答案：\nbtnStart = findViewById(R.id.btn_start);\ntvStatus = findViewById(R.id.tv_status);\netDbName = findViewById(R.id.et_db_name);",
                new String[]{"findviewbyid", "btn_start", "tv_status", "et_db_name"}));

        items.add(new PracticeItem(6,
                "教学 06：初始状态文本设置",
                "练习 06：设计 3 条初始化提示",
                "请为服务状态、数据库状态、操作提示分别设计 1 条初始化文案。",
                "输入样例：服务状态 / 数据状态 / 操作提示",
                "预期输出：共 3 行文本。",
                "提示：至少要体现未启动、未设置、如何开始。",
                "判题规则：答案中必须同时出现 未启动、未设置、启动 这三个核心表达。",
                "参考答案：\n服务状态：服务未启动\n数据库状态：当前数据库未设置\n操作提示：请先输入名称再点击启动",
                new String[]{"未启动", "未设置", "启动"}));

        items.add(new PracticeItem(7,
                "教学 07：按钮点击事件监听",
                "练习 07：实现三按钮分发逻辑",
                "请根据 start、stop、replay 三种输入，写出对应动作说明。",
                "输入样例：start / stop / replay",
                "预期输出：startService / stopService / openReplay",
                "提示：每个按钮应该映射到一个清晰的业务动作。",
                "判题规则：答案中必须同时出现 startService、stopService、openReplay。",
                "参考答案：\nstart -> startService\nstop -> stopService\nreplay -> openReplay",
                new String[]{"startservice", "stopservice", "openreplay"}));

        items.add(new PracticeItem(8,
                "教学 08：Lambda 表达式简化监听器",
                "练习 08：匿名类改写为 Lambda",
                "请给出一个点击监听从匿名内部类改写为 Lambda 的示意。",
                "输入样例：View.OnClickListener",
                "预期输出：两段描述，其中后一段包含 -> 符号。",
                "提示：Lambda 重点是保留行为，不保留冗长样板。",
                "判题规则：答案中必须出现 setOnClickListener 和 ->。",
                "参考答案：\n改写前：button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { startService(); } });\n改写后：button.setOnClickListener(v -> startService());",
                new String[]{"setonclicklistener", "->"}));

        items.add(new PracticeItem(9,
                "教学 09：前置条件校验的 guard clause",
                "练习 09：写一个用户名校验器",
                "请写出校验规则：用户名为空输出 EMPTY；长度小于 3 输出 SHORT；否则输出 OK。",
                "输入样例：\"\" / \"ab\" / \"alex\"",
                "预期输出：EMPTY / SHORT / OK",
                "提示：适合用 if + return 直接返回。",
                "判题规则：答案中必须出现 EMPTY、SHORT、OK。",
                "参考答案：\nif (name == null || name.isEmpty()) return \"EMPTY\";\nif (name.length() < 3) return \"SHORT\";\nreturn \"OK\";",
                new String[]{"empty", "short", "ok"}));

        items.add(new PracticeItem(10,
                "教学 10：TextUtils.isEmpty 的固定场景",
                "练习 10：实现空值分类器",
                "请对 null、空串、普通字符串分别输出分类结果。",
                "输入样例：null / \"\" / \"hello\"",
                "预期输出：NULL_OR_EMPTY / NULL_OR_EMPTY / HAS_TEXT",
                "提示：两个空场景可以归并处理。",
                "判题规则：答案中必须出现 NULL_OR_EMPTY 和 HAS_TEXT。",
                "参考答案：\nif (TextUtils.isEmpty(value)) return \"NULL_OR_EMPTY\";\nreturn \"HAS_TEXT\";",
                new String[]{"null_or_empty", "has_text"}));

        items.add(new PracticeItem(11,
                "教学 11：字符串 trim 与 endsWith + substring",
                "练习 11：写数据库名标准化函数",
                "请把数据库名做标准化处理：去空格；如果以 .db 结尾只去掉最后一个后缀。",
                "输入样例：\" test.db \" / \"demo\" / \"abc.db.db\"",
                "预期输出：test / demo / abc.db",
                "提示：顺序是先 trim 再判断 endsWith。",
                "判题规则：答案中必须出现 trim、endsWith、substring。",
                "参考答案：\nString name = input.trim();\nif (name.endsWith(\".db\")) {\n    name = name.substring(0, name.length() - 3);\n}\nreturn name;",
                new String[]{"trim", "endswith", "substring"}));

        items.add(new PracticeItem(12,
                "教学 12：正则 matches 校验命名合法性",
                "练习 12：实现文件名合法性判断",
                "请写出只允许字母、数字、下划线的判断规则。",
                "输入样例：ok_name / bad-name / hello123",
                "预期输出：true / false / true",
                "提示：需要写出完整正则或等价表达。",
                "判题规则：答案中必须出现 matches 和 ^[a-zA-Z0-9_]+$。",
                "参考答案：\nreturn name.matches(\"^[a-zA-Z0-9_]+$\");",
                new String[]{"matches", "^[a-za-z0-9_]+$"}));

        items.add(new PracticeItem(13,
                "教学 13：悬浮窗权限检查 API",
                "练习 13：权限状态文本函数",
                "请根据布尔值输出悬浮窗权限状态文本。",
                "输入样例：true / false",
                "预期输出：已授权 / 未授权，请前往设置",
                "提示：true 和 false 需要给出两种不同文案。",
                "判题规则：答案中必须同时出现 已授权 和 未授权。",
                "参考答案：\nif (granted) return \"已授权\";\nreturn \"未授权，请前往设置\";",
                new String[]{"已授权", "未授权"}));

        items.add(new PracticeItem(14,
                "教学 14：AlertDialog 引导式交互",
                "练习 14：生成确认框文案",
                "请为删除文件场景写出标题、说明、确认按钮、取消按钮四项内容。",
                "输入样例：场景=删除文件",
                "预期输出：4 行文本。",
                "提示：确认按钮要体现风险动作。",
                "判题规则：答案中至少要包含 删除、取消、确认 三类含义。",
                "参考答案：\n标题：确认删除文件\n说明：删除后无法恢复，请谨慎操作\n确认按钮：立即删除\n取消按钮：取消",
                new String[]{"删除", "取消", "确认"}));

        items.add(new PracticeItem(15,
                "教学 15：Intent 跳转到系统设置页",
                "练习 15：根据场景返回动作常量名称",
                "请根据场景名称输出正确的系统设置动作常量。",
                "输入样例：overlay / appDetail",
                "预期输出：ACTION_MANAGE_OVERLAY_PERMISSION / ACTION_APPLICATION_DETAILS_SETTINGS",
                "提示：关键是动作常量名准确。",
                "判题规则：答案中必须同时出现 ACTION_MANAGE_OVERLAY_PERMISSION 和 ACTION_APPLICATION_DETAILS_SETTINGS。",
                "参考答案：\noverlay -> ACTION_MANAGE_OVERLAY_PERMISSION\nappDetail -> ACTION_APPLICATION_DETAILS_SETTINGS",
                new String[]{"action_manage_overlay_permission", "action_application_details_settings"}));

        items.add(new PracticeItem(16,
                "教学 16：通过 Intent extra 传递参数",
                "练习 16：构造参数字典",
                "请输出数据库名 demo 对应的键值对传参结果。",
                "输入样例：demo",
                "预期输出：database_name=demo.db",
                "提示：key 固定，value 需要拼接 .db。",
                "判题规则：答案中必须出现 database_name 和 demo.db。",
                "参考答案：\nintent.putExtra(\"database_name\", \"demo.db\");\n// 或写成 database_name=demo.db",
                new String[]{"database_name", "demo.db"}));

        items.add(new PracticeItem(17,
                "教学 17：前台服务启动命令封装",
                "练习 17：写启动命令说明器",
                "请根据 action=START 和 db=task 输出一句完整命令说明。",
                "输入样例：START, task",
                "预期输出：启动服务并使用 task.db",
                "提示：动作和数据库名都要出现在结果中。",
                "判题规则：答案中必须同时出现 START 或 启动，以及 task.db。",
                "参考答案：\n收到 START 命令后，启动服务并使用 task.db 保存数据。",
                new String[]{"start", "task.db"}));

        items.add(new PracticeItem(18,
                "教学 18：停止服务的命令分发",
                "练习 18：动作路由器",
                "请根据 START、STOP、UNKNOWN 三个输入给出不同返回值。",
                "输入样例：START / STOP / UNKNOWN",
                "预期输出：启动逻辑 / 停止逻辑 / 未知动作",
                "提示：至少体现三个分支。",
                "判题规则：答案中必须同时出现 启动逻辑、停止逻辑、未知动作。",
                "参考答案：\nif (\"START\".equals(action)) return \"启动逻辑\";\nif (\"STOP\".equals(action)) return \"停止逻辑\";\nreturn \"未知动作\";",
                new String[]{"启动逻辑", "停止逻辑", "未知动作"}));

        items.add(new PracticeItem(19,
                "教学 19：Toast 单例化避免叠加",
                "练习 19：提示队列简化器",
                "请根据“当前提示总覆盖上一条”的规则输出最终结果。",
                "输入样例：上一条=保存成功；当前=网络异常",
                "预期输出：网络异常",
                "提示：最终结果始终等于当前提示。",
                "判题规则：答案中必须表达“当前覆盖上一条”或直接给出当前提示。",
                "参考答案：\n最终显示：网络异常\n原因：当前提示会覆盖上一条提示。",
                new String[]{"覆盖", "当前", "网络异常"}));

        items.add(new PracticeItem(20,
                "教学 20：提取 updateUI 统一管理状态",
                "练习 20：输出运行态 UI 配置",
                "请分别给出运行中与未运行时的按钮和输入框状态。",
                "输入样例：true / false",
                "预期输出：start=false, stop=true, input=false；start=true, stop=false, input=true",
                "提示：运行中应禁用 start 和 input。",
                "判题规则：答案中必须同时出现 start=false 和 start=true 两组配置。",
                "参考答案：\ntrue -> start=false, stop=true, input=false\nfalse -> start=true, stop=false, input=true",
                new String[]{"start=false", "stop=true", "input=false", "start=true", "stop=false", "input=true"}));

        items.add(new PracticeItem(21,
                "教学 21：回调接口解耦 Activity 与 Service",
                "练习 21：设计下载监听接口",
                "请写出一个下载监听接口的 3 个方法职责。",
                "输入样例：下载开始 / 进度更新 / 下载完成",
                "预期输出：3 行说明，至少包含 onStart、onProgress、onFinish。",
                "提示：方法名要能体现生命周期。",
                "判题规则：答案中必须出现 onStart、onProgress、onFinish。",
                "参考答案：\nonStart：通知下载开始\nonProgress：通知当前进度\nonFinish：通知下载完成",
                new String[]{"onstart", "onprogress", "onfinish"}));

        items.add(new PracticeItem(22,
                "教学 22：runOnUiThread 线程切换",
                "练习 22：线程来源分类",
                "请对按钮点击、网络回调、Service 回调、onCreate 这四种来源判断是否需要切回 UI 线程。",
                "输入样例：按钮点击 / 网络回调 / Service 回调 / onCreate",
                "预期输出：4 行分类结果，包含 NEED_SWITCH 或 DIRECT_UI。",
                "提示：常见经验是异步来源更可能需要切线程。",
                "判题规则：答案中必须同时出现 NEED_SWITCH 和 DIRECT_UI。",
                "参考答案：\n按钮点击 -> DIRECT_UI\n网络回调 -> NEED_SWITCH\nService 回调 -> NEED_SWITCH\nonCreate -> DIRECT_UI",
                new String[]{"need_switch", "direct_ui"}));

        items.add(new PracticeItem(23,
                "教学 23：onResume 做状态同步",
                "练习 23：状态同步清单输出",
                "请列出页面恢复到前台时需要重新检查的 3 项内容。",
                "输入样例：用户从设置页返回",
                "预期输出：3 行清单。",
                "提示：优先想权限、服务状态、界面按钮联动。",
                "判题规则：答案中必须覆盖 权限、服务、按钮 或等价表达。",
                "参考答案：\n1. 检查悬浮窗权限\n2. 检查服务是否仍在运行\n3. 同步按钮与输入框状态",
                new String[]{"权限", "服务", "按钮"}));

        items.add(new PracticeItem(24,
                "教学 24：编程中的‘输入清洗 + 状态同步 + 命令分发’整体思路",
                "练习 24：设计一个文件上传页面流程",
                "请输出一个 6 步的上传页面流程，至少包含输入校验、权限判断、开始上传、状态更新、回调刷新、恢复同步。",
                "输入样例：上传头像页面",
                "预期输出：6 步编号流程。",
                "提示：这是总结题，要把多个知识点串起来。",
                "判题规则：答案中必须覆盖 输入校验、权限、上传、状态、回调、同步 六类动作。",
                "参考答案：\n1. 清洗并校验文件路径\n2. 判断存储或相机权限\n3. 点击按钮后开始上传\n4. 更新页面为上传中状态\n5. 上传回调中刷新进度和结果\n6. 页面恢复时重新同步上传状态",
                new String[]{"输入校验", "权限", "上传", "状态", "回调", "同步"}));

        return items;
    }

    public static PracticeItem findByOrder(int order) {
        for (PracticeItem item : buildPractices()) {
            if (item.getOrder() == order) {
                return item;
            }
        }
        return null;
    }
}
