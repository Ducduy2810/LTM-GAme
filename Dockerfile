# ── Stage 1: Compile ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# Copy toàn bộ source và lib
COPY src/       src/
COPY lib/       lib/
COPY public/    public/

# Compile (giống run.bat)
RUN mkdir -p bin && \
    javac -encoding UTF-8 \
      -cp "lib/sqlite-jdbc-3.45.3.0.jar:lib/slf4j-api-2.0.13.jar" \
      -d bin \
      src/vn/game/config/*.java \
      src/vn/game/util/*.java \
      src/vn/game/model/*.java \
      src/vn/game/db/*.java \
      src/vn/game/server/*.java \
      src/vn/game/games/*.java \
      src/vn/game/*.java

# ── Stage 2: Runtime ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy compiled classes, libs, và static files
COPY --from=builder /app/bin/    bin/
COPY --from=builder /app/lib/    lib/
COPY --from=builder /app/public/ public/

# Thư mục data để lưu SQLite DB (mount volume ở đây để dữ liệu không mất khi restart)
RUN mkdir -p data

# Classpath
ENV CP="bin:lib/sqlite-jdbc-3.45.3.0.jar:lib/slf4j-api-2.0.13.jar:lib/slf4j-nop-2.0.13.jar"

EXPOSE 3000

CMD java -cp "$CP" vn.game.Main
