# 发票核验系统

本地启动、配置、备份与测试说明。下面的命令都在 PowerShell 中执行，工作目录为仓库根目录。

## 环境变量

```powershell
Copy-Item .env.example .env
```

按下面的说明改 `.env`。文件里只放占位符或你自己在本机生成的值，不要把 `.env`、数据库转储或存储目录提交到 Git。

必填：

| 变量 | 要求 |
| --- | --- |
| `POSTGRES_PASSWORD` | 本机 PostgreSQL 口令 |
| `APP_JWT_SECRET` | 至少 32 字节 |
| `APP_CREDENTIAL_MASTER_KEY` | 恰好 32 字节的 Base64 |
| `APP_STORAGE_ROOT` | 发票文件存储目录，例如 `./data` |

可选：

| 变量 | 说明 |
| --- | --- |
| `APP_JWT_SECURE_COOKIE` | 本地 HTTP 设为 `false`。未设置时默认为 `true`，Cookie 只在 HTTPS 下发送 |
| `APP_PADDLE_TOKEN_URL` | 百度 Paddle 取 token 的地址。未设置时使用 `https://aip.baidubce.com/oauth/2.0/token` |
| `APP_PADDLE_RECOGNIZE_URL` | 百度 Paddle 发票识别地址。未设置时使用 `https://aip.baidubce.com/rest/2.0/ocr/v1/multiple_invoice` |

生成密钥时使用下面这种形状，不要把示例输出当作真实密钥：

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 }))
```

把该命令的输出写入 `APP_CREDENTIAL_MASTER_KEY`。同一形状的输出作为 `APP_JWT_SECRET` 时，按 UTF-8 计算超过 32 字节。如果希望 JWT 使用解码后的 32 字节，在值前面加上 `base64:`。

`docker compose` 会读取仓库根目录的 `.env`，用来替换 `compose.yaml` 中的 `${POSTGRES_PASSWORD}`。后端 `bootRun` 只读取当前进程的环境变量。启动后端前，在同一个 PowerShell 会话中导入：

```powershell
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') { return }
    $name, $value = $_.Split('=', 2)
    Set-Item -Path "Env:$($name.Trim())" -Value $value.Trim()
}
```

## 启动

先启动 PostgreSQL。数据库名和用户都是 `invoice`，端口 `5432`。

```powershell
docker compose up -d
```

Flyway 在后端启动时执行迁移，不需要单独的迁移命令。

后端监听 `8080`：

```powershell
cd backend; .\gradlew.bat bootRun
```

另开一个终端启动前端。前端开发服务器端口是 `5173`。Vite 把 `/api` 代理到 `http://127.0.0.1:8080`。

```powershell
cd frontend; npm install; npm run dev
```

默认端口：Postgres `5432`，后端 `8080`，前端 `5173`。浏览器打开 `http://127.0.0.1:5173`。

## 百度 Paddle 凭证

用户在设置页 `/settings/ocr` 填写自己的百度 Paddle API Key 和 Secret Key。服务端用 AES-GCM 保存这两项。查询凭证的响应只返回掩码，不返回原文。

## 测试

```powershell
cd backend; .\gradlew.bat test
cd frontend; npm test -- --run
cd e2e; npm test
```

端到端测试需要 Docker。它会自行启动一套 Postgres，映射到端口 `55432`，并启动 WireMock，不使用上面那套 `5432` 开发库。

## 备份

转储 Postgres 数据库，并复制 `APP_STORAGE_ROOT` 指向的目录。不要提交转储文件，也不要提交该存储目录。

```powershell
docker compose exec postgres pg_dump -U invoice -d invoice > invoice-backup.sql
Copy-Item -Recurse $env:APP_STORAGE_ROOT .\storage-backup
```

## ERP

对接说明见 [docs/api/erp-integration.md](docs/api/erp-integration.md)。
