# ============ 构建阶段 ============
# 用 JDK 17：Spring Boot 2.7 + Lombok 1.18.30，JDK 8 下 Lombok 注解处理器会失效导致编译失败
FROM maven:3.9.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 先只复制 pom，利用 Docker 层缓存：依赖不变时不会重新下载
COPY pom.xml ./
COPY sky-common/pom.xml sky-common/pom.xml
COPY sky-pojo/pom.xml sky-pojo/pom.xml
COPY sky-server/pom.xml sky-server/pom.xml
RUN mvn -q -pl sky-server -am dependency:go-offline

# 复制源码并打包（跳过测试）
COPY sky-common sky-common
COPY sky-pojo sky-pojo
COPY sky-server sky-server
RUN mvn -pl sky-server -am clean package -DskipTests

# ============ 运行阶段 ============
# 只保留 JRE + Jar，缩小镜像体积
FROM eclipse-temurin:17-jre
WORKDIR /app

# 时区 + 健康检查需要的 curl
ENV TZ=Asia/Shanghai
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# 非 root 用户运行，降低安全风险
RUN useradd -r -u 1001 appuser
COPY --from=builder /app/sky-server/target/sky-server-1.0-SNAPSHOT.jar app.jar
RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8080

# JVM 参数可通过环境变量覆盖；默认 512m 堆 + G1 垃圾回收器
ENV JAVA_OPTS="-Xms512m -Xmx512m -XX:+UseG1GC -Djava.security.egd=file:/dev/./urandom"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
