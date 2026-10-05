# ERP 接口

当前版本只提供下面两个接口。当前 API 没有分页：`GET /api/erp/invoices` 一次返回该用户全部符合条件的发票，响应体是 JSON 数组，没有页码、页大小或总数包装。

本版本的推送客户端不会调用外部 ERP。`erp_push_receipts` 表中的回执行就是幂等记录。

## 认证

先调用 `POST /api/auth/login` 或 `POST /api/auth/register`。成功后服务端设置两枚 Cookie：

| Cookie | 属性 | 用途 |
| --- | --- | --- |
| `access_token` | HttpOnly | 标识当前用户。后续请求都要带上这枚 Cookie |
| `XSRF-TOKEN` | 可读 | 变更请求把这枚 Cookie 的值放到请求头 `X-XSRF-TOKEN` |

`GET /api/erp/invoices` 只需要 `access_token`。`POST /api/erp/invoices/push` 还要带 `X-XSRF-TOKEN`，以及 `Content-Type: application/json`。

未登录时返回 `401`：

```json
{"code":"UNAUTHORIZED","message":"Authentication required"}
```

变更请求缺少或带错 CSRF 令牌时返回 `403`：

```json
{"code":"FORBIDDEN","message":"CSRF token missing or invalid"}
```

本地 HTTP 需要 `APP_JWT_SECURE_COOKIE=false`，否则浏览器不会保存带 Secure 标记的 Cookie。

## 查询可推送发票

`GET /api/erp/invoices`

只返回当前 Cookie 对应用户、状态为 `PASSED` 的发票，按创建时间从新到旧排列。其他状态和其他用户的发票不会出现。

```json
[
  {
    "id": "11111111-1111-1111-1111-111111111111",
    "invoiceCode": "033001900111",
    "invoiceNumber": "00123456",
    "status": "PASSED"
  }
]
```

字段只有 `id`、`invoiceCode`、`invoiceNumber`、`status`。

## 推送

`POST /api/erp/invoices/push`

请求头 `Idempotency-Key` 必填，同一用户下最长 128 个字符。请求体：

```json
{ "invoiceId": "11111111-1111-1111-1111-111111111111" }
```

成功时返回 `200`：

```json
{ "invoiceId": "11111111-1111-1111-1111-111111111111", "status": "PUSHED" }
```

同一个 `Idempotency-Key` 再次调用时，返回第一次保存的响应体，并且不会再次推送。幂等查找按用户和该请求头进行，发生在处理本次 `invoiceId` 之前。

错误：

| 状态 | code | 条件 |
| --- | --- | --- |
| 400 | `IDEMPOTENCY_KEY_REQUIRED` | 没有 `Idempotency-Key`，或该头为空白。消息为 `Idempotency-Key header is required` |
| 400 | `IDEMPOTENCY_KEY_TOO_LONG` | `Idempotency-Key` 超过 128 个字符。消息为 `Idempotency-Key must be at most 128 characters` |
| 400 | `INVOICE_REQUIRED` | 请求体缺少 `invoiceId`。消息为 `invoiceId is required` |
| 404 | `INVOICE_NOT_FOUND` | 发票不存在，或属于另一个用户。消息为 `Invoice not found` |
| 409 | `INVOICE_NOT_PASSED` | 发票不是 `PASSED`。消息为 `Only PASSED invoices can be pushed to ERP` |

错误体形状：

```json
{"code":"INVOICE_NOT_FOUND","message":"Invoice not found"}
```
