FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

WORKDIR /app
COPY services/consultation-classifier/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY services/consultation-classifier/app ./app

ENV PORT=8083
EXPOSE 8083

CMD ["sh", "-c", "uvicorn app.main:app --host 0.0.0.0 --port ${PORT}"]
