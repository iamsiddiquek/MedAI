# Builds one Python service. Build context is the service directory:
#   docker build -f infra/docker/python.Dockerfile --build-arg APP=model_gateway.server:app services/model-gateway
FROM python:3.13-slim
ARG APP
ENV APP=${APP} PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY . .
RUN pip install --no-cache-dir . && useradd --system --uid 10001 afia
USER 10001
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn \"$APP\" --host 0.0.0.0 --port 8000"]
