# 自动跳过 (AutoClicker) 🚀

![License](https://img.shields.io/badge/License-MIT-blue.svg)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-purple.svg)
![Compose](https://img.shields.io/badge/Jetpack-Compose-green.svg)

**自动跳过** 是一款基于 Android 无障碍服务 (Accessibility Service) 开发的高性能、现代化点击工具。它深度集成了类似 GKD 的选择器引擎，支持强大的 UI 节点匹配逻辑，旨在帮助用户自动化处理繁琐的界面点击操作（如自动跳过开屏广告、点击确认按钮等）。

---

## ✨ 核心特性

- **🚀 GKD 风格选择器引擎**: 支持高级属性匹配 (`vid`, `text`, `desc`, `bounds`, `clickable`) 以及复杂的层级关系匹配 (`>`, `+`, ` `)。
- **🪄 可视化规则构造器**: 内置“魔棒”工具，无需编写代码即可通过表单自动生成复杂的 GKD 匹配表达式。
- **📸 页面节点拾取**: 支持捕获当前页面快照，并直接通过可视化列表点选 UI 元素来快速生成点击规则。
- **📦 规则订阅系统**: 全面支持导入网络GKD格式订阅（JSON/JSON5 格式），支持全局规则组与应用专属规则。
- **🛡️ 极致性能与防死锁**: 核心引擎采用协程与 `ConcurrentHashMap` 重构，支持深达 200 层的 UI 树遍历，彻底杜绝 `ConcurrentModificationException` 和 `StackOverflowError`。
- **💎 现代审美 UI**: 采用 Jetpack Compose 构建，支持 Edge-to-Edge 全屏布局与 Haze 磨砂玻璃效果，视觉体验极佳。
- **🔋 智能能耗控制**:
    - **应用白名单**: 仅对用户勾选的应用生效，非目标应用在后台 0 CPU 占用。
    - **系统保护**: 自动识别并屏蔽桌面 (Launcher) 及系统 UI 交互，防止误触。
    - **点击限速**: 自动防回环机制，防止因规则配置不当导致的无限点击死循环。
- **🔄 自动后台唤回**: 当无障碍服务重连或被系统杀死重启后，App 可在后台自动重连并弹出静默提示，确保全天候待命。

---

## 🛠️ 技术架构

- **语言**: Kotlin 1.9+
- **UI**: Jetpack Compose (Material 3)
- **数据库**: Room Persistence Library
- **背景处理**: Kotlin Coroutines Flow
- **特效库**: [Haze](https://github.com/chrisbanes/haze) (实现动态背景高斯模糊)
- **选择器逻辑**: 自研 AST (抽象语法树) 解析及逻辑估值器，支持 `||` 和 `&&` 操作符。

---

## 📖 规则语法简介 (GKD-Lite)

本项目兼容大部分 GKD 语法节点定义：

| 属性 | 描述 | 示例 |
| :--- | :--- | :--- |
| `vid` | 控件的 Short ID | `[vid="button_skip"]` |
| `id` | 控件的完整 ID | `[id="com.app:id/close"]` |
| `text` | 匹配文本内容 | `[text*="跳过"]` (包含匹配) |
| `desc` | 匹配内容描述 | `[desc^="关闭"]` (前缀匹配) |
| `clickable` | 是否可点击 | `[clickable=true]` |
| `>` | 直接子节点 | `FrameLayout > TextView` |
| `+` | 相邻兄弟节点 | `Button + Image` |

---

## 🚀 快速上手

### 1. 编译运行
1. 使用 Android Studio 打开本项目。
2. 确保已安装 Android SDK 34。
3. 执行 `./gradlew assembleRelease` 生成安装包。

### 2. 授权权限
- **无障碍服务**: 必须开启，用于探测页面元素并执行点击模拟。
- **通知权限**: 用于在后台静默重连时弹出提示，并防止服务被系统锁定。

---

## 🤝 贡献与反馈

欢迎提交 Issue 或 Pull Request！

- **提交规则建议**: 如果你发现了某些 App 的跳过规则失效，建议先使用“保存页面快照”功能提取 `hierarchy_dump.txt` 提交给我们。
- **作者**: Hugehard
- **个人网站**: [https://www.idiots.cn/](https://www.idiots.cn/)

---

## ⚠️ 免责声明

本应用仅供测试与研究安卓无障碍技术之用，不内置任何规则。请勿用于非法用途。用户因使用本软件违反相关法律法规或服务协议而产生的后果由用户自行承担。

---

## 📄 开源协议

本项目采用 **MIT License**。详情请参阅 `LICENSE` 文件。
