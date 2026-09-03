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
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/family_finance?useSSL=false&serverTimezone=UTC&characterEncoding=utf8
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

浏览器打开 http://127.0.0.1:18080。当前应用不直接暴露 MySQL 3306 或 Spring Boot 8080 到公网。

