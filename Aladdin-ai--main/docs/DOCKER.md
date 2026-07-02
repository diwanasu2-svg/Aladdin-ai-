# Docker Guide

## Using Docker Compose

Start the entire stack (Aladdin + Ollama):
```bash
docker-compose up -d
```

View logs:
```bash
docker-compose logs -f aladdin
```

Stop the stack:
```bash
docker-compose down
```

## Building Manually

Build the image:
```bash
docker build -t aladdin-ai .
```

Run the container:
```bash
docker run -p 8000:8000 -v aladdin_data:/app/data aladdin-ai
```

## Volumes

Ensure that the `/app/data` directory inside the container is mapped to a persistent volume, so your memories and settings are not lost between restarts.
