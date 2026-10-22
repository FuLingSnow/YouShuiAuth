# YouShuiAuth

<div align="center">

基于 [Velocity](https://docs.papermc.io/velocity/) 的 Minecraft 代理端认证插件

支持 QQ 绑定注册、QQ 快捷登录、设备免密自动登录、两步验证与多数据库存储

</div>

<div align="center">

![Velocity](https://img.shields.io/badge/Velocity-4.x-blue)
![Version](https://img.shields.io/badge/version-v1.1.1-blue)
![License](https://img.shields.io/badge/license-AGPL--3.0--only-red)
[![Build and release](https://github.com/FuLingSnow/youshuiauth/actions/workflows/build-and-release.yml/badge.svg)](https://github.com/FuLingSnow/youshuiauth/actions/workflows/build-and-release.yml)

</div>

## 目录

- [简介](#简介)
- [功能特性](#功能特性)
- [环境要求](#环境要求)
- [安装](#安装)
- [使用](#使用)
- [配置](#配置)
- [目录结构](#目录结构)
- [注意事项](#注意事项)
- [开源许可](#开源许可)

## 简介

部署在 Velocity 代理端的统一认证方案：离线玩家通过 QQ 绑定即可完成注册与登录，已绑定玩家使用 6 位登录码或设备免密快速进入服务器；内置防爆破、限频、TOTP 两步验证与审计日志，支持 PostgreSQL / MySQL / SQLite / H2 多种数据库。

## 功能特性

- **QQ 绑定注册**：游戏内 `/qqbind` 生成 8 位绑定码，QQ 群内 `/绑定 <码>` 自动建号并登录，全程无需输入密码
- **QQ 快捷登录**：进入登录大厅后点击聊天按钮或执行 `/login` 获取 6 位登录码，QQ 群内 `/登录 <码>` 直接转服
- **设备免密自动登录**：记录客户端指纹（brand / 协议版本 / 聊天设置 / 连接地址 / IP 网段），有效期与网段匹配时自动跳过登录大厅
- **自动登录群提醒**：自动登录成功后在指定 QQ 群 `@对应QQ` 通知，异常登录及时发现
- **一键复制**：绑定码 / 登录码附带聊天栏按钮，点击即复制完整命令
- **两步验证（TOTP）**：为账号启用动态码二次校验，启用后不支持免密登录
- **防爆破与限频**：按 IP / 昵称计数锁定，QQ 操作按 QQ 号限频
- **正版兼容**：支持离线 / 正版混合模式与 Floodgate 基岩直通
- **多数据库**：PostgreSQL / MySQL / SQLite / H2，含自动建表与版本迁移
- **管理命令**：统计信息、QQ 绑定列表、解绑、重置与报告生成

## 环境要求

- Velocity 3.x / 4.x
- Java 21+
- 数据库：PostgreSQL / MySQL / SQLite / H2 任一
- QQ 功能（可选）：[AstrBot](https://github.com/AstrBotDevs/AstrBot) v4.x + QQ 协议端（如 NapCat），并安装 [astrbot_plugin_fls_youshuiauth](https://github.com/FuLingSnow/astrbot_plugin_fls_youshuiauth)

## 安装

### 最新版永久下载

```
https://github.com/FuLingSnow/youshuiauth/releases/latest/download/YouShuiAuth-latest.jar
```

### 方式一：手动安装

将 `YouShuiAuth-1.1.1.jar` 放入 Velocity 的 `plugins/` 目录，重启代理即可。首次启动自动生成 `config.yml` 与多语言文件。

下载后可校验发布包完整性：

```bash
sha256sum -c YouShuiAuth-latest.jar.sha256
```

### 方式二：源码构建

```bash
git clone https://github.com/FuLingSnow/youshuiauth.git
cd youshuiauth/YouShuiAuth
./mvnw clean package
```

### QQ 功能部署

1. 确认 `config.yml` 中 `qq.enabled: true`，并设置 `qq.shared-secret`（≥32 位随机字符）；
2. 将 `astrbot_plugin_fls_youshuiauth/` 安装到 AstrBot 的 `data/plugins/` 目录；
3. 插件配置（或环境变量）中填入与 `qq.shared-secret` 一致的密钥及桥接地址；
4. 重启代理，日志出现 `QQ bridge listening on ...` 即成功。

## 使用

### 游戏内命令

| 命令 | 说明 |
| --- | --- |
| `/login` | 登录：密码账号输入密码；QQ 账号不带参数时获取 6 位登录码 |
| `/register <密码>` | 密码注册（受 `security.allow-password-registration` 开关控制） |
| `/qqbind` | 获取 8 位 QQ 绑定/注册码（聊天栏附带复制按钮） |
| `/changepassword` | 修改密码（受 `security.allow-password-change` 开关控制） |
| `/logout` | 注销当前登录状态 |
| `/unregister` | 注销账号 |
| `/2fa` | TOTP 两步验证绑定 / 校验 / 解除 |
| `/ysauth`（别名 `/vauth`） | 管理命令，见下表 |

### `/ysauth` 管理子命令

| 命令 | 说明 |
| --- | --- |
| `/ysauth stats` | 查看注册数、在线认证数等统计 |
| `/ysauth qq list` | 查看 QQ 绑定列表（QQ 号掩码显示） |
| `/ysauth qq unbind <昵称\|QQ>` | 解除绑定并吊销其设备免密 |
| `/ysauth qq reset <昵称>` | 重置 QQ 专用账号（仅限 `!qq-only` 账号） |
| `/ysauth report` | 生成诊断报告 |

### QQ 群内命令（需 AstrBot）

| 命令 | 说明 |
| --- | --- |
| `/绑定 <码>` | 消耗绑定码：新昵称自动注册并登录，已有账号完成绑定 |
| `/登录 <码>` | 消耗登录码，完成快捷登录并转服 |

## 配置

在 `plugins/youshuiauth/config.yml` 中修改，主要配置项：

| 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `language` | string | `en` | 界面语言，如 `zh_cn` |
| `security.allow-password-registration` | bool | `true` | 密码注册开关（关闭后仅 QQ 注册） |
| `security.allow-password-change` | bool | `true` | 改密开关 |
| `security.auto-login-enabled` | bool | `false` | 设备免密自动登录开关 |
| `security.auto-login-max-age-days` | int | `7` | 免密有效期（天） |
| `security.auto-login-require-same-subnet` | bool | `true` | 要求与上次登录同 IP 网段 |
| `security.auto-login-fingerprint-required` | bool | `true` | 要求客户端指纹匹配 |
| `premium.check-enabled` | bool | `true` | 正版验证（离线服可关闭） |
| `qq.enabled` | bool | `false` | QQ 绑定 / 快捷登录总开关 |
| `qq.port` | int | `8765` | 内网桥接端口（仅监听本机） |
| `qq.shared-secret` | string | - | 与 AstrBot 插件一致的 HMAC 密钥（≥32 位） |
| `qq.allowed-groups` | list | `[]` | 允许发起绑定 / 登录的 QQ 群白名单 |
| `qq.bind-code-ttl-seconds` | int | `300` | 绑定码有效期（秒） |
| `qq.login-code-ttl-seconds` | int | `120` | 登录码有效期（秒） |
| `qq.max-attempts-per-minute` | int | `5` | 单 QQ 每分钟操作上限 |

### 配置示例

```yaml
language: zh_cn

security:
  allow-password-registration: false
  allow-password-change: false
  auto-login-enabled: true
  auto-login-max-age-days: 7
  auto-login-require-same-subnet: true
  auto-login-fingerprint-required: true

premium:
  check-enabled: false

qq:
  enabled: true
  bind-address: "127.0.0.1"
  port: 8765
  shared-secret: "请替换为至少32位的随机字符串"
  allowed-groups: ["123456789"]
  bind-code-ttl-seconds: 300
  login-code-ttl-seconds: 120
  max-attempts-per-minute: 5

database:
  storage-type: postgresql
  host: "127.0.0.1"
  port: 5432
  database: "youshuiauth"
  user: "youshuiauth"
  password: "你的数据库密码"
```

## 目录结构

```
YouShuiAuth/
├── pom.xml                          # Maven 构建配置（Java 21）
├── src/main/java/net/rafalohaki/veloauth/
│   ├── VeloAuth.java                # 插件入口与生命周期
│   ├── command/                     # 登录 / 注册 / 管理命令与 QQ 服务
│   ├── listener/                    # 连接、认证事件与后端守卫
│   ├── connection/                  # 转服协调与登录大厅超时调度
│   ├── cache/                       # 授权缓存与会话管理
│   ├── auth/                        # TOTP 与冲突模式
│   ├── database/                    # 多数据库接入与版本迁移
│   ├── qq/                          # QQ 桥接服务、码管理、设备指纹
│   ├── config/                      # 配置加载与校验
│   ├── i18n/                        # 多语言支持
│   ├── audit/                       # 审计日志
│   └── util/                        # 工具类（指纹、地址、安全等）
├── src/main/resources/
│   ├── default-config.yml           # 默认配置模板
│   └── lang/                        # 多语言文件
└── ../astrbot_plugin_fls_youshuiauth/   # 配套 AstrBot 插件
```

## 注意事项

- `qq.allowed-groups` 填 `*` 时放行所有群（含私聊），仅限本地测试，生产环境请改为真实群号。
- 设备指纹由客户端上报字段组合而成，属于**便利因子**而非强凭据：2FA 账号永不自动登录；改密 / 解绑会吊销全部免密记录。
- 登录码仅存在代理内存中，代理重启后失效；同一账号同一时刻只有一个有效登录码。
- 绑定码 / 登录码在数据库中只保存 SHA-256 哈希，明文不出现在任何存储中。
- `database` 表名保持 `VELOAUTH_` 前缀以兼容既有数据，升级时请勿手动改名。
- 首次从旧版 `veloauth` 数据目录升级时，插件会自动将 `plugins/veloauth/` 内容迁移到 `plugins/youshuiauth/`。

## CI 与依赖安全

推送 `main` 会触发四个并行/串联的阶段：

| 阶段 | 内容 |
|---|---|
| build | `./mvnw clean verify pmd:cpd-check`：全量单测、PMD、CPD、JaCoCo、打包 |
| database-it | PostgreSQL 17 与 MySQL 8.4 服务容器上的数据库集成测试 |
| osv-scan | 对 CycloneDX SBOM 做漏洞扫描，发现漏洞即失败 |
| release | 前两项通过后更新 `latest` 发布（`YouShuiAuth-latest.jar` 与 `.sha256`） |

依赖版本固定写在 `pom.xml`，扫描结果可在 Actions 的 `osv-scan` 任务中查看。

## 开源许可

本项目基于 [GNU Affero General Public License v3.0 (AGPL-3.0-only)](./LICENSE) 协议开源。

项目包含来自 [VeloAuth](https://github.com/rafalohaki/VeloAuth) 的代码（MIT，见 [LICENSE.VeloAuth](./LICENSE.VeloAuth)），依照其许可要求保留原始版权声明。

作者：[FuLingSnow](https://github.com/FuLingSnow) · 欢迎提交 [Issue](https://github.com/FuLingSnow/youshuiauth/issues) 与 PR
