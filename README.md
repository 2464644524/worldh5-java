# 世界H5 十开控制台

《世界H5》原 APK（5 开 WebView 辅助壳）的电脑版重制：Java + Swing 控制台，通过
Playwright 驱动本机 Microsoft Edge，每个账号使用独立的持久化用户目录，实现
Cookie、LocalStorage、缓存互相隔离，支持最多 10 开。

## 运行环境

- Windows 10/11
- JDK 17 或更高版本（当前机器使用 JDK 25）
- 已安装 Microsoft Edge（Playwright 通过 `setChannel("msedge")` 调用系统 Edge）

## 启动方式

双击或在项目目录执行：

```bat
run.cmd
```

脚本会先编译再通过 `exec:java` 启动控制台。第一次运行会下载 Playwright 浏览器驱动，
需要联网，之后即可离线使用。

## 目录结构

```
world-desktop/
├── data/
│   ├── accounts.json            # 10 个账号的配置（自动生成）
│   └── profiles/
│       ├── account-01/          # 账号1独立 Edge Profile（Cookie/缓存隔离）
│       ├── account-02/
│       └── ... account-10/
├── src/main/resources/scripts/  # 从原 APK 原样抽取的辅助 JS
└── run.cmd
```

每个账号对应一个独立 Profile 目录，账号之间不共享登录态。删除某个 `account-XX`
目录相当于让该账号重新登录。

## 使用流程（首次）

1. 启动控制台，选择账号（01–10），填写备注、选择渠道（天宇 / 官版 / 小七）。
2. 点“打开当前账号”，会弹出独立 Edge 窗口。
3. 在窗口中手动登录并进入游戏。检测到游戏 Frame 且 `xself / Control / nato`
   就绪后，控制台日志出现“脚本环境已注入”，辅助脚本自动生效。
4. 进入游戏后可点“使用当前URL存号”，把游戏直链保存到配置，下次打开直接进游戏。
5. 单账号验证稳定后，再用“全部打开(10开)”批量启动（有确认弹窗，不会自动全开）。

## 登录流程追踪

新窗口从渠道打开到进入游戏期间，会在控制台输出 `[登录]` 阶段日志，并追加到
`data/登录流程.txt`。日志包含页面跳转、登录/验证码/选角阶段、按钮文本和画布归一化点击位置；
不会记录 URL 参数、输入框内容或密码。该日志用于复盘人工进游戏流程，并校准后续自动登录。
## 单号一键托管

“当前账号”区的“托管”按钮用于单号自动闭环：自动打开/等待登录、等待进入游戏和脚本注入、
先判断是否在城内再进城、随后开启自动任务并持续守护。运行中按钮会变为“停管·槽位”，
点击后停止自动/刷怪/跟随但保留浏览器窗口。当前阶段只做单号托管，不做任务完成判定和自动切号。
## 功能按钮（对应原 APK）

- 自动：`TestAutoGame.start()`
- 刷怪：`TestRefreshGame.start(4000)`
- 跟随：`TestLoopGame.start()`
- 停止脚本：依次 stop 上述三个
- 进城：`City.doEnterCity(xself.getId())`
- 微端奖励：`nato.Network.sendCmd(MsgHandler.createDrawMicroReward())`
- 自动清背包：勾选后该账号注入时额外加载 `auto_clear_bag.js`

## 关键实现说明

- Playwright 的所有阻塞调用都在单线程 `playwright-worker` 上串行执行，
  Swing 事件线程不被阻塞。
- 后台线程每秒轮询一次所有已打开账号，游戏 Frame 就绪后只注入一次脚本。
- 注入脚本使用间接 `eval`（`(0,eval)(...)`）执行，保证脚本里的
  `var TestAutoGame = ...` 等声明挂到全局 `window`，与 Android WebView
  的 `evaluateJavascript` 行为一致。
- 天宇、小七渠道通过 `addInitScript` 注入原 APK 的 iframe 跳转脚本，
  让外层渠道页自动跳到游戏 Frame。

## 注意事项与风险

- 官版 / 小七地址为 HTTP 明文渠道，已通过 `setIgnoreHTTPSErrors` 处理，
  请仅在可信网络环境使用。
- 10 开内存占用较高，建议单开、双开逐步验证后再开满，并留意机器内存。
- 辅助脚本会改变游戏行为，存在封号风险，请自行评估后使用。
- 控制台不会自动批量开号，必须手动确认“全部打开”。
## Excel 导号自动上号（最新）

1. 点右侧辅助栏“批量”里的“模板”，生成 `data/账号导入模板.xlsx`。
2. Excel 按 `账号 / 密码 / 备注` 填写；也支持常见表头：`账户 / 用户名 / account / username`、`password / pwd / 登录密码`。
3. 支持 `.xlsx / .xlsm / .xls / .csv`；CSV 自动兼容 UTF-8 和 GBK。
4. 点“导号”选择账号表，勾选要登录的账号；勾选顺序就是 01–10 槽位顺序，最多 10 个。
5. 点“登录选中账号”后，会自动重开对应 Edge、清理旧 Cookie/本地账号缓存、打开官服、填写账号密码、提交、选第一个正常角色进入游戏。
6. 图形验证码、密码错误、无角色等无法自动判断的情况会在日志提示，需要手动处理。

密码只保存在本次程序运行内存中，不写入 `data/accounts.json`。Excel 自动上号仅用于官服账号密码登录；测试前请关闭旧控制台和旧游戏窗口后重新双击 `run.cmd`。