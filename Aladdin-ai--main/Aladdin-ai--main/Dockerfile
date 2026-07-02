# Stage 1 (builder)
FROM python:3.11-slim as builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements_unified.txt .
RUN pip install --user --no-cache-dir -r requirements_unified.txt

# Stage 2 (runtime)
FROM python:3.11-slim

# Install curl for healthcheck + runtime system deps
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create non-root user "aladdin" (uid 1000)
RUN useradd -m -u 1000 aladdin

# Copy installed packages from builder
COPY --from=builder /root/.local /home/aladdin/.local

WORKDIR /app

# Copy app code
COPY . /app

# Ensure local bin is in PATH
ENV PATH=/home/aladdin/.local/bin:$PATH

RUN chown -R aladdin:aladdin /app

USER aladdin

HEALTHCHECK CMD curl -f http://localhost:8000/health || exit 1

EXPOSE 8000

ENTRYPOINT ["python", "-m", "uvicorn", "backend.main:app"]
CMD ["--host", "0.0.0.0", "--port", "8000"]
