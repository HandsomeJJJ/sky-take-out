# 构建阶段：使用 JDK 8 与 Spring Boot 2.7 的目标运行环境保持一致
FROM maven:3.9.9-eclipse-temurin-8 AS builder
WORKDIR /app
COPY pom.xml ./
COPY sky-common/pom.xml sky-common/pom.xml
COPY sky-pojo/pom.xml sky-pojo/pom.xml
COPY sky-server/pom.xml sky-server/pom.xml
RUN mvn -q -pl sky-server -am dependency:go-offline
COPY sky-common sky-common
COPY sky-pojo sky-pojo
COPY sky-server sky-server
RUN mvn -pl sky-server -am clean package -DskipTests

# 运行阶段：仅保留可执行 Jar，缩小镜像体积
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=builder /app/sky-server/target/sky-server-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
