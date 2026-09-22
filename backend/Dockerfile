FROM python:3.12-slim AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
RUN groupadd --system neurox && useradd --system --gid neurox --home /app neurox
COPY requirements*.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY alembic alembic
COPY alembic.ini .
COPY app app
RUN chown -R neurox:neurox /app
USER neurox
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
