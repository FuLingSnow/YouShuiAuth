# 更新记录

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

- 基于 VeloAuth 1.6.2 深度定制
- 新增 QQ 绑定注册、QQ 快捷登录、设备免密自动登录与群内登录提醒
- 新增 TOTP 两步验证与审计日志
- 新增 `/setpassword`：QQ 专用账号可补设密码，支持 QQ 与密码双通道登录
- 新增开关：`security.allow-qq-set-password`、`security.deny-qq-password-login`
- 品牌更名为 YouShuiAuth，管理命令 `/vauth` 更名为 `/ysauth`
- 许可证变更为 AGPL-3.0
- CI 重建：构建与全量测试、PostgreSQL/MySQL 集成测试、SBOM 漏洞扫描，主分支构建通过后自动更新 `latest` 发布
