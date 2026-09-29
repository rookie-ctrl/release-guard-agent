@echo off
rem 开发环境一键启动:
rem 1. 中间件(MySQL/ES/Redis/RabbitMQ)在虚拟机 192.168.245.129 上,需已 docker compose up -d
rem 2. API key 放在同目录 .env 文件(CHAT_API_KEY / EMBEDDING_API_KEY),不要提交到版本库
setlocal
if exist .env for /f "usebackq tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
set "MYSQL_HOST=192.168.245.129"
set "REDIS_HOST=192.168.245.129"
set "RABBITMQ_HOST=192.168.245.129"
set "ES_HOST=192.168.245.129"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
call mvn spring-boot:run
endlocal
