# Cloud Run Job image. Not a server: it runs to completion and its exit code is the alert.
FROM python:3.12-slim

# Unbuffered so Cloud Logging shows progress during a run rather than in one lump at the end.
# A cold scrape plus extract is long enough that the difference matters when something hangs.
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app

# Dependencies first, so an edit to the pipeline does not re-resolve them on every build.
COPY pyproject.toml ./
RUN pip install --no-cache-dir \
        "requests>=2.32,<3" \
        "beautifulsoup4>=4.12,<5" \
        "pymongo>=4.8,<5" \
        "google-genai>=1.0,<2"

COPY syllabus_pipeline ./syllabus_pipeline

# Nothing here needs root, and a job that reaches the public internet should not have it.
RUN useradd --create-home --uid 1001 pipeline
USER pipeline

# Overridden per Scheduler entry with --args. The default is the whole pipeline, gated on the
# cadence rule, which is what the daily trigger should do.
ENTRYPOINT ["python", "-m", "syllabus_pipeline"]
CMD ["run", "--respect-cadence"]
