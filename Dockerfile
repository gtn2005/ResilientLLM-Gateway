FROM maven:3.9.11-eclipse-temurin-17-noble AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /opt/models/all-MiniLM-L6-v2 \
    && curl -fL --retry 3 https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx -o /opt/models/all-MiniLM-L6-v2/model.onnx \
    && echo '6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452  /opt/models/all-MiniLM-L6-v2/model.onnx' | sha256sum -c - \
    && curl -fL --retry 3 https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json -o /opt/models/all-MiniLM-L6-v2/tokenizer.json
WORKDIR /app
COPY --from=build /app/target/resilient-llm-gateway-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
