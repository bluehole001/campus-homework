# 阶段1: 构建
FROM maven:3.8.5-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# 阶段2: 运行
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/campus-homework.jar app.jar
# 关键: 将端口设置为环境变量，Render 会注入一个 PORT 变量[reference:2]
ENTRYPOINT ["sh", "-c", "java -jar app.jar --server.port=${PORT:8080}"]