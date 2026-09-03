# MySQL 服务器运行手册

本文适用于当前 Ubuntu 22.04 服务器部署。密码、Token 和 SSH 私钥只在服务器或本机安全环境中填写，不要提交到 Git 或聊天记录。

## 依赖

~~~bash
apt update
apt install -y openjdk-17-jdk mysql-server openssl git
systemctl enable --now mysql
java -version
mysql --version
~~~

## 数据库初始化

以 root 身份在服务器执行。下面的脚本在服务器本地生成随机数据库密码，不回显密码：

~~~bash
set -eu
db_password=$(openssl rand -hex 32)
mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS family_finance CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'family_finance'@'localhost' IDENTIFIED BY '${db_password}';
ALTER USER 'family_finance'@'localhost' IDENTIFIED BY '${db_password}';
CREATE USER IF NOT EXISTS 'family_finance'@'127.0.0.1' IDENTIFIED BY '${db_password}';
ALTER USER 'family_finance'@'127.0.0.1' IDENTIFIED BY '${db_password}';
GRANT ALL PRIVILEGES ON family_finance.* TO 'family_finance'@'localhost';
GRANT ALL PRIVILEGES ON family_finance.* TO 'family_finance'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL
install -d -m 700 /etc/family-finance
umask 077
cat > /etc/family-finance/family-finance.env <<ENV
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/family_finance?useSSL=false&serverTimezone=UTC&characterEncoding=utf8&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME=family_finance
SPRING_DATASOURCE_PASSWORD=${db_password}
ENV
chmod 600 /etc/family-finance/family-finance.env
unset db_password
~~~

## 应用部署

~~~bash
cd /root/Family-IE-Management
git pull --ff-only origin codex/family-finance-stage-2
./mvnw -q package
~~~

创建 /etc/systemd/system/family-finance.service：

~~~ini
[Unit]
Description=Family Finance Spring Boot application
After=network.target mysql.service
Requires=mysql.service

[Service]
Type=simple
WorkingDirectory=/root/Family-IE-Management
EnvironmentFile=/etc/family-finance/family-finance.env
ExecStart=/usr/bin/java -jar /root/Family-IE-Management/target/family-finance-0.0.1-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
~~~

启动和查看日志：

~~~bash
systemctl daemon-reload
systemctl enable --now family-finance
systemctl status family-finance --no-pager
journalctl -u family-finance -n 120 --no-pager
~~~

正常日志应显示 MySQL 连接成功、Flyway 迁移到 V12、Hibernate schema validation 通过以及 Tomcat 监听 127.0.0.1:8080。

## IP 访问（Nginx）

服务器已配置 Nginx 监听 80，并将请求代理到 Spring Boot 的 127.0.0.1:8080。需要在阿里云 ECS 控制台的安全组入方向新增一条规则：协议 TCP、端口 80、来源按需要设置为你的公网 IP 或 `0.0.0.0/0`。服务器系统层的 UFW 当前未启用，iptables 默认放行；若公网仍无法访问，优先检查安全组和实例公网 IP 绑定。

验证：

```bash
nginx -t
systemctl is-active nginx
curl -i http://127.0.0.1/
```

安全组放行后，浏览器访问 `http://YOUR_SERVER_IP`。不要开放 Spring Boot 8080 到公网；MySQL 3306 只有在确实需要远程管理时才按下一节开启。

## 远程 MySQL（显式开启）

如果确实需要用本地数据库工具直连，服务器可以让 MySQL 监听公网网卡，并使用单独的 `family_finance_remote` 账号；当前演示环境已经这样配置。连接参数：

```text
Host: YOUR_SERVER_IP
Port: 3306
User: family_finance_remote
Database: family_finance
```

该账号密码由服务器生成并保存在 `/etc/family-finance/mysql-remote.env` 的 `MYSQL_REMOTE_PASSWORD` 中，只应在服务器控制台读取后填入本地客户端，不要发送到聊天或提交到 Git。阿里云安全组当前允许所有来源时，3306 会暴露给全网；正式使用应把来源收窄到固定 IP，或改回 SSH 隧道，并且绝不开放 MySQL 33060、Spring Boot 8080。

## 更新

~~~bash
cd /root/Family-IE-Management
git pull --ff-only origin codex/family-finance-stage-2
./mvnw -q package
systemctl restart family-finance
journalctl -u family-finance -n 80 --no-pager
~~~

## 访问

从本机建立 SSH 隧道：

~~~bash
ssh -N -L 18080:127.0.0.1:8080 family-finance-server
~~~

浏览器打开 `http://YOUR_SERVER_IP`。如果尚未放行安全组 80 端口，可暂时使用 `http://127.0.0.1:18080` 的 SSH 隧道方式。若显式开放 MySQL 3306 供远程管理，应收窄来源或改回 SSH 隧道，并且绝不开放 MySQL 33060 或 Spring Boot 8080。
