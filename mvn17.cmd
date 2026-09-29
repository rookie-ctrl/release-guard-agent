@echo off
rem 本机 Maven 默认走 JAVA_HOME=JDK 25,Lombok 注解处理会静默失效导致编译失败。
rem 此脚本强制用 JDK 17 编译/运行本项目(项目 target 就是 Java 17)。
rem 用法:mvn17.cmd spring-boot:run | mvn17.cmd compile | mvn17.cmd package
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
call mvn %*
