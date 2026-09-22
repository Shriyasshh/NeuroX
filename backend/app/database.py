# ===================================
#  Imports
# ===================================
from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, Session, sessionmaker
from app.config import APP_ENV
from app.secrets import setting


# ===================================
#  Database Configuration
# ===================================
DATABASE_URL = setting("DATABASE_URL", "sqlite:///./neurox.db")
if APP_ENV in {"production", "staging"} and not DATABASE_URL.startswith(
    ("postgresql://", "postgresql+psycopg://")
):
    raise RuntimeError("A PostgreSQL DATABASE_URL is required outside development.")
if DATABASE_URL.startswith("sqlite"):
    engine_args = {"connect_args": {"check_same_thread": False}}
else:
    pool_size = int(setting("DATABASE_POOL_SIZE", "10"))
    max_overflow = int(setting("DATABASE_MAX_OVERFLOW", "20"))
    pool_timeout = int(setting("DATABASE_POOL_TIMEOUT_SECONDS", "30"))
    pool_recycle = int(setting("DATABASE_POOL_RECYCLE_SECONDS", "1800"))
    if not 1 <= pool_size <= 100 or not 0 <= max_overflow <= 200:
        raise RuntimeError("Database pool settings are outside safe limits.")
    if not 1 <= pool_timeout <= 120 or not 60 <= pool_recycle <= 86400:
        raise RuntimeError("Database pool timing settings are outside safe limits.")
    engine_args = {
        "pool_pre_ping": True,
        "pool_size": pool_size,
        "max_overflow": max_overflow,
        "pool_timeout": pool_timeout,
        "pool_recycle": pool_recycle,
    }
# Create the SQLAlchemy engine and session factory
engine = create_engine(DATABASE_URL, future=True, **engine_args)

# Create a session factory for database sessions
SessionLocal = sessionmaker(
    bind=engine, autoflush=False, autocommit=False, expire_on_commit=False
)


# ===================================
#  Database Models
# ===================================
class Base(DeclarativeBase):
    pass


# Function to access Database session
def get_db():
    db: Session = SessionLocal()
    try:
        yield db
    finally:
        db.close()
