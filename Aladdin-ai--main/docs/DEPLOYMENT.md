# Deployment Guide

## Quick Start (Docker)

The fastest way to deploy Aladdin is using Docker Compose.

```bash
docker-compose up -d
```

## Production Checklist

Before deploying to production, ensure:
1. All debug logging is disabled.
2. `config.yaml` is properly secured.
3. Volumes are mapped for persistent memory (`/app/data`).
4. Proper network isolation is configured (e.g. reverse proxy like Nginx).
5. Secrets are managed securely (using `.env` or external secret manager).

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ALADDIN_ENV` | Environment (dev, prod) | `dev` |
| `OLLAMA_HOST` | URL to Ollama server | `http://localhost:11434` |
| `API_PORT` | Port for the FastAPI server | `8000` |
