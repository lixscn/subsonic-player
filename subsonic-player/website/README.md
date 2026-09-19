# Subsonic Player 官网（www.lixs.fun）

静态落地页：产品介绍 + 截图 + Windows / Android 下载 + GitHub 链接，面向搜索引擎做了优化。
不依赖任何外部资源（无 CDN、无第三方脚本），纯 HTML/CSS，加载快且在境内访问稳定。

## 目录

```
website/
├── index.html           # 中文落地页（x-default）
├── en/index.html        # 英文落地页（/en/）
├── styles.css           # 样式（中英共用，与客户端一致的深色主题）
├── robots.txt           # 允许全部抓取 + 指向 sitemap
├── sitemap.xml          # 站点地图（含 xhtml:link 多语言互指 + 图片 sitemap）
├── assets/              # 图片（jpg 为网页用压缩版；desktop-raw.png 是截图原图，不部署）
├── downloads/           # 安装包（部署用；apk 由构建脚本产出后同步过来）
├── tools/prepare-assets.ps1   # 图片裁剪/缩放/压缩
├── tools/zip-win-release.ps1  # 把 dotnet 的单文件发布目录打成下载用 zip（条目名用正斜杠、跳过运行缓存）
├── tools/audit-credentials.ps1 # 上线前核查：公开文件里是否含账号密码
└── deploy/              # 实际推送到服务器的文件集 + nginx 配置模板
```

## 多语言（中 / 英）

- 中文：`https://www.lixs.fun/`（`hreflang="x-default"`），英文：`https://www.lixs.fun/en/`
- 两页互相声明 `hreflang`（zh-CN / en / x-default），各自有独立 `canonical`、`og:locale`；导航与页脚有语言切换入口
- 资源用绝对路径（`/assets/...`），所以 `/en/` 下无需重复放置图片
- **改文案时中英两页都要改**，否则会出现「页面语言与声明不符」，对 SEO 不利
- 部署：`scp en/index.html Aliyun:/var/www/lixs.fun/en/index.html`（该目录需已存在）

## 部署（VPS：Aliyun 203.0.113.10）

站点目录 `/var/www/lixs.fun`，nginx 配置 `/etc/nginx/sites-available/lixs.fun`（`sites-enabled/lixs.fun` 是软链接）。

```powershell
# 1) 同步安装包
cd subsonic-player/android && .\build.ps1            # 产出 dist\SubsonicPlayer-0.1.0-release.apk
Copy-Item dist\SubsonicPlayer-0.1.0-release.apk ..\website\downloads\SubsonicPlayer-android-0.1.0.apk -Force

# 1b) 同步 Windows 包（单文件发布 → 打包；约 275MB，deploy/ 里不含它，由第 3 步单独传）
cd ..\dotnet && powershell -ExecutionPolicy Bypass -File publish-singlefile.ps1 -TargetDir dist\single-win-x64
cd ..\website && .\tools\zip-win-release.ps1 -Source ..\dotnet\dist\single-win-x64 -Out downloads\SubsonicPlayer-win-x64.zip

# 2) 上传站点文件
scp -r ..\website\deploy Aliyun:/tmp/sp-deploy
ssh Aliyun 'cp -a /tmp/sp-deploy/. /var/www/lixs.fun/ && rm -rf /tmp/sp-deploy'

# 3) 上传大文件（Windows 包约 275MB）
scp ..\website\downloads\SubsonicPlayer-win-x64.zip Aliyun:/var/www/lixs.fun/downloads/
```

> 注意：`deploy/` 是 `cp -a` 合并覆盖（不是镜像同步），所以第 2 步**不会**删掉服务器上已有的
> `downloads/SubsonicPlayer-win-x64.zip`；换大文件必须走第 3 步。传完用
> `ssh Aliyun 'sha256sum /var/www/lixs.fun/downloads/SubsonicPlayer-win-x64.zip'` 与本地比对。

## 服务器侧要点（改配置前务必先看这段）

1. **TLS 不是常规 443 监听**：`nginx.conf` 的 `stream` 段用 `ssl_preread` 按 SNI 分流，
   非 `derp1.lixs.fun` 的流量走 `127.0.0.1:8445`（注入 PROXY protocol）→ `127.0.0.1:8444`。
   所以 HTTP 站点的 server 块写 **`listen 8444 ssl proxy_protocol;`**，不要写 443。
   因此**直连 8444 用 curl 测不通是正常的**（缺 PROXY protocol），要从外部走 443 验证。
2. 证书：`certbot certonly --webroot -w /var/www/lixs.fun --cert-name lixs.fun -d lixs.fun -d www.lixs.fun`。
   80 端口必须保留 `location /.well-known/acme-challenge/` 且**不能被跳转覆盖**，否则续期失败。
3. 原站是 `hotnews.service`（`/opt/hotnews`，Python/FastAPI，监听 8000，nginx 反代）。
   已被 `systemctl disable --now hotnews` 停用；要恢复就先启服务再把配置改回 `proxy_pass http://127.0.0.1:8000`。
   备份在 VPS：`/root/backups/hotnews-*.tar.gz`、`/root/backups/lixs.fun.nginx-real-*.bak`、
   旧静态残留 `/root/backups/www-lixs.fun-old-*`。

## 验证清单

```powershell
curl.exe -sI https://www.lixs.fun/                                   # 200
curl.exe -s -o NUL -w "%{http_code} %{redirect_url}" http://www.lixs.fun/   # 301 -> https
curl.exe -sI https://www.lixs.fun/downloads/SubsonicPlayer-win-x64.zip     # 200 + Accept-Ranges
curl.exe -s https://www.lixs.fun/robots.txt
curl.exe -s -o NUL -w "%{http_code}" https://www.lixs.fun/sitemap.xml
```

## SEO 已做的项

- `<title>` / `meta description` / `keywords` / `canonical`（https://www.lixs.fun/）
- Open Graph + Twitter Card（og:image 用 1200×630 封面）
- 结构化数据：`SoftwareApplication`（含 featureList、downloadUrl、codeRepository、offers=0）
  与 `WebSite` + `FAQPage`（常见问题可出富摘要）
- 语义化结构：单一 `h1`、分区 `h2/h3`、`nav`/`main`/`section`/`figure`/`figcaption`、图片均带 `alt`
- `robots.txt` + `sitemap.xml`（含 image sitemap）；HTTP 全量 301 到 HTTPS；图片长缓存、安装包支持断点续传
- 无外部依赖，LCP 元素（hero 截图）用 `preload` + `fetchpriority=high`，其余图片 `loading=lazy`

## 后续可做

- 把站点源文件纳入仓库版本管理后，接 GitHub Actions 自动部署（push 即同步到 VPS）
- 加百度站长/Google Search Console 验证文件，并主动提交 sitemap
- 英文版页面（`/en/`）+ `hreflang`
