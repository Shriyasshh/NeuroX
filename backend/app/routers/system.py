from fastapi import APIRouter, Depends, HTTPException, Response
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from app.database import get_db
from app.observability import prometheus_metrics
from app.config import APP_ENV, REDIS_URL
from app.services.cognitive_model import status as cognitive_model_status

router = APIRouter(tags=["system"])


@router.get("/health")
def health():
    return {"status": "ok"}


@router.get("/ready")
def ready(db: Session = Depends(get_db)):
    try:
        db.execute(text("SELECT 1"))
    except SQLAlchemyError as exc:
        raise HTTPException(status_code=503, detail="Database is not ready.") from exc
    if APP_ENV in {"staging", "production"}:
        try:
            from redis import Redis

            Redis.from_url(REDIS_URL, socket_connect_timeout=2, socket_timeout=2).ping()
        except Exception as exc:
            raise HTTPException(status_code=503, detail="Redis is not ready.") from exc
        model = cognitive_model_status()
        if not model["available"]:
            raise HTTPException(status_code=503, detail="Cognitive model is not ready.")
        return {
            "status": "ready",
            "database": "available",
            "redis": "available",
            "cognitiveModel": model["model"],
        }
    return {"status": "ready", "database": "available"}


@router.get("/metrics", include_in_schema=False)
def metrics():
    return Response(prometheus_metrics(), media_type="text/plain; version=0.0.4")
