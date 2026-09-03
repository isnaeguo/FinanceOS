# FinanceOS 局域网同步协议 v2（配对加密）

> 本文档与 `shared/src/commonMain/kotlin/com/financeos/shared/lansync/` 的 KDoc 相互引用，
> 常量定义以 [LanSyncSpec] 为唯一来源。

## 1. 概述

局域网同步仍走“单请求-单响应、Content-Length、无 chunked”的明文 HTTP 传输（端口 45678，
协议本身不加密传输层），但对业务数据（快照/导入结果）启用**配对码认证 + 端到端加密**：

- 接收端开启共享时生成一次性的 **10 位配对码**，仅本次接收会话有效；停止接收即失效。
- 发送端必须输入该配对码，才能拉取或推送快照。
- 旧版本明文客户端访问受保护接口返回 `426 Upgrade Required` 并提示升级。

## 2. 配对码

- 10 字符 Base32（RFC 4648 无填充、大写，剔除易混 `0/O/1/I`），字母表共 28 字符，示例：`K7M2QX4T9A`。
- 实际熵约 48 bit。设计目标“≥60 bit”与“10 字符示例”不可兼得（60 bit 需 13 字符），
  本版本按示例采用 10 字符；48 bit 仍远超 6 位数字码（约 20 bit）的离线枚举可行边界，
  且配对码仅在单次接收会话内有效，会话外无法重放。

## 3. HTTP 线格式

受保护端点：`GET /api/snapshot`、`POST /api/snapshot`（`/api/ping` 不含业务数据，保持明文以
兼容地址探测）。

新增请求头：

| 头 | 必带 | 说明 |
|---|---|---|
| `X-FOS-Proto` | 是 | 恒为 `2`，同时作为 AEAD 的 AAD |
| `X-FOS-Salt` | POST 必带；GET 可省略 | KDF salt，16 字节十六进制；GET 省略时由服务端生成并在响应头回写 |
| `X-FOS-Nonce` | POST 必带 | 本请求所用 IV，16 字节十六进制 |
| `X-FOS-Device-Id` | 是 | 本机持久化 UUID v4 |

响应头：成功响应为密文，携带 `X-FOS-Salt`（GET 生成场景）与 `X-FOS-Nonce`（本次响应 IV）。

## 4. 密钥派生与加密

- `K = PBKDF2-HMAC-SHA256(pairing_code, salt, iterations=150_000, dkLen=32)`，salt 来自请求头。
- 算法：`AES-256-CBC + HMAC-SHA256`（Encrypt-then-MAC），payload `alg` 字段声明为
  `AES-256-CBC+HMAC-SHA256`。
- MAC 覆盖 `IV ‖ 密文 ‖ AAD`（AAD = `X-FOS-Proto` 值，即 ASCII `2`）。
- 传输信封：`iv(16) ‖ mac(32) ‖ ciphertext`；iv 同时经 `X-FOS-Nonce` 明文携带（iv 非秘密）。

**选型说明**：Apple 公开 API（CommonCrypto/Security）不提供 AES-GCM，而三端一致性要求给定
参数密文逐字节一致，因此全端统一 CBC+HMAC，而非 JVM 用 GCM、Native 用 CBC 的混合方案。

## 5. 明文 payload

加密前 JSON：

```json
{
  "proto": 2,
  "alg": "AES-256-CBC+HMAC-SHA256",
  "ts": 1786000000000,
  "device_id": "uuid-v4",
  "kind": "snapshot_v2 | snapshot_response | import_result",
  "body": "<financeos-backup JSON 字符串>"
}
```

- `kind=snapshot_v2`：客户端推送快照。
- `kind=snapshot_response`：GET 响应，body 为对端完整快照。
- `kind=import_result`：POST 响应，body 为 `{"imported":{...}}`。

## 6. 错误码

| HTTP | 场景 |
|---|---|
| 426 | 请求缺少 `X-FOS-Proto` 或版本非 2（旧客户端）→ 中文“请升级 FinanceOS” |
| 401 | 解密/验签失败 → “配对码错误或数据已损坏” |
| 400 | `\|server_now - ts\| > 5 分钟`；或同会话内 nonce 重复 |
| 429 | 同一会话累计 5 次认证失败 → 拒绝并提示重新开启接收 |
| 413 | 请求体超过 64MB 上限 |
| 503 | 接收会话未开始（配对码为空） |

## 7. 残余风险（如实声明）

- 传输层未加密：元数据（IP、端口、包长度、请求时刻）仍可见；密文长度与明文长度近似（CBC
  填充 ≤16 字节）。
- 无服务器身份认证：配对码熵即安全上界；知道配对码即可访问本会话数据。
- 无前向保密：会话密钥由配对码与盐派生，无临时密钥交换。
- 同网段具备 MITM 能力的攻击者虽无法解密，但可阻断/重放整段密文（重放由 ts 与 nonce 窗口缓解）。

## 8. 跨端一致性

- 固定向量测试位于 `shared/src/commonTest/.../lansync/LanSyncCryptoTest.kt`：给定
  code/salt/iv/aad，JVM（javax.crypto）与 Apple（CommonCrypto）输出逐字节一致。
- 错误配对码、密文篡改、非法信封、时间戳越界均有对应用例。

[LanSyncSpec]: ../shared/src/commonMain/kotlin/com/financeos/shared/lansync/LanSyncSpec.kt
