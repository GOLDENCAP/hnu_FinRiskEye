package com.example.chapter07.teach.global_view_advanced;

import java.util.ArrayList;
import java.util.List;

public final class GlobalTouchAdvancedLessonRepository {

    private GlobalTouchAdvancedLessonRepository() {
    }

    public static List<LessonItem> buildLessons() {
        List<LessonItem> items = new ArrayList<>();

        items.add(new LessonItem(
                1,
                LessonItem.TYPE_LESSON,
                "教学 01：Activity 作为界面控制器",
                "学习目标：理解 Activity 负责界面组织，而不是直接承载所有业务逻辑。",
                "围绕 GlobalTouchAdvancedActivity 的整体结构，先建立控制层视角：onCreate 负责初始化页面，按钮点击负责触发动作，真正的记录逻辑交给 Service。重点掌握 Activity、View、Service 三者分工。",
                "可对比设计：LoginActivity 控制登录；UploadActivity 控制上传；TimerActivity 控制倒计时。观察它们都在做界面调度而不是替代底层实现。",
                "练习 01：写出一个 DownloadControllerActivity 的骨架",
                "要求：写一个类，至少包含 onCreate、initViews、setupListeners、startDownload、stopDownload 五个方法；并能用字符串描述各自职责。",
                "评判标准：最终能输出 5 行方法职责说明，每行都包含“方法名 + 职责”。"
        ));

        items.add(new LessonItem(
                2,
                LessonItem.TYPE_LESSON,
                "教学 02：成员变量与界面控件绑定",
                "学习目标：理解为什么 EditText、TextView、Button 通常定义为成员变量。",
                "这一类聚焦 private EditText etDbName、private TextView tvStatus 这类字段。重点学习：为什么控件既要在初始化时绑定，又要在后续按钮点击、状态切换时继续访问，因此需要提升为成员变量。",
                "可举一反三：把用户名输入框、密码输入框、提交按钮、结果文本都设计成成员变量，然后比较它们与局部变量在生命周期上的差异。",
                "练习 02：实现控件字段清单生成器",
                "要求：给定 4 个控件名，输出一段字段声明文本，例如 private Button btnStart;。",
                "评判标准：输出必须正好 4 行，每行包含访问修饰符、控件类型、变量名和分号。"
        ));

        items.add(new LessonItem(
                3,
                LessonItem.TYPE_LESSON,
                "教学 03：onCreate 生命周期入口",
                "学习目标：掌握 Activity 首次创建时的固定初始化顺序。",
                "本类讲解 super.onCreate、setContentView、initViews、setupListeners、注册回调、初始化 UI 这条主线。重点不是死记 API，而是学会把首次进入页面要做的动作按顺序组织起来。",
                "对比练习空间：列表页 onCreate、详情页 onCreate、表单页 onCreate，它们都遵循“父类初始化 -> 绑定布局 -> 绑定控件 -> 绑定事件 -> 初始化状态”。",
                "练习 03：排列初始化顺序",
                "要求：将 6 个步骤按正确顺序输出：调用父类、设置布局、查找控件、绑定点击、注册监听、刷新初始状态。",
                "评判标准：输出必须是 1 到 6 的编号列表，顺序完全正确才算通过。"
        ));

        items.add(new LessonItem(
                4,
                LessonItem.TYPE_LESSON,
                "教学 04：setContentView 与布局装配",
                "学习目标：理解 Java 代码如何与 XML 布局建立关联。",
                "这一类围绕 setContentView(R.layout.activity_global_touch_advanced) 展开，帮助你理解：XML 只是结构定义，真正把界面装进 Activity 的动作发生在 setContentView。",
                "可扩展对比：切换不同布局文件、夜间模式布局、平板布局、错误页布局，观察资源 id 和页面逻辑的对应关系。",
                "练习 04：模拟布局映射关系",
                "要求：写一个函数，输入页面名 main/detail/teach，输出对应布局文件名 activity_main、activity_detail、activity_teach。",
                "评判标准：3 个输入都要给出正确映射字符串，输出总数为 3。"
        ));

        items.add(new LessonItem(
                5,
                LessonItem.TYPE_LESSON,
                "教学 05：findViewById 的固定用法",
                "学习目标：掌握根据资源 id 获取控件引用的标准模式。",
                "本类聚焦 initViews 方法。你需要学会把 XML 里的 id 与 Java 字段一一对应，并理解 id 不匹配、类型不匹配会导致什么问题。",
                "对比空间：TextView、Button、EditText、ImageView 都可以采用同样的获取方式，只是字段类型不同。",
                "练习 05：控件绑定结果验证",
                "要求：输入 3 组“字段名-资源id-控件类型”，输出对应的 findViewById 赋值语句。",
                "评判标准：每组都输出 1 行完整 Java 语句，共 3 行，字段名和 id 必须完全匹配。"
        ));

        items.add(new LessonItem(
                6,
                LessonItem.TYPE_LESSON,
                "教学 06：初始状态文本设置",
                "学习目标：理解页面加载后为什么要先给用户一个默认状态。",
                "围绕 tvStatus.setText、tvCurrentDb.setText、tvClickInfo.setText 讲解“初始可见状态”设计。用户第一次进入页面时，应该明确知道当前系统没有运行、数据库未设置、下一步该做什么。",
                "举一反三：表单页的“请先输入”，网络页的“等待连接”，播放器的“尚未播放”都属于初始状态文案设计。",
                "练习 06：设计 3 条初始化提示",
                "要求：为“服务状态、数据状态、操作提示”各输出 1 条中文初始化文案。",
                "评判标准：输出必须共 3 行，并且能分别体现未启动、未设置、如何开始。"
        ));

        items.add(new LessonItem(
                7,
                LessonItem.TYPE_LESSON,
                "教学 07：按钮点击事件监听",
                "学习目标：掌握 setOnClickListener 的事件驱动写法。",
                "这一类讲解为什么 UI 编程是“等用户触发”而不是顺序执行。重点学习为启动、停止、权限、回放按钮分别设置监听器，并把不同业务入口收口到不同方法。",
                "对比设计：提交按钮触发表单校验、删除按钮触发确认框、刷新按钮触发加载，都遵循同一监听器模式。",
                "练习 07：实现三按钮分发逻辑",
                "要求：写一个函数，输入按钮类型 start/stop/replay，输出对应动作 startService/stopService/openReplay。",
                "评判标准：3 个输入必须分别输出 3 个不同动作字符串。"
        ));

        items.add(new LessonItem(
                8,
                LessonItem.TYPE_LESSON,
                "教学 08：Lambda 表达式简化监听器",
                "学习目标：看懂 v -> { ... } 这种 Java Lambda 写法。",
                "以 btnStart.setOnClickListener(v -> { ... }) 为中心，学习匿名内部类与 Lambda 的对应关系、适用条件，以及为什么单一抽象方法接口可以这样写。",
                "可对比几个相似函数：View.OnClickListener、Runnable、Comparator，都可以练习 Lambda 化。",
                "练习 08：匿名类改写为 Lambda",
                "要求：把一个只有 onClick 方法的监听器改写成 Lambda，并输出改写前后两段文本。",
                "评判标准：输出 2 段代码描述，后一段必须包含 -> 符号。"
        ));

        items.add(new LessonItem(
                9,
                LessonItem.TYPE_LESSON,
                "教学 09：前置条件校验的 guard clause",
                "学习目标：掌握先校验、失败立即 return 的编程思路。",
                "这一类围绕数据库名为空、名称非法时立即 return 的写法。它强调减少嵌套，让主流程更清楚。学会后，你能把权限校验、参数校验、登录校验统一写得更干净。",
                "可扩展对比：文件路径为空直接返回、token 失效直接返回、库存不足直接返回，都是 guard clause 的典型应用。",
                "练习 09：写一个用户名校验器",
                "要求：若用户名为空返回 EMPTY；长度小于 3 返回 SHORT；否则返回 OK。",
                "评判标准：给出 3 组测试输入时，必须能分别输出 EMPTY、SHORT、OK。"
        ));

        items.add(new LessonItem(
                10,
                LessonItem.TYPE_LESSON,
                "教学 10：TextUtils.isEmpty 的固定场景",
                "学习目标：掌握空字符串与 null 判断的 Android 常用 API。",
                "本类从 TextUtils.isEmpty(customDbName) 出发，说明它比手写 name == null || name.length() == 0 更统一，也更适合 Android 场景。",
                "对比空间：EditText 输入、Intent 参数、接口响应字段都经常需要做空值防御。",
                "练习 10：实现空值分类器",
                "要求：输入 null、空串、普通字符串时，分别输出 NULL_OR_EMPTY、NULL_OR_EMPTY、HAS_TEXT。",
                "评判标准：3 个测试样例全部命中正确类别。"
        ));

        items.add(new LessonItem(
                11,
                LessonItem.TYPE_LESSON,
                "教学 11：字符串 trim 与 endsWith + substring",
                "学习目标：理解输入清洗与格式统一。",
                "围绕 trim、endsWith(\".db\")、substring 展开。教学重点是：用户输入可以不规范，但程序内部应先标准化，再继续业务处理，避免出现 test.db.db 这样的重复后缀。",
                "举一反三：处理 .txt、.json、http:// 前缀、手机号空格，都属于格式标准化。",
                "练习 11：写数据库名标准化函数",
                "要求：输入 \" test.db \" 输出 test；输入 \"demo\" 输出 demo；输入 \"abc.db.db\" 只去掉最后一个 .db 输出 abc.db。",
                "评判标准：3 组示例输出必须完全匹配预期文本。"
        ));

        items.add(new LessonItem(
                12,
                LessonItem.TYPE_LESSON,
                "教学 12：正则 matches 校验命名合法性",
                "学习目标：掌握用正则限制输入格式。",
                "以 name.matches(\"^[a-zA-Z0-9_]+$\") 为中心，学习字符集合、开始结束边界、加号表示至少一个字符等基本概念，并理解为什么数据库名要限制特殊字符。",
                "可对比：校验用户名、文件名、邀请码、只含数字的订单号，都能用类似思路扩展。",
                "练习 12：实现文件名合法性判断",
                "要求：只允许字母、数字、下划线；输入 ok_name 返回 true，输入 bad-name 返回 false，输入 hello123 返回 true。",
                "评判标准：3 个测试用例输出应为 true、false、true。"
        ));

        items.add(new LessonItem(
                13,
                LessonItem.TYPE_LESSON,
                "教学 13：悬浮窗权限检查 API",
                "学习目标：认识 Settings.canDrawOverlays 的用途和调用时机。",
                "本类专门讲 Android 悬浮窗权限：为什么全局触摸拦截场景离不开它、为什么启动前和 onResume 都要检查它、为什么它属于系统级授权而不是普通运行时权限。",
                "对比学习：通知权限、定位权限、文件权限、悬浮窗权限的触发入口和判断 API 都不同。",
                "练习 13：权限状态文本函数",
                "要求：输入 true 输出 已授权；输入 false 输出 未授权，请前往设置。",
                "评判标准：两个布尔输入都要输出准确中文状态。"
        ));

        items.add(new LessonItem(
                14,
                LessonItem.TYPE_LESSON,
                "教学 14：AlertDialog 引导式交互",
                "学习目标：学会在失败条件下给用户下一步动作，而不是只报错。",
                "围绕 new AlertDialog.Builder(this) 这段代码，理解 setTitle、setMessage、setPositiveButton、setNegativeButton、show 的组合模式。重点不是会背 API，而是知道什么时候应该用对话框承载操作分支。",
                "举一反三：删除确认、退出确认、权限说明、版本更新提示都适合用对话框。",
                "练习 14：生成确认框文案",
                "要求：针对“删除文件”场景输出标题、说明、确认按钮、取消按钮 4 项文本。",
                "评判标准：输出必须是 4 行，且包含一个高风险动作说明。"
        ));

        items.add(new LessonItem(
                15,
                LessonItem.TYPE_LESSON,
                "教学 15：Intent 跳转到系统设置页",
                "学习目标：掌握使用 Intent 打开其他页面的固定用法。",
                "这一类既讲打开系统设置页的 Intent，也帮助你理解显式 Intent、动作常量、startActivity 的角色。后续你会发现跳转 Activity、系统页面、浏览器页面，本质都是发送合适的 Intent。",
                "可对比：打开 Wifi 设置、打开应用详情页、打开某个自定义 Activity。",
                "练习 15：根据场景返回动作常量名称",
                "要求：输入 overlay 输出 ACTION_MANAGE_OVERLAY_PERMISSION；输入 appDetail 输出 ACTION_APPLICATION_DETAILS_SETTINGS。",
                "评判标准：2 个输入返回 2 个正确常量字符串。"
        ));

        items.add(new LessonItem(
                16,
                LessonItem.TYPE_LESSON,
                "教学 16：通过 Intent extra 传递参数",
                "学习目标：理解 putExtra 用于页面或服务间传值。",
                "围绕 intent.putExtra(\"database_name\", customDbName + \".db\") 讲解“命令 + 参数”的通信模式。重点学习 key 要稳定、value 要约定格式、接收方要按同样 key 读取。",
                "对比空间：传 userId、orderId、mode、title 都是同一种用法，只是数据含义不同。",
                "练习 16：构造参数字典",
                "要求：写一个函数，输入数据库名 demo，输出键值对描述 database_name=demo.db。",
                "评判标准：输出必须包含完整 key、等号和追加后的 .db 值。"
        ));

        items.add(new LessonItem(
                17,
                LessonItem.TYPE_LESSON,
                "教学 17：前台服务启动命令封装",
                "学习目标：理解 startForegroundService 在 Android 服务场景中的作用。",
                "这一类聚焦 startService() 方法：构造 Intent、设置 ACTION_START、附带参数、调用 startForegroundService。它体现了一种命令式服务控制思路：界面不直接操作服务对象，而是发送一条明确指令。",
                "可对比：ACTION_PLAY、ACTION_PAUSE、ACTION_UPLOAD、ACTION_SYNC 都能用同样的命令风格扩展。",
                "练习 17：写启动命令说明器",
                "要求：输入 action=START、db=task，输出一句完整命令说明：启动服务并使用 task.db。",
                "评判标准：输出中必须同时包含动作名和规范化数据库名。"
        ));

        items.add(new LessonItem(
                18,
                LessonItem.TYPE_LESSON,
                "教学 18：停止服务的命令分发",
                "学习目标：理解停止动作也可以通过 action 常量统一入口。",
                "围绕 ACTION_STOP 展开，帮助你建立“一个服务、多个动作命令”的设计意识，而不是为每个动作都单独造一个服务入口。",
                "举一反三：音乐播放器服务的播放/暂停/停止，下载服务的开始/取消/重试，都是同类设计。",
                "练习 18：动作路由器",
                "要求：输入 START 输出 启动逻辑，输入 STOP 输出 停止逻辑，输入 UNKNOWN 输出 未知动作。",
                "评判标准：至少覆盖 3 个分支，每个输入都返回唯一结果。"
        ));

        items.add(new LessonItem(
                19,
                LessonItem.TYPE_LESSON,
                "教学 19：Toast 单例化避免叠加",
                "学习目标：理解为什么需要先 cancel 再 show。",
                "这类聚焦 showToast 方法。它不只是弹提示，而是在做体验治理：连续点击时不让多个 Toast 排队。你可以把它看作一个简单的“上一个提示替换掉”的策略封装。",
                "对比空间：Snackbar 替换、加载弹窗防重入、重复请求节流，也都是‘防叠加’思路。",
                "练习 19：提示队列简化器",
                "要求：输入上一条提示和当前提示，输出最终应显示的提示内容。规则：当前提示总是覆盖上一条。",
                "评判标准：给出 2 组输入时，最终输出都必须等于当前提示。"
        ));

        items.add(new LessonItem(
                20,
                LessonItem.TYPE_LESSON,
                "教学 20：提取 updateUI 统一管理状态",
                "学习目标：掌握把多个控件状态变化收口到一个方法的封装思路。",
                "围绕 updateUI(boolean isRunning) 讲解状态驱动界面：服务运行时禁用启动按钮和输入框，服务停止时恢复编辑。重点是统一维护，而不是到处 scattered 地 setEnabled。",
                "举一反三：登录中/未登录、加载中/完成、编辑中/只读，都可以抽成一个 updateUiState。",
                "练习 20：输出运行态 UI 配置",
                "要求：输入 true 时输出 start=false, stop=true, input=false；输入 false 时输出 start=true, stop=false, input=true。",
                "评判标准：两个输入都要严格输出对应布尔组合。"
        ));

        items.add(new LessonItem(
                21,
                LessonItem.TYPE_LESSON,
                "教学 21：回调接口解耦 Activity 与 Service",
                "学习目标：理解监听器模式如何把数据变化通知回界面。",
                "本类围绕 GlobalTouchAdvancedService.setRecordListener 和 RecordListener 接口。重点是：Activity 不轮询服务，而是注册监听；服务有新状态就回调 Activity。这样职责更清晰、耦合更低。",
                "可对比：下载进度监听、网络请求回调、播放器状态监听、本地数据库变更监听。",
                "练习 21：设计下载监听接口",
                "要求：定义 onStart、onProgress、onFinish 三个回调方法，并输出它们的职责说明。",
                "评判标准：输出必须有 3 行，每行一个方法名和一句对应职责。"
        ));

        items.add(new LessonItem(
                22,
                LessonItem.TYPE_LESSON,
                "教学 22：runOnUiThread 线程切换",
                "学习目标：掌握后台回调更新界面时必须切回主线程。",
                "这一类专门解释 runOnUiThread 的必要性：服务或异步任务可能不在主线程执行，但 TextView.setText 必须在 UI 线程。你要学会识别“谁在后台，谁能碰界面”。",
                "举一反三：网络请求结果刷新列表、定时器更新倒计时、下载线程更新进度条，都需要类似线程切换。",
                "练习 22：线程来源分类",
                "要求：对“按钮点击、网络回调、Service 回调、onCreate”四种来源判断是否需要切回 UI 线程。",
                "评判标准：输出 4 行，每行标注 NEED_SWITCH 或 DIRECT_UI。"
        ));

        items.add(new LessonItem(
                23,
                LessonItem.TYPE_LESSON,
                "教学 23：onResume 做状态同步",
                "学习目标：理解页面从后台回到前台时为什么要重新检查权限和服务状态。",
                "围绕 onResume 方法，学习 Activity 生命周期里的“恢复同步”职责。用户去了系统设置后再返回，页面通常不会重建，但界面状态可能已经变化，所以必须在 onResume 再检查一次。",
                "对比空间：重新拉取草稿、刷新登录态、刷新定位开关、刷新蓝牙状态，都是 onResume 常见职责。",
                "练习 23：状态同步清单输出",
                "要求：输出一个恢复页面时要检查的 3 项内容清单，例如 权限、服务、按钮。",
                "评判标准：输出必须正好 3 行，且每行都是一个可检查项。"
        ));

        items.add(new LessonItem(
                24,
                LessonItem.TYPE_LESSON,
                "教学 24：编程中的‘输入清洗 + 状态同步 + 命令分发’整体思路",
                "学习目标：从单个 API 上升到完整实现套路，理解这段代码背后的方法论。",
                "最终总结课不再盯单行代码，而是抽象出三步：先清洗输入，再验证前置条件，再分发命令并同步界面。学会这个套路后，你可以迁移到文件上传、表单提交、后台任务、播放器控制等大量场景。",
                "可举一反三：提交订单、保存配置、开始下载、开始录音，都能按同一主线拆解。",
                "练习 24：设计一个文件上传页面流程",
                "要求：用 6 步输出一个上传页面的完整流程，至少包含输入校验、权限判断、开始上传、状态更新、回调刷新、恢复同步。",
                "评判标准：输出必须是 6 步编号列表，且六类动作全部出现。"
        ));

        return items;
    }

    public static LessonItem findByOrder(int order) {
        for (LessonItem item : buildLessons()) {
            if (item.getOrder() == order) {
                return item;
            }
        }
        return null;
    }
}
