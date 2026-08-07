# CitySeek Web

`web/` 保存 CitySeek 的 Vue 2、Element UI 与 Axios 静态页面。依赖文件已经放在
`web/js/` 和 `web/css/` 中，不需要执行前端构建命令。

开发时需要由静态服务器提供页面，并将以下请求转发到 Spring Boot 服务：

- `/api/*`：去掉 `/api` 前缀后转发到 `http://127.0.0.1:8081`
- `/blogs/*`：直接转发到 `http://127.0.0.1:8081`

仓库提供了 `nginx.conf.example`。使用本机 Nginx 时：

1. 复制该文件覆盖 Nginx 的 `conf/nginx.conf`；
2. 将 `__CITYSEEK_WEB_ROOT__` 替换为当前 `web` 目录的绝对路径，Windows 路径使用 `/`；
3. 启动 Nginx，访问 `http://localhost:8080`。

Nginx 二进制、日志、PID 和用户上传文件都属于运行环境产物，不提交到源码仓库。
使用 Docker Compose 时无需手工配置 Nginx。在仓库根目录执行 `docker compose up -d --build`，
编排会使用 `Dockerfile` 和 `nginx.docker.conf` 构建前端镜像，并把请求转发到后端容器。
