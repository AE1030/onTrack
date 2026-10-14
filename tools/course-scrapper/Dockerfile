# Cloud Run Job image. Not a server: it runs to completion and its exit code is the alert.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

RUN pip install --no-cache-dir \
        "requests>=2.32,<3" \
        "aiohttp>=3.9,<4" \
        "beautifulsoup4>=4.12,<5" \
        "psycopg2-binary>=2.9,<3"

COPY course-scrapper.py ./

RUN useradd --create-home --uid 1001 scraper
USER scraper

ENTRYPOINT ["python", "course-scrapper.py"]
