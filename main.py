from fastapi import FastAPI, HTTPException, Depends, Header
from pydantic import BaseModel, Field
from typing import Optional, List, Tuple
import sqlite3
import hashlib
import uuid
import os
from datetime import datetime

# Database path (can be overridden with env var HAMCHAT_DB_PATH)
DB_PATH = os.environ.get("HAMCHAT_DB_PATH", "hamchat.db")


# ---------- Database helpers ----------


def get_db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def now_iso() -> str:
    return datetime.utcnow().isoformat() + "Z"


def hash_password(password: str) -> str:
    return hashlib.sha256(password.encode("utf-8")).hexdigest()


def normalize_phone(country_code: str, national: str) -> Tuple[str, str, str]:
    """Normalize a phone number to a simple E.164-like format.

    country_code examples: "+52", "52", "+1".
    national: local number, any non-digit chars are removed.
    Returns (normalized_country_code, normalized_national, phone_e164).
    """
    if not country_code:
        raise HTTPException(status_code=400, detail="phone_country_code is required")

    cc = country_code.strip()
    if not cc.startswith("+"):
        # remove leading '+' and zeros, then add '+'
        cc = "+" + cc.lstrip("+").lstrip("0")

    # very basic validation: '+' plus 1-4 digits
    if len(cc) < 2 or len(cc) > 5 or not cc[1:].isdigit():
        raise HTTPException(status_code=400, detail="invalid phone_country_code")

    digits = "".join(ch for ch in str(national) if ch.isdigit())
    if len(digits) < 4 or len(digits) > 15:
        raise HTTPException(status_code=400, detail="invalid phone_national length (4-15 digits)")

    phone_e164 = cc + digits
    if len(phone_e164) > 16:
        raise HTTPException(status_code=400, detail="phone too long")

    return cc, digits, phone_e164


def create_tables() -> None:
    conn = get_db()
    cur = conn.cursor()

    # Users table: global phone support
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS users (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            username TEXT NOT NULL UNIQUE,
            password_hash TEXT NOT NULL,
            phone_country_code TEXT NOT NULL,
            phone_national TEXT NOT NULL,
            phone_e164 TEXT NOT NULL UNIQUE,
            created_at TEXT NOT NULL
        );
        """
    )

    # Messages between users
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            sender_id INTEGER NOT NULL,
            recipient_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            FOREIGN KEY(sender_id) REFERENCES users(id),
            FOREIGN KEY(recipient_id) REFERENCES users(id)
        );
        """
    )

    # Contacts (server-side address book)
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS contacts (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            owner_user_id INTEGER NOT NULL,
            contact_user_id INTEGER NOT NULL,
            alias TEXT,
            created_at TEXT NOT NULL,
            UNIQUE(owner_user_id, contact_user_id),
            FOREIGN KEY(owner_user_id) REFERENCES users(id),
            FOREIGN KEY(contact_user_id) REFERENCES users(id)
        );
        """
    )

    # Auth tokens for simple token-based auth
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS auth_tokens (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            token TEXT NOT NULL UNIQUE,
            created_at TEXT NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        """
    )

    # Ladas (country calling codes stored globally)
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS ladas (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            code TEXT NOT NULL UNIQUE,
            label TEXT,
            created_at TEXT NOT NULL
        );
        """
    )

    conn.commit()
    conn.close()


# ---------- FastAPI app ----------


app = FastAPI(title="HamChat Server", version="1.0.0")
create_tables()


# ---------- Pydantic models ----------


class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    password: str = Field(..., min_length=4, max_length=128)
    phone_country_code: str
    phone_national: str


class UserResponse(BaseModel):
    id: int
    username: str
    phone_country_code: str
    phone_national: str
    phone_e164: str


class LoginRequest(BaseModel):
    username: str
    password: str


class LoginResponse(BaseModel):
    user_id: int
    token: str


class SendMessageRequest(BaseModel):
    recipient_id: int
    content: str = Field(..., min_length=1, max_length=1000)


class MessageResponse(BaseModel):
    id: int
    sender_id: int
    recipient_id: int
    content: str
    created_at: str


class ContactCreateRequest(BaseModel):
    contact_user_id: int
    alias: Optional[str] = None


class ContactResponse(BaseModel):
    id: int
    contact_user_id: int
    alias: Optional[str]
    username: str
    phone_e164: str


class UserSearchResponse(BaseModel):
    id: int
    username: str
    phone_e164: str


class LadaCreateRequest(BaseModel):
    code: str
    label: Optional[str] = None


class LadaResponse(BaseModel):
    id: int
    code: str
    label: Optional[str]
    created_at: str


class InboxItemResponse(BaseModel):
    with_user_id: int
    username: str
    phone_e164: str
    last_message: str
    last_message_at: str
    last_message_id: int


# ---------- Auth utilities ----------


def get_user_id_from_token(authorization: str = Header(..., alias="Authorization")) -> int:
    """Extract user_id from a Bearer token stored in auth_tokens table."""
    if not authorization:
        raise HTTPException(status_code=401, detail="Authorization header is required")

    parts = authorization.split(" ", 1)
    if len(parts) != 2 or parts[0].lower() != "bearer":
        raise HTTPException(status_code=401, detail="Invalid Authorization format")

    token = parts[1].strip()
    if not token:
        raise HTTPException(status_code=401, detail="Empty token")

    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT user_id FROM auth_tokens WHERE token = ?", (token,))
    row = cur.fetchone()
    conn.close()

    if row is None:
        raise HTTPException(status_code=401, detail="Invalid token")

    return int(row["user_id"])


# ---------- Endpoints ----------


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/api/register", response_model=UserResponse)
def register(req: RegisterRequest) -> UserResponse:
    cc, nat, e164 = normalize_phone(req.phone_country_code, req.phone_national)

    conn = get_db()
    cur = conn.cursor()
    try:
        cur.execute(
            """
            INSERT INTO users (username, password_hash, phone_country_code, phone_national, phone_e164, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                req.username,
                hash_password(req.password),
                cc,
                nat,
                e164,
                now_iso(),
            ),
        )
        conn.commit()
        user_id = cur.lastrowid
    except sqlite3.IntegrityError as e:
        conn.close()
        msg = str(e)
        if "UNIQUE constraint failed: users.username" in msg:
            raise HTTPException(status_code=400, detail="username already exists")
        if "UNIQUE constraint failed: users.phone_e164" in msg:
            raise HTTPException(status_code=400, detail="phone already registered")
        raise

    conn.close()
    return UserResponse(
        id=user_id,
        username=req.username,
        phone_country_code=cc,
        phone_national=nat,
        phone_e164=e164,
    )


@app.post("/api/login", response_model=LoginResponse)
def login(req: LoginRequest) -> LoginResponse:
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id, password_hash FROM users WHERE username = ?", (req.username,))
    row = cur.fetchone()

    if row is None:
        conn.close()
        raise HTTPException(status_code=401, detail="invalid credentials")

    user_id = int(row["id"])
    stored_hash = row["password_hash"]
    if stored_hash != hash_password(req.password):
        conn.close()
        raise HTTPException(status_code=401, detail="invalid credentials")

    token = str(uuid.uuid4())
    cur.execute(
        "INSERT INTO auth_tokens (user_id, token, created_at) VALUES (?, ?, ?)",
        (user_id, token, now_iso()),
    )
    conn.commit()
    conn.close()

    return LoginResponse(user_id=user_id, token=token)


@app.get("/api/users/by-username/{username}", response_model=UserSearchResponse)
def get_user_by_username(username: str) -> UserSearchResponse:
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id, username, phone_e164 FROM users WHERE username = ?", (username,))
    row = cur.fetchone()
    conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="user not found")

    return UserSearchResponse(
        id=int(row["id"]),
        username=row["username"],
        phone_e164=row["phone_e164"],
    )


@app.get("/api/inbox", response_model=List[InboxItemResponse])
def inbox(current_user_id: int = Depends(get_user_id_from_token)) -> List[InboxItemResponse]:
    """Return the last message of each conversation for the current user.

    This is used by the Android client to detect new incoming messages
    without having to poll the full message history.
    """
    conn = get_db()
    cur = conn.cursor()

    # Subquery to get the last message id per other user
    cur.execute(
        """
        SELECT MAX(id) AS max_id
        FROM (
            SELECT id,
                   CASE WHEN sender_id = ? THEN recipient_id ELSE sender_id END AS other_user_id
            FROM messages
            WHERE sender_id = ? OR recipient_id = ?
        )
        GROUP BY other_user_id
        """,
        (current_user_id, current_user_id, current_user_id),
    )
    ids_rows = cur.fetchall()
    if not ids_rows:
        conn.close()
        return []

    last_ids = [int(r["max_id"]) for r in ids_rows]

    # Now fetch full info for those messages and their counterpart users
    placeholders = ",".join("?" for _ in last_ids)
    query = f"""
        SELECT m.id, m.sender_id, m.recipient_id, m.content, m.created_at,
               u.id AS other_id, u.username, u.phone_e164
        FROM messages m
        JOIN users u ON u.id = CASE WHEN m.sender_id = ? THEN m.recipient_id ELSE m.sender_id END
        WHERE m.id IN ({placeholders})
        ORDER BY m.id DESC
    """

    cur.execute(query, (current_user_id, *last_ids))
    rows = cur.fetchall()
    conn.close()

    result: List[InboxItemResponse] = []
    for r in rows:
        result.append(
            InboxItemResponse(
                with_user_id=int(r["other_id"]),
                username=r["username"],
                phone_e164=r["phone_e164"],
                last_message=r["content"],
                last_message_at=r["created_at"],
                last_message_id=int(r["id"]),
            )
        )

    return result


@app.get("/api/ladas", response_model=List[LadaResponse])
def list_ladas() -> List[LadaResponse]:
    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id, code, label, created_at FROM ladas ORDER BY code")
    rows = cur.fetchall()
    conn.close()

    result: List[LadaResponse] = []
    for r in rows:
        result.append(
            LadaResponse(
                id=int(r["id"]),
                code=r["code"],
                label=r["label"],
                created_at=r["created_at"],
            )
        )
    return result


@app.post("/api/ladas", response_model=LadaResponse)
def add_lada(req: LadaCreateRequest) -> LadaResponse:
    code = req.code.strip()
    if not code:
        raise HTTPException(status_code=400, detail="code is required")

    if not code.startswith("+"):
        code = "+" + code.lstrip("+").lstrip("0")

    if len(code) < 2 or len(code) > 5 or not code[1:].isdigit():
        raise HTTPException(status_code=400, detail="invalid lada code")

    label = req.label.strip() if req.label else None

    conn = get_db()
    cur = conn.cursor()
    try:
        cur.execute(
            "INSERT INTO ladas (code, label, created_at) VALUES (?, ?, ?)",
            (code, label, now_iso()),
        )
        conn.commit()
        lada_id = cur.lastrowid
        cur.execute("SELECT id, code, label, created_at FROM ladas WHERE id = ?", (lada_id,))
        row = cur.fetchone()
    except sqlite3.IntegrityError:
        # If code already exists, return existing row
        cur.execute("SELECT id, code, label, created_at FROM ladas WHERE code = ?", (code,))
        row = cur.fetchone()
        if row is None:
            conn.close()
            raise HTTPException(status_code=400, detail="lada already exists")
    finally:
        conn.close()

    return LadaResponse(
        id=int(row["id"]),
        code=row["code"],
        label=row["label"],
        created_at=row["created_at"],
    )


@app.get("/api/users/by-phone", response_model=UserSearchResponse)
def get_user_by_phone(phone_country_code: str, phone_national: str) -> UserSearchResponse:
    cc, nat, e164 = normalize_phone(phone_country_code, phone_national)

    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id, username, phone_e164 FROM users WHERE phone_e164 = ?", (e164,))
    row = cur.fetchone()
    conn.close()

    if row is None:
        raise HTTPException(status_code=404, detail="user not found")

    return UserSearchResponse(
        id=int(row["id"]),
        username=row["username"],
        phone_e164=row["phone_e164"],
    )


@app.post("/api/messages", response_model=MessageResponse)
def send_message(req: SendMessageRequest, current_user_id: int = Depends(get_user_id_from_token)) -> MessageResponse:
    conn = get_db()
    cur = conn.cursor()

    # Ensure recipient exists
    cur.execute("SELECT id FROM users WHERE id = ?", (req.recipient_id,))
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=404, detail="recipient not found")

    created_at = now_iso()
    cur.execute(
        """
        INSERT INTO messages (sender_id, recipient_id, content, created_at)
        VALUES (?, ?, ?, ?)
        """,
        (current_user_id, req.recipient_id, req.content, created_at),
    )
    conn.commit()
    msg_id = cur.lastrowid
    conn.close()

    return MessageResponse(
        id=msg_id,
        sender_id=current_user_id,
        recipient_id=req.recipient_id,
        content=req.content,
        created_at=created_at,
    )


@app.get("/api/messages", response_model=List[MessageResponse])
def get_messages(with_user_id: int, limit: int = 50, current_user_id: int = Depends(get_user_id_from_token)) -> List[MessageResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT id, sender_id, recipient_id, content, created_at
        FROM messages
        WHERE (sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)
        ORDER BY id DESC
        LIMIT ?
        """,
        (current_user_id, with_user_id, with_user_id, current_user_id, limit),
    )
    rows = cur.fetchall()
    conn.close()

    result: List[MessageResponse] = []
    for r in reversed(rows):
        result.append(
            MessageResponse(
                id=int(r["id"]),
                sender_id=int(r["sender_id"]),
                recipient_id=int(r["recipient_id"]),
                content=r["content"],
                created_at=r["created_at"],
            )
        )
    return result


@app.get("/api/messages/since", response_model=List[MessageResponse])
def get_messages_since(with_user_id: int, since_id: int = 0, current_user_id: int = Depends(get_user_id_from_token)) -> List[MessageResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT id, sender_id, recipient_id, content, created_at
        FROM messages
        WHERE id > ?
          AND ((sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?))
        ORDER BY id ASC
        """,
        (since_id, current_user_id, with_user_id, with_user_id, current_user_id),
    )
    rows = cur.fetchall()
    conn.close()

    result: List[MessageResponse] = []
    for r in rows:
        result.append(
            MessageResponse(
                id=int(r["id"]),
                sender_id=int(r["sender_id"]),
                recipient_id=int(r["recipient_id"]),
                content=r["content"],
                created_at=r["created_at"],
            )
        )
    return result


@app.get("/api/contacts", response_model=List[ContactResponse])
def list_contacts(current_user_id: int = Depends(get_user_id_from_token)) -> List[ContactResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT c.id, c.contact_user_id, c.alias, u.username, u.phone_e164
        FROM contacts c
        JOIN users u ON u.id = c.contact_user_id
        WHERE c.owner_user_id = ?
        ORDER BY u.username
        """,
        (current_user_id,),
    )
    rows = cur.fetchall()
    conn.close()

    result: List[ContactResponse] = []
    for r in rows:
        result.append(
            ContactResponse(
                id=int(r["id"]),
                contact_user_id=int(r["contact_user_id"]),
                alias=r["alias"],
                username=r["username"],
                phone_e164=r["phone_e164"],
            )
        )
    return result


@app.post("/api/contacts", response_model=ContactResponse)
def add_contact(req: ContactCreateRequest, current_user_id: int = Depends(get_user_id_from_token)) -> ContactResponse:
    if req.contact_user_id == current_user_id:
        raise HTTPException(status_code=400, detail="cannot add yourself")

    conn = get_db()
    cur = conn.cursor()

    # Ensure user to add exists
    cur.execute("SELECT id, username, phone_e164 FROM users WHERE id = ?", (req.contact_user_id,))
    user_row = cur.fetchone()
    if user_row is None:
        conn.close()
        raise HTTPException(status_code=404, detail="user not found")

    try:
        cur.execute(
            """
            INSERT INTO contacts (owner_user_id, contact_user_id, alias, created_at)
            VALUES (?, ?, ?, ?)
            """,
            (current_user_id, req.contact_user_id, req.alias, now_iso()),
        )
        conn.commit()
        contact_id = cur.lastrowid
    except sqlite3.IntegrityError:
        conn.close()
        raise HTTPException(status_code=400, detail="contact already exists")

    conn.close()
    return ContactResponse(
        id=contact_id,
        contact_user_id=req.contact_user_id,
        alias=req.alias,
        username=user_row["username"],
        phone_e164=user_row["phone_e164"],
    )


@app.delete("/api/contacts/{contact_user_id}")
def delete_contact(contact_user_id: int, current_user_id: int = Depends(get_user_id_from_token)) -> dict:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        "DELETE FROM contacts WHERE owner_user_id = ? AND contact_user_id = ?",
        (current_user_id, contact_user_id),
    )
    deleted = cur.rowcount
    conn.commit()
    conn.close()

    if deleted == 0:
        raise HTTPException(status_code=404, detail="contact not found")

    return {"status": "ok"}


# ---------- Admin endpoints ----------

ADMIN_SECRET = os.environ.get("HAMCHAT_ADMIN_SECRET", "hamchat-reset-2024")


@app.post("/api/admin/reset-db")
def reset_database(secret: str) -> dict:
    """Limpiar todas las tablas de la base de datos. Requiere clave secreta."""
    if secret != ADMIN_SECRET:
        raise HTTPException(status_code=403, detail="Invalid secret")

    conn = get_db()
    cur = conn.cursor()
    cur.execute("DELETE FROM auth_tokens")
    cur.execute("DELETE FROM messages")
    cur.execute("DELETE FROM contacts")
    cur.execute("DELETE FROM users")
    cur.execute("DELETE FROM ladas")
    conn.commit()
    conn.close()

    return {"status": "ok", "message": "Database reset complete"}


@app.get("/api/admin/users")
def list_users(secret: str) -> list:
    """Listar todos los usuarios registrados. Requiere clave secreta."""
    if secret != ADMIN_SECRET:
        raise HTTPException(status_code=403, detail="Invalid secret")

    conn = get_db()
    cur = conn.cursor()
    cur.execute("SELECT id, username, phone_e164, created_at FROM users")
    rows = cur.fetchall()
    conn.close()

    return [dict(row) for row in rows]


# To run locally:
#   uvicorn main:app --reload
