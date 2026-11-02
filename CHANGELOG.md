# 更新记录

## v2.0.0

### 破坏性变更
- 包名 `net.rafalohaki.veloauth` → `io.github.fulingsnow.youshuiauth`，主类 `VeloAuth` → `YouShuiAuth`
- 数据库表名统一 `YOUSHUI_` 前缀：`AUTH` → `YOUSHUI_AUTH`、`PREMIUM_UUIDS` → `YOUSHUI_PREMIUM_UUIDS`、
  `VELOAUTH_*` → `YOUSHUI_*`。启动时自动原地改名（幂等，带引号/无引号两种历史形态都识别），数据保留
- 权限节点根改为 `youshuiauth.admin`；旧节点 `veloauth.admin` 不再被识别，升级后需更新权限组配置
- 数据目录 `plugins/veloauth/` → `plugins/youshuiauth/`（新目录为空时自动迁移）
- 插件 ID 与产物名保持不变（`youshuiauth` / `YouShuiAuth-<platform>-2.0.0.jar`）

### 架构
- 三层拆分：`core` 只保留平台无关实现（`checkVelocityLeak` 严格门禁），
  `platforms/velocity/origin` 与 `platforms/paper/origin` 作为适配层
- 平台工程由 `tool:version-generator` 从 `origin/` 生成，生成目录不入库
- Gradle 多模块，平台经 `mavenLocal` 消费 `youshuiauth-core`
- 平台无关逻辑上收 core：QQ 绑定与快捷登录、设备免密、设备确认、超时算法、
  未登录提醒选键、客户端语言渲染、会话过期、隔离区策略

### Paper 平台（新增）
- 登录链路与冻结隔离区：屏障平台自动铺设、位置回拉、飞行/重力状态恢复
- 命令门控与 Velocity 对齐（已登录玩家不可见认证命令）
- 客户端语言渲染（BCP-47），与 Velocity 同口径
- 未登录提醒：1 秒首提醒 + 周期重复 + 可点击按钮；文案按账号类型选择
  （未注册 / 纯密码 / QQ-only / 已绑 QQ / 注册关闭）
- 实测：Paper 1.21.4 加载、注册 `login/register/changepassword/setpassword/logout/2fa/qqbind/ysauth`

### QQ 集成
- 绑定码与快捷登录由 core 统一处理：TOTP 门禁、审计、失败不白烧码
- 设备免密：指纹由 brand / protocol / locale / 聊天设置 / 皮肤 / 主手 /
  服务器列表 / 文本过滤 / 粒子 / vhost / 平台标识 / 子网组成
- 新设备 Y/N 确认（`qq.device-approval`，默认关闭）：引用通知回复放行或拒绝，超时回退
- 免密群提醒与引用「阻止」撤销：立即踢出并删除设备记录
- 桥接端点：`/internal/qq/{bind,confirm-login,device-approval,stop-auto-login,notify}`

### 修复
- 绑定码与登录码改为「先校验、后消费」：掉线、昵称被占、码过期不再让码作废
- Velocity 补上设备确认流程接线，免密成功后群通知不再静默丢失
- 语言回退补「配置默认语言」一档，客户端语言不受支持时不再渲染缺失键名
- 启动装配中途异常时释放连接池与审计定时任务
- 撤销免密的踢出改用原生 kick 并重试（此前玩家快速重连后踢不掉）
- 设备确认与免密通知不再被 `device-approval.enabled=false` 连带关闭
- Paper 设备确认补齐审计、成功提示、连接代际复检

### 配置与文案
- 新增 `cache.max-sessions` / `cache.max-premium` 独立键（`config-version` 3 → 4，自动迁移并备份）
- `report.enabled`、`premium.resolver.case-sensitive` 此前为死配置，现已生效
- 清理无引用消息键；补齐 `2fa.*`、`admin.2fa.*`、`admin.report.*` 中文翻译
- 三语言键集一致性由测试强制约束

### 构建与发布
- CI 产物只有 Paper / Velocity 两个插件包（core 由两端 shade 打进产物）
- `push main` / PR 只跑测试；推 `v*` tag 才构建并上传产物；
  Actions 手动触发并勾选发布才创建 GitHub Release
- 兼容性：Paper 1.20.0 – 1.21.11 单一 jar 通用（未使用版本特化 API）

## v1.1.2

- 品牌层整理：第三方归属文档与许可证说明统一到 YouShuiAuth，仓库文档不再把本项目称作 YouShuiAuth
- 新增上游 YouShuiAuth（MIT）通知，并随产物内嵌于 `META-INF/licenses/LICENSE.YouShuiAuth`
- 修正 `THIRD_PARTY_NOTICES.md` 中已删除脚本的引用，改为指向 `pom.xml` 中的固定坐标与校验和
- 架构：平台拆分落地——`core` 只保留平台无关实现（编译期不再依赖 Velocity API，`checkVelocityLeak` 严格门禁），
  Velocity 适配层独立为 `platforms/velocity/origin`，与 Paper 同构（origin → 版本生成器 → CI 矩阵）
- 构建：Gradle 多模块（`core` / `tool:version-generator`），平台工程经 `mavenLocal` 消费 `youshuiauth-core`；
  测试拆分 `core` 374 例 + Velocity 597 例
- 新增 Paper 平台支持：`platforms/paper/origin`（登录链路 + 冻结隔离区），产物 `YouShuiAuth-paper-<ver>.jar`
  - 原计划独立发布的「1.1.0 Paper」里程碑并入本版本，版本号统一为 1.1.2
  - 实测：Paper 1.21.4 加载插件、注册 `login/register/changepassword/setpassword/logout/2fa/qqbind/ysauth`

## v1.1.1

- `pom.xml` 许可证声明改为 SPDX 标识 `AGPL-3.0-only`（含 SPDX 链接与归属说明），修复扫描器「许可证无法识别」告警

## v1.1.0

- 新增「新设备/异地登录 QQ 确认」（`qq.device-approval`，默认关闭）：离线账号已有设备记录但本次指纹不匹配时，登录暂缓并在群里 `@绑定QQ` 推送确认通知；本人**引用通知回复 Y（放行并记住新设备）/ N（拒绝并踢出）**，超时自动回退正常登录；服务端强校验回复者必须是绑定 QQ 本人（新增结果码 `FORBIDDEN`）
- 新增「免密登录阻止」：Cookie 设备免密自动登录的群提醒支持**引用回复「阻止」（block / stop）**，立即踢出该登录并撤销设备免密记录（审计 `LOGIN_FAIL/auto-login-stopped`）
- 桥接新增端点 `POST /internal/qq/device-approval` 与 `POST /internal/qq/stop-auto-login`；`/internal/qq/notify` 每条消息新增 `type`（auto_login / device_approval）与一次性令牌 `approvalId` 字段（向后兼容）
- 通知文案调整：免密提醒去掉「联系管理员」指引；确认通知展示可读客户端版本（如 `1.21.4 (协议 776)`）与脱敏 IP 段（IPv4 /24、IPv6 /64）
- 23 种语言文件同步新增 `qq.device_approval.*` 与 `qq.auto_login.stopped_kick` 文案

## v1.0.3

- 数据库初始化遇到连接槽位耗尽时按 2/4/6 秒退避重试（最多 4 次），重启代理不再因上游连接未释放而启动失败
- QQ 专用账号解绑后视为未注册：进服提示重新注册，`/qqbind` 可重建同名账号并清理旧记录
- `/ysauth` 支持 Tab 联想：子命令、`qq` 子命令与在线玩家昵称
- `/ysauth` 帮助补全至全部子命令，文案前缀统一为 `/ysauth`（23 种语言）

## v1.0.2

- 新增 `security.auto-login-qq-enabled` 与 `security.auto-login-password-enabled`：按账号类型分别控制是否允许免密登录（默认仅 QQ 账号免密）
- `/qqbind` 与 `/登录` 提示中的命令片段改为金色高亮，点击即可复制
- 首次 QQ 注册提示拆为两行（标题与操作提示），并修复入群按钮文案乱码
- 免密登录改走与密码登录一致的异步转服路径，并输出耗时调试日志

## v1.0.1

- 新增 `qq.join-url`：`/qqbind` 生成的绑定码行尾附带可点击的「加入 QQ 群」按钮，留空则不显示
- 绑定码与登录码的「复制命令」按钮改为与提示文字同行显示，不再单独占一行
- 修复多实例共用同一数据库时初始化竞争失败的问题（按 JDBC URL 串行化建表与迁移）

## v1.0.0

- 基于 YouShuiAuth 1.6.2 深度定制
- 新增 QQ 绑定注册、QQ 快捷登录、设备免密自动登录与群内登录提醒
- 新增 TOTP 两步验证与审计日志
- 新增 `/setpassword`：QQ 专用账号可补设密码，支持 QQ 与密码双通道登录
- 新增开关：`security.allow-qq-set-password`、`security.deny-qq-password-login`
- 品牌更名为 YouShuiAuth，管理命令 `/vauth` 更名为 `/ysauth`
- 许可证变更为 AGPL-3.0
- CI 重建：构建与全量测试、PostgreSQL/MySQL 集成测试、SBOM 漏洞扫描，主分支构建通过后自动更新 `latest` 发布
