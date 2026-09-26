"""
Titan AI Risk Engine — Enhanced
=====================================
Dual-mode service:
  • gRPC  → CheckRisk (risk scoring for every transfer)
  • HTTP  → FastAPI report endpoints (port 8085)

High-value transfer rule:
  Transfer >= $10,000  → BLOCKED immediately (persisted to titan_systemdb)

Risk scoring table:
  Amount               | Score | Level   | Action
  ---------------------|-------|---------|--------
  < $1,000             |  10   | LOW     | ALLOW
  $1,000 – $9,999      |  50   | MEDIUM  | REVIEW     ← NOTE: < $10,000 REVIEW
  >= $10,000           | 100   | BLOCKED | BLOCK  ⛔  ← HIGH-VALUE BLOCK

Persistence:
  Every CheckRisk call is persisted to titan_systemdb.risk_events.
  Every BLOCK decision is additionally persisted to titan_systemdb.blocked_transfers.

HTTP Endpoints (port 8085):
  GET  /health                         → service liveness
  GET  /api/reports/risk               → paginated risk event log
  GET  /api/reports/blocked            → blocked transfers log
  GET  /api/reports/stats              → aggregated stats
  GET  /api/reports/stats/daily        → daily summary
  POST /api/reports/generate           → trigger report generation
"""

import grpc
import threading
import logging
import os
import sys
from concurrent import futures
from datetime import datetime, date, timedelta
from decimal import Decimal

# ─── FastAPI & Pydantic ───────────────────────────────────────────────────────
from fastapi import FastAPI, Query, HTTPException, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional, List
import uvicorn

# ─── Database ─────────────────────────────────────────────────────────────────
import psycopg2
from psycopg2.extras import RealDictCursor
import psycopg2.pool

# ─── Logging ──────────────────────────────────────────────────────────────────
LOG_LEVEL = os.environ.get("LOG_LEVEL", "INFO").upper()
logging.basicConfig(
    level=getattr(logging, LOG_LEVEL, logging.INFO),
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# ─── Environment Variables ────────────────────────────────────────────────────
GRPC_PORT   = int(os.environ.get("GRPC_PORT",   "50051"))
HTTP_PORT   = int(os.environ.get("HTTP_PORT",   "8085"))
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "10"))

# Risk thresholds — HIGH-VALUE BLOCK threshold is now $10,000
RISK_LOW_MAX_AMOUNT     = float(os.environ.get("RISK_LOW_MAX_AMOUNT",   "1000"))
RISK_BLOCK_THRESHOLD    = float(os.environ.get("RISK_BLOCK_THRESHOLD",  "10000"))   # >= $10,000 → BLOCK

# Risk scores
RISK_SCORE_LOW      = int(os.environ.get("RISK_SCORE_LOW",     "10"))
RISK_SCORE_MEDIUM   = int(os.environ.get("RISK_SCORE_MEDIUM",  "50"))
RISK_SCORE_BLOCKED  = int(os.environ.get("RISK_SCORE_BLOCKED", "100"))

# Database
DB_HOST     = os.environ.get("DB_HOST",     "postgres")
DB_PORT     = int(os.environ.get("DB_PORT", "5432"))
DB_NAME     = os.environ.get("DB_NAME",     "titan_systemdb")
DB_USER     = os.environ.get("DB_USER",     "postgres")
# DB_PASSWORD must be supplied via environment variable — no default to avoid embedding
# credentials in source code.  The service will start but DB persistence will be
# disabled (db_pool stays None) if DB_PASSWORD is missing and DB_ENABLED=true.
DB_PASSWORD = os.environ.get("DB_PASSWORD")
DB_ENABLED  = os.environ.get("DB_ENABLED",  "true").lower() == "true"

# ─── Proto imports ────────────────────────────────────────────────────────────
sys.path.append(os.path.join(os.path.dirname(__file__), 'protos'))

try:
    import risk_engine_pb2 as pb2
    import risk_engine_pb2_grpc as pb2_grpc
except ImportError:
    import protos.risk_engine_pb2 as pb2
    import protos.risk_engine_pb2_grpc as pb2_grpc

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_health.v1.health import HealthServicer

# ─── Database Connection Pool ─────────────────────────────────────────────────
db_pool: Optional[psycopg2.pool.ThreadedConnectionPool] = None


def init_db_pool():
    """Initialize the PostgreSQL connection pool."""
    global db_pool
    if not DB_ENABLED:
        logger.warning("⚠️  DB_ENABLED=false — risk events will NOT be persisted")
        return

    if not DB_PASSWORD:
        logger.error("❌ DB_PASSWORD environment variable is not set — DB persistence disabled")
        return

    try:
        db_pool = psycopg2.pool.ThreadedConnectionPool(
            minconn=2,
            maxconn=10,
            host=DB_HOST,
            port=DB_PORT,
            dbname=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
            connect_timeout=5
        )
        logger.info(f"✅ DB pool ready → {DB_HOST}:{DB_PORT}/{DB_NAME}")
    except Exception as e:
        logger.error(f"❌ DB pool init failed: {e} — continuing without persistence")
        db_pool = None


def get_conn():
    """Get a connection from the pool (may be None if pool is unavailable)."""
    if db_pool is None:
        return None
    try:
        return db_pool.getconn()
    except Exception as e:
        logger.error(f"DB getconn error: {e}")
        return None


def release_conn(conn):
    """Return a connection to the pool."""
    if db_pool and conn:
        try:
            db_pool.putconn(conn)
        except Exception:
            pass


def persist_risk_event(user_id: str, amount: float, risk_score: int,
                       risk_level: str, action: str, transaction_ref: str = None,
                       source_ip: str = None):
    """Persist a risk event to titan_systemdb.risk_events."""
    conn = get_conn()
    if not conn:
        return
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO risk_events
                    (user_id, amount, risk_score, risk_level, action, transaction_ref, source_ip)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                """,
                (user_id, amount, risk_score, risk_level, action, transaction_ref, source_ip)
            )
        conn.commit()
    except Exception as e:
        logger.error(f"persist_risk_event error: {e}")
        try:
            conn.rollback()
        except Exception:
            pass
    finally:
        release_conn(conn)


def persist_blocked_transfer(user_id: str, amount: float, risk_score: int,
                              block_reason: str, transaction_ref: str = None,
                              source_ip: str = None):
    """Persist a blocked transfer to titan_systemdb.blocked_transfers."""
    conn = get_conn()
    if not conn:
        return
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO blocked_transfers
                    (user_id, amount, risk_score, block_reason, transaction_ref, source_ip)
                VALUES (%s, %s, %s, %s, %s, %s)
                """,
                (user_id, amount, risk_score, block_reason, transaction_ref, source_ip)
            )
        conn.commit()
    except Exception as e:
        logger.error(f"persist_blocked_transfer error: {e}")
        try:
            conn.rollback()
        except Exception:
            pass
    finally:
        release_conn(conn)


def persist_system_log(service: str, level: str, category: str, message: str, details: dict = None):
    """Write an entry to titan_systemdb.system_logs."""
    import json
    conn = get_conn()
    if not conn:
        return
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO system_logs (service, level, category, message, details)
                VALUES (%s, %s, %s, %s, %s)
                """,
                (service, level, category, message,
                 json.dumps(details) if details else None)
            )
        conn.commit()
    except Exception as e:
        logger.error(f"persist_system_log error: {e}")
        try:
            conn.rollback()
        except Exception:
            pass
    finally:
        release_conn(conn)


# ─── gRPC Service ─────────────────────────────────────────────────────────────
class RiskService(pb2_grpc.RiskEngineServiceServicer):

    def CheckRisk(self, request, context):
        try:
            if not self._validate_request(request, context):
                return pb2.RiskCheckResponse()

            logger.info(f"📡 Analyzing risk | User: {request.user_id} | Amount: ${request.amount:,.2f}")

            risk_score, risk_level, action = self._calculate_risk(request)

            # Log the decision
            icon = "✅" if action == "ALLOW" else "⚠️" if action == "REVIEW" else "🚫"
            logger.info(f"{icon} Decision | Score: {risk_score} | Level: {risk_level} | Action: {action}")

            # Persist asynchronously (non-blocking)
            t = threading.Thread(
                target=self._persist_async,
                args=(request, risk_score, risk_level, action),
                daemon=True
            )
            t.start()

            return pb2.RiskCheckResponse(
                risk_score=risk_score,
                risk_level=risk_level,
                action=action
            )

        except ValueError as e:
            logger.warning(f"Validation error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(str(e))
            return pb2.RiskCheckResponse()

        except TypeError as e:
            logger.warning(f"Type error: {e}")
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details(f"Invalid input type: {str(e)}")
            return pb2.RiskCheckResponse()

        except Exception as e:
            logger.error(f"Unexpected error in CheckRisk: {e}", exc_info=True)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details("Internal server error")
            return pb2.RiskCheckResponse()

    def _persist_async(self, request, risk_score, risk_level, action):
        """Non-blocking DB write so gRPC response is never delayed."""
        try:
            persist_risk_event(
                user_id=request.user_id,
                amount=request.amount,
                risk_score=risk_score,
                risk_level=risk_level,
                action=action
            )
            if action == "BLOCK":
                persist_blocked_transfer(
                    user_id=request.user_id,
                    amount=request.amount,
                    risk_score=risk_score,
                    block_reason=(
                        f"High-value transfer blocked: ${request.amount:,.2f} "
                        f">= threshold ${RISK_BLOCK_THRESHOLD:,.0f}"
                    )
                )
                persist_system_log(
                    service="titan-ai-service",
                    level="WARN",
                    category="TRANSFER",
                    message=f"HIGH-VALUE TRANSFER BLOCKED: user={request.user_id} amount=${request.amount:,.2f}",
                    details={"user_id": request.user_id, "amount": request.amount, "threshold": RISK_BLOCK_THRESHOLD}
                )
        except Exception as e:
            logger.error(f"Async persist failed: {e}")

    def _validate_request(self, request, context):
        """Validate incoming gRPC request fields."""
        if not request.user_id or request.user_id.strip() == "":
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("user_id is required and cannot be empty")
            return False
        if request.amount <= 0:
            context.set_code(grpc.StatusCode.INVALID_ARGUMENT)
            context.set_details("amount must be positive")
            return False
        return True

    def _calculate_risk(self, request):
        """
        Risk scoring rules (updated — high-value block at $10,000):

        Amount               | Score | Level   | Action
        ---------------------|-------|---------|--------
        < $1,000             |  10   | LOW     | ALLOW
        $1,000 – $9,999      |  50   | MEDIUM  | REVIEW
        >= $10,000           | 100   | BLOCKED | BLOCK  ⛔
        """
        amount = request.amount

        if amount < RISK_LOW_MAX_AMOUNT:
            return RISK_SCORE_LOW, "LOW", "ALLOW"

        elif amount < RISK_BLOCK_THRESHOLD:
            return RISK_SCORE_MEDIUM, "MEDIUM", "REVIEW"

        else:
            # Amount >= $10,000 → BLOCK
            logger.warning(
                f"🚫 HIGH-VALUE BLOCK | User: {request.user_id} | "
                f"Amount: ${amount:,.2f} >= threshold ${RISK_BLOCK_THRESHOLD:,.0f}"
            )
            return RISK_SCORE_BLOCKED, "BLOCKED", "BLOCK"


# ─── HTTP API (FastAPI) ────────────────────────────────────────────────────────
app = FastAPI(
    title="Titan AI Risk Engine — Report API",
    description="HTTP report endpoints for risk events, blocked transfers, and statistics",
    version="2.0.0",
    docs_url="/docs",
    redoc_url="/redoc"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Pydantic response models ──────────────────────────────────────────────────
class HealthResponse(BaseModel):
    status: str
    service: str
    version: str
    grpc_port: int
    http_port: int
    db_connected: bool
    timestamp: str


class RiskEventItem(BaseModel):
    id: int
    user_id: str
    amount: float
    risk_score: int
    risk_level: str
    action: str
    transaction_ref: Optional[str]
    evaluated_at: str


class BlockedTransferItem(BaseModel):
    id: int
    user_id: str
    amount: float
    risk_score: int
    block_reason: str
    review_status: str
    transaction_ref: Optional[str]
    blocked_at: str
    reviewed_at: Optional[str]


class RiskStats(BaseModel):
    total_evaluations: int
    allowed_count: int
    review_count: int
    blocked_count: int
    total_amount_evaluated: float
    total_amount_blocked: float
    avg_risk_score: float
    block_rate_pct: float
    period_from: str
    period_to: str


class DailySummaryItem(BaseModel):
    report_date: str
    total_transactions: int
    total_amount: float
    allowed_count: int
    review_count: int
    blocked_count: int
    avg_risk_score: float
    unique_users: int


# ── Helper ────────────────────────────────────────────────────────────────────
def _db_ok() -> bool:
    conn = get_conn()
    if not conn:
        return False
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        return True
    except Exception:
        return False
    finally:
        release_conn(conn)


# ── Endpoints ─────────────────────────────────────────────────────────────────
@app.get("/health", response_model=HealthResponse, tags=["System"])
def health():
    return {
        "status": "ok",
        "service": "titan-ai-service",
        "version": "2.0.0",
        "grpc_port": GRPC_PORT,
        "http_port": HTTP_PORT,
        "db_connected": _db_ok(),
        "timestamp": datetime.utcnow().isoformat() + "Z"
    }


@app.get("/api/reports/risk", tags=["Reports"])
def get_risk_events(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=20, ge=1, le=100),
    user_id: Optional[str] = None,
    action: Optional[str] = None,
    level: Optional[str] = None,
):
    """Return paginated list of all risk evaluations."""
    conn = get_conn()
    if not conn:
        raise HTTPException(status_code=503, detail="Database unavailable")
    try:
        offset = (page - 1) * size
        filters = []
        params = []
        if user_id:
            filters.append("user_id = %s")
            params.append(user_id)
        if action:
            filters.append("action = %s")
            params.append(action.upper())
        if level:
            filters.append("risk_level = %s")
            params.append(level.upper())

        where = ("WHERE " + " AND ".join(filters)) if filters else ""

        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) as total FROM risk_events {where}", params)
            total = cur.fetchone()["total"]

            cur.execute(
                f"""
                SELECT id, user_id, amount, risk_score, risk_level, action,
                       transaction_ref, evaluated_at
                FROM risk_events {where}
                ORDER BY evaluated_at DESC
                LIMIT %s OFFSET %s
                """,
                params + [size, offset]
            )
            rows = cur.fetchall()

        return {
            "page": page,
            "size": size,
            "total": total,
            "pages": (total + size - 1) // size if total else 0,
            "items": [
                {**row, "evaluated_at": row["evaluated_at"].isoformat()}
                for row in rows
            ]
        }
    except Exception as e:
        logger.error(f"/api/reports/risk error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        release_conn(conn)


@app.get("/api/reports/blocked", tags=["Reports"])
def get_blocked_transfers(
    page: int = Query(default=1, ge=1),
    size: int = Query(default=20, ge=1, le=100),
    user_id: Optional[str] = None,
    review_status: Optional[str] = None,
):
    """Return paginated blocked transfer audit log."""
    conn = get_conn()
    if not conn:
        raise HTTPException(status_code=503, detail="Database unavailable")
    try:
        offset = (page - 1) * size
        filters = []
        params = []
        if user_id:
            filters.append("user_id = %s")
            params.append(user_id)
        if review_status:
            filters.append("review_status = %s")
            params.append(review_status.upper())

        where = ("WHERE " + " AND ".join(filters)) if filters else ""

        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) as total FROM blocked_transfers {where}", params)
            total = cur.fetchone()["total"]

            cur.execute(
                f"""
                SELECT id, user_id, amount, currency, risk_score, block_reason,
                       review_status, transaction_ref, source_ip,
                       reviewed_by, review_notes, blocked_at, reviewed_at
                FROM blocked_transfers {where}
                ORDER BY blocked_at DESC
                LIMIT %s OFFSET %s
                """,
                params + [size, offset]
            )
            rows = cur.fetchall()

        def _fmt(row):
            r = dict(row)
            r["blocked_at"] = r["blocked_at"].isoformat()
            r["reviewed_at"] = r["reviewed_at"].isoformat() if r["reviewed_at"] else None
            return r

        return {
            "page": page,
            "size": size,
            "total": total,
            "pages": (total + size - 1) // size if total else 0,
            "items": [_fmt(r) for r in rows]
        }
    except Exception as e:
        logger.error(f"/api/reports/blocked error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        release_conn(conn)


@app.get("/api/reports/stats", response_model=RiskStats, tags=["Reports"])
def get_stats(
    from_date: Optional[str] = Query(default=None, description="ISO date, e.g. 2026-08-01"),
    to_date: Optional[str] = Query(default=None, description="ISO date, e.g. 2026-08-29"),
):
    """Return aggregated risk statistics for a date range."""
    conn = get_conn()
    if not conn:
        raise HTTPException(status_code=503, detail="Database unavailable")
    try:
        now = datetime.utcnow()
        dt_from = datetime.fromisoformat(from_date) if from_date else (now - timedelta(days=30))
        dt_to   = datetime.fromisoformat(to_date)   if to_date   else now

        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(
                """
                SELECT
                    COUNT(*)                                        AS total_evaluations,
                    COALESCE(SUM(CASE WHEN action='ALLOW'  THEN 1 ELSE 0 END), 0) AS allowed_count,
                    COALESCE(SUM(CASE WHEN action='REVIEW' THEN 1 ELSE 0 END), 0) AS review_count,
                    COALESCE(SUM(CASE WHEN action='BLOCK'  THEN 1 ELSE 0 END), 0) AS blocked_count,
                    COALESCE(SUM(amount), 0)                        AS total_amount_evaluated,
                    COALESCE(SUM(CASE WHEN action='BLOCK' THEN amount ELSE 0 END), 0) AS total_amount_blocked,
                    COALESCE(ROUND(AVG(risk_score)::NUMERIC, 2), 0) AS avg_risk_score
                FROM risk_events
                WHERE evaluated_at BETWEEN %s AND %s
                """,
                (dt_from, dt_to)
            )
            row = cur.fetchone()

        total = row["total_evaluations"] or 0
        blocked = row["blocked_count"] or 0

        return {
            "total_evaluations": total,
            "allowed_count": int(row["allowed_count"]),
            "review_count": int(row["review_count"]),
            "blocked_count": int(blocked),
            "total_amount_evaluated": float(row["total_amount_evaluated"]),
            "total_amount_blocked": float(row["total_amount_blocked"]),
            "avg_risk_score": float(row["avg_risk_score"]),
            "block_rate_pct": round((blocked / total * 100), 2) if total > 0 else 0.0,
            "period_from": dt_from.isoformat(),
            "period_to": dt_to.isoformat()
        }
    except Exception as e:
        logger.error(f"/api/reports/stats error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        release_conn(conn)


@app.get("/api/reports/stats/daily", tags=["Reports"])
def get_daily_stats(days: int = Query(default=7, ge=1, le=90)):
    """Return per-day breakdown for the last N days."""
    conn = get_conn()
    if not conn:
        raise HTTPException(status_code=503, detail="Database unavailable")
    try:
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(
                """
                SELECT
                    DATE(evaluated_at)                             AS report_date,
                    COUNT(*)                                       AS total_transactions,
                    COALESCE(SUM(amount), 0)                       AS total_amount,
                    SUM(CASE WHEN action='ALLOW'  THEN 1 ELSE 0 END) AS allowed_count,
                    SUM(CASE WHEN action='REVIEW' THEN 1 ELSE 0 END) AS review_count,
                    SUM(CASE WHEN action='BLOCK'  THEN 1 ELSE 0 END) AS blocked_count,
                    ROUND(AVG(risk_score)::NUMERIC, 2)             AS avg_risk_score,
                    COUNT(DISTINCT user_id)                        AS unique_users
                FROM risk_events
                WHERE evaluated_at >= NOW() - (%s || ' days')::INTERVAL
                GROUP BY DATE(evaluated_at)
                ORDER BY report_date DESC
                """,
                (days,)
            )
            rows = cur.fetchall()

        return {
            "days": days,
            "items": [
                {
                    **{k: v for k, v in row.items() if k != "report_date"},
                    "report_date": row["report_date"].isoformat(),
                    "total_amount": float(row["total_amount"]),
                    "avg_risk_score": float(row["avg_risk_score"])
                }
                for row in rows
            ]
        }
    except Exception as e:
        logger.error(f"/api/reports/stats/daily error: {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        release_conn(conn)


@app.post("/api/reports/generate", tags=["Reports"])
def generate_report(background_tasks: BackgroundTasks):
    """Trigger a manual transfer_reports aggregation job."""
    background_tasks.add_task(_run_report_aggregation)
    return {"status": "queued", "message": "Report aggregation started in background"}


def _run_report_aggregation():
    """Aggregate today's data into transfer_reports table."""
    conn = get_conn()
    if not conn:
        logger.warning("Report aggregation skipped — no DB connection")
        return
    try:
        today = date.today().isoformat()
        with conn.cursor(cursor_factory=RealDictCursor) as cur:
            cur.execute(
                """
                SELECT
                    COUNT(*)                              AS total_transactions,
                    COALESCE(SUM(amount), 0)              AS total_amount,
                    SUM(CASE WHEN action='ALLOW'  THEN 1 ELSE 0 END) AS allowed_count,
                    SUM(CASE WHEN action='REVIEW' THEN 1 ELSE 0 END) AS review_count,
                    SUM(CASE WHEN action='BLOCK'  THEN 1 ELSE 0 END) AS blocked_count,
                    ROUND(AVG(risk_score)::NUMERIC, 2)    AS avg_risk_score,
                    COALESCE(MAX(amount), 0)              AS max_amount,
                    COALESCE(MIN(amount), 0)              AS min_amount,
                    COUNT(DISTINCT user_id)               AS unique_users
                FROM risk_events
                WHERE DATE(evaluated_at) = %s
                """,
                (today,)
            )
            row = cur.fetchone()

            cur.execute(
                """
                INSERT INTO transfer_reports
                    (report_period, period_type, total_transactions, total_amount,
                     allowed_count, review_count, blocked_count, avg_risk_score,
                     max_amount, min_amount, unique_users)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (report_period, period_type) DO UPDATE SET
                    total_transactions = EXCLUDED.total_transactions,
                    total_amount       = EXCLUDED.total_amount,
                    allowed_count      = EXCLUDED.allowed_count,
                    review_count       = EXCLUDED.review_count,
                    blocked_count      = EXCLUDED.blocked_count,
                    avg_risk_score     = EXCLUDED.avg_risk_score,
                    max_amount         = EXCLUDED.max_amount,
                    min_amount         = EXCLUDED.min_amount,
                    unique_users       = EXCLUDED.unique_users,
                    generated_at       = NOW()
                """,
                (today, "DAILY",
                 row["total_transactions"], float(row["total_amount"]),
                 row["allowed_count"], row["review_count"], row["blocked_count"],
                 float(row["avg_risk_score"] or 0),
                 float(row["max_amount"]), float(row["min_amount"]),
                 row["unique_users"])
            )
        conn.commit()
        logger.info(f"✅ Transfer report generated for {today}")
        persist_system_log(
            service="titan-ai-service",
            level="INFO",
            category="REPORT",
            message=f"Daily report generated for {today}",
            details={"date": today, "transactions": row["total_transactions"]}
        )
    except Exception as e:
        logger.error(f"Report aggregation error: {e}")
        try:
            conn.rollback()
        except Exception:
            pass
    finally:
        release_conn(conn)


# ─── gRPC Server ───────────────────────────────────────────────────────────────
def serve_grpc():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=MAX_WORKERS))
    pb2_grpc.add_RiskEngineServiceServicer_to_server(RiskService(), server)

    health_servicer = HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    health_servicer.set("RiskEngineService", health_pb2.HealthCheckResponse.SERVING)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

    listen_addr = f'[::]:{GRPC_PORT}'
    server.add_insecure_port(listen_addr)

    logger.info("=" * 60)
    logger.info("🤖 Titan AI Risk Engine v2.0 starting...")
    logger.info(f"   gRPC Port   : {GRPC_PORT}")
    logger.info(f"   HTTP Port   : {HTTP_PORT}")
    logger.info(f"   Workers     : {MAX_WORKERS}")
    logger.info("   Risk Rules  :")
    logger.info(f"     < ${RISK_LOW_MAX_AMOUNT:>10,.0f}  → LOW     / ALLOW")
    logger.info(f"     < ${RISK_BLOCK_THRESHOLD:>10,.0f}  → MEDIUM  / REVIEW")
    logger.info(f"    >= ${RISK_BLOCK_THRESHOLD:>10,.0f}  → BLOCKED / BLOCK  ⛔  (HIGH-VALUE)")
    logger.info(f"   DB Persist  : {'enabled → ' + DB_HOST + ':' + str(DB_PORT) + '/' + DB_NAME if DB_ENABLED else 'disabled'}")
    logger.info("=" * 60)

    server.start()
    server.wait_for_termination()


# ─── Entry Point ───────────────────────────────────────────────────────────────
if __name__ == '__main__':
    # Initialize DB connection pool
    init_db_pool()

    # Start gRPC in a background thread
    grpc_thread = threading.Thread(target=serve_grpc, daemon=True)
    grpc_thread.start()

    # Start FastAPI HTTP server in main thread
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=HTTP_PORT,
        log_level=LOG_LEVEL.lower(),
        access_log=True
    )
