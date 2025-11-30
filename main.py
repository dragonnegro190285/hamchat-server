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
            sent_at TEXT NOT NULL,
            received_at TEXT,
            is_delivered INTEGER DEFAULT 0,
            local_id TEXT,
            FOREIGN KEY(sender_id) REFERENCES users(id),
            FOREIGN KEY(recipient_id) REFERENCES users(id)
        );
        """
    )
    
    # Migrar tabla existente si falta columnas
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN sent_at TEXT")
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN received_at TEXT")
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN is_delivered INTEGER DEFAULT 0")
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN local_id TEXT")
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN message_type TEXT DEFAULT 'text'")
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN audio_data TEXT")  # Base64 encoded audio
    except:
        pass
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN audio_duration INTEGER DEFAULT 0")  # Duration in seconds
    except:
        pass
    # Actualizar registros existentes
    cur.execute("UPDATE messages SET sent_at = created_at WHERE sent_at IS NULL")
    cur.execute("UPDATE messages SET message_type = 'text' WHERE message_type IS NULL")

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

    # Grupos de chat
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS groups (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            description TEXT,
            creator_id INTEGER NOT NULL,
            avatar_url TEXT,
            created_at TEXT NOT NULL,
            FOREIGN KEY(creator_id) REFERENCES users(id)
        );
        """
    )

    # Miembros de grupos
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS group_members (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            group_id INTEGER NOT NULL,
            user_id INTEGER NOT NULL,
            role TEXT DEFAULT 'member',
            joined_at TEXT NOT NULL,
            UNIQUE(group_id, user_id),
            FOREIGN KEY(group_id) REFERENCES groups(id),
            FOREIGN KEY(user_id) REFERENCES users(id)
        );
        """
    )

    # Mensajes de grupo
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS group_messages (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            group_id INTEGER NOT NULL,
            sender_id INTEGER NOT NULL,
            content TEXT NOT NULL,
            created_at TEXT NOT NULL,
            sent_at TEXT NOT NULL,
            local_id TEXT,
            FOREIGN KEY(group_id) REFERENCES groups(id),
            FOREIGN KEY(sender_id) REFERENCES users(id)
        );
        """
    )

    conn.commit()
    conn.close()
    
    # Crear usuarios de prueba predefinidos
    create_test_users()


# Números de prueba predefinidos
TEST_USERS = [
    {"username": "alvaro puebla", "password": "test123", "country_code": "+52", "national": "2228165690"},
    {"username": "alvaro tulancingo", "password": "test123", "country_code": "+52", "national": "7753574534"},
]


def create_test_users() -> None:
    """Crea usuarios de prueba si no existen"""
    conn = get_db()
    cur = conn.cursor()
    
    for user in TEST_USERS:
        phone_e164 = user["country_code"] + user["national"]
        # Verificar si ya existe
        cur.execute("SELECT id FROM users WHERE phone_e164 = ?", (phone_e164,))
        if cur.fetchone() is None:
            # Crear usuario de prueba
            cur.execute(
                """
                INSERT INTO users (username, password_hash, phone_country_code, phone_national, phone_e164, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    user["username"],
                    hash_password(user["password"]),
                    user["country_code"],
                    user["national"],
                    phone_e164,
                    now_iso(),
                ),
            )
            print(f"✅ Usuario de prueba creado: {phone_e164}")
    
    conn.commit()
    conn.close()


# ---------- FastAPI app ----------


app = FastAPI(title="HamChat Server", version="1.0.0")
create_tables()


# ---------- Pydantic models ----------


class RegisterRequest(BaseModel):
    username: str = Field(..., min_length=3, max_length=32)
    password: Optional[str] = None  # Ya no es requerido
    phone_country_code: str
    phone_national: str


class UserResponse(BaseModel):
    id: int
    username: str
    phone_country_code: str
    phone_national: str
    phone_e164: str


class LoginRequest(BaseModel):
    username: Optional[str] = None  # Opcional
    password: Optional[str] = None  # Ya no es requerido
    phone_country_code: Optional[str] = None  # Login por teléfono
    phone_national: Optional[str] = None  # Login por teléfono


class LoginResponse(BaseModel):
    user_id: int
    token: str


class SendMessageRequest(BaseModel):
    recipient_id: int
    content: str = Field(..., min_length=1, max_length=1000)
    local_id: Optional[str] = None  # ID local para evitar duplicados
    sent_at: Optional[str] = None   # Timestamp de envío del cliente
    message_type: str = "text"      # "text" o "voice"
    audio_data: Optional[str] = None  # Base64 encoded audio para mensajes de voz
    audio_duration: int = 0         # Duración en segundos


class MessageResponse(BaseModel):
    id: int
    sender_id: int
    recipient_id: int
    content: str
    created_at: str
    sent_at: Optional[str] = None
    received_at: Optional[str] = None
    is_delivered: bool = False
    local_id: Optional[str] = None
    message_type: str = "text"
    audio_data: Optional[str] = None
    audio_duration: int = 0


class MarkDeliveredRequest(BaseModel):
    message_ids: list[int]  # IDs de mensajes a marcar como entregados


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


# ---------- Modelos para Grupos ----------

class CreateGroupRequest(BaseModel):
    name: str = Field(..., min_length=1, max_length=100)
    description: Optional[str] = None
    member_ids: List[int] = []  # IDs de usuarios a agregar al grupo


class GroupResponse(BaseModel):
    id: int
    name: str
    description: Optional[str]
    creator_id: int
    created_at: str
    member_count: int


class GroupMemberResponse(BaseModel):
    user_id: int
    username: str
    phone_e164: str
    role: str
    joined_at: str


class GroupMessageRequest(BaseModel):
    group_id: int
    content: str = Field(..., min_length=1, max_length=1000)
    local_id: Optional[str] = None
    sent_at: Optional[str] = None


class GroupMessageResponse(BaseModel):
    id: int
    group_id: int
    sender_id: int
    sender_name: str
    content: str
    created_at: str
    sent_at: Optional[str] = None
    local_id: Optional[str] = None


class AddGroupMemberRequest(BaseModel):
    user_id: int


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
    
    # Números de prueba - si ya existen, devolver el usuario existente
    test_phones = ["+522228165690", "+527753574534"]
    
    # Verificar si el teléfono ya está registrado
    cur.execute("SELECT id, username, phone_country_code, phone_national, phone_e164 FROM users WHERE phone_e164 = ?", (e164,))
    existing = cur.fetchone()
    
    if existing:
        # Si ya existe, devolver el usuario existente (permite re-login)
        conn.close()
        print(f"📱 Usuario existente encontrado: {e164}")
        return UserResponse(
            id=int(existing["id"]),
            username=existing["username"],
            phone_country_code=existing["phone_country_code"],
            phone_national=existing["phone_national"],
            phone_e164=existing["phone_e164"],
        )
    
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
        print(f"✅ Nuevo usuario registrado: {e164}")
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
    row = None
    
    # Login por teléfono (prioridad)
    if req.phone_country_code and req.phone_national:
        cc, nat, e164 = normalize_phone(req.phone_country_code, req.phone_national)
        cur.execute("SELECT id, username FROM users WHERE phone_e164 = ?", (e164,))
        row = cur.fetchone()
        if row:
            print(f"📱 Login por teléfono: {e164}")
    
    # Login por username (fallback)
    if row is None and req.username:
        cur.execute("SELECT id, username FROM users WHERE username = ?", (req.username,))
        row = cur.fetchone()
        if row:
            print(f"👤 Login por username: {req.username}")

    if row is None:
        conn.close()
        raise HTTPException(status_code=401, detail="Usuario no encontrado. Regístrate primero.")

    user_id = int(row["id"])
    
    # Generar token sin verificar contraseña
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

    # Verificar si ya existe un mensaje con el mismo local_id (evitar duplicados)
    if req.local_id:
        cur.execute("SELECT id FROM messages WHERE local_id = ? AND sender_id = ?", (req.local_id, current_user_id))
        existing = cur.fetchone()
        if existing:
            # Ya existe, devolver el mensaje existente
            cur.execute(
                "SELECT id, sender_id, recipient_id, content, created_at, sent_at, received_at, is_delivered, local_id FROM messages WHERE id = ?",
                (existing["id"],)
            )
            row = cur.fetchone()
            conn.close()
            return MessageResponse(
                id=int(row["id"]),
                sender_id=int(row["sender_id"]),
                recipient_id=int(row["recipient_id"]),
                content=row["content"],
                created_at=row["created_at"],
                sent_at=row["sent_at"],
                received_at=row["received_at"],
                is_delivered=bool(row["is_delivered"]),
                local_id=row["local_id"],
            )

    created_at = now_iso()
    sent_at = req.sent_at or created_at  # Usar timestamp del cliente si existe
    
    cur.execute(
        """
        INSERT INTO messages (sender_id, recipient_id, content, created_at, sent_at, local_id, is_delivered, message_type, audio_data, audio_duration)
        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
        """,
        (current_user_id, req.recipient_id, req.content, created_at, sent_at, req.local_id, req.message_type, req.audio_data, req.audio_duration),
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
        sent_at=sent_at,
        is_delivered=False,
        local_id=req.local_id,
        message_type=req.message_type,
        audio_data=req.audio_data,
        audio_duration=req.audio_duration,
    )


@app.get("/api/messages", response_model=List[MessageResponse])
def get_messages(with_user_id: int, limit: int = 50, current_user_id: int = Depends(get_user_id_from_token)) -> List[MessageResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT id, sender_id, recipient_id, content, created_at, sent_at, received_at, is_delivered, local_id,
               message_type, audio_data, audio_duration
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
                sent_at=r["sent_at"],
                received_at=r["received_at"],
                is_delivered=bool(r["is_delivered"]) if r["is_delivered"] else False,
                local_id=r["local_id"],
                message_type=r["message_type"] or "text",
                audio_data=r["audio_data"],
                audio_duration=r["audio_duration"] or 0,
            )
        )
    return result


@app.get("/api/messages/since", response_model=List[MessageResponse])
def get_messages_since(with_user_id: int, since_id: int = 0, current_user_id: int = Depends(get_user_id_from_token)) -> List[MessageResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT id, sender_id, recipient_id, content, created_at, sent_at, received_at, is_delivered, local_id,
               message_type, audio_data, audio_duration
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
                sent_at=r["sent_at"],
                received_at=r["received_at"],
                is_delivered=bool(r["is_delivered"]) if r["is_delivered"] else False,
                local_id=r["local_id"],
                message_type=r["message_type"] or "text",
                audio_data=r["audio_data"],
                audio_duration=r["audio_duration"] or 0,
            )
        )
    return result


@app.post("/api/messages/mark-delivered")
def mark_messages_delivered(req: MarkDeliveredRequest, current_user_id: int = Depends(get_user_id_from_token)):
    """Marcar mensajes como entregados al receptor"""
    if not req.message_ids:
        return {"marked": 0}
    
    conn = get_db()
    cur = conn.cursor()
    
    received_at = now_iso()
    placeholders = ",".join("?" for _ in req.message_ids)
    
    # Solo marcar mensajes donde el usuario actual es el destinatario
    cur.execute(
        f"""
        UPDATE messages 
        SET is_delivered = 1, received_at = ?
        WHERE id IN ({placeholders}) AND recipient_id = ? AND is_delivered = 0
        """,
        (received_at, *req.message_ids, current_user_id),
    )
    conn.commit()
    marked = cur.rowcount
    conn.close()
    
    return {"marked": marked, "received_at": received_at}


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


# ---------- Endpoints de Grupos ----------

@app.post("/api/groups", response_model=GroupResponse)
def create_group(req: CreateGroupRequest, current_user_id: int = Depends(get_user_id_from_token)) -> GroupResponse:
    """Crear un nuevo grupo de chat"""
    conn = get_db()
    cur = conn.cursor()
    
    created_at = now_iso()
    
    # Crear el grupo
    cur.execute(
        """
        INSERT INTO groups (name, description, creator_id, created_at)
        VALUES (?, ?, ?, ?)
        """,
        (req.name, req.description, current_user_id, created_at),
    )
    group_id = cur.lastrowid
    
    # Agregar al creador como admin
    cur.execute(
        """
        INSERT INTO group_members (group_id, user_id, role, joined_at)
        VALUES (?, ?, 'admin', ?)
        """,
        (group_id, current_user_id, created_at),
    )
    
    # Agregar miembros adicionales
    for member_id in req.member_ids:
        if member_id != current_user_id:
            try:
                cur.execute(
                    """
                    INSERT INTO group_members (group_id, user_id, role, joined_at)
                    VALUES (?, ?, 'member', ?)
                    """,
                    (group_id, member_id, created_at),
                )
            except sqlite3.IntegrityError:
                pass  # Usuario ya está en el grupo o no existe
    
    conn.commit()
    
    # Contar miembros
    cur.execute("SELECT COUNT(*) FROM group_members WHERE group_id = ?", (group_id,))
    member_count = cur.fetchone()[0]
    
    conn.close()
    
    return GroupResponse(
        id=group_id,
        name=req.name,
        description=req.description,
        creator_id=current_user_id,
        created_at=created_at,
        member_count=member_count,
    )


@app.get("/api/groups", response_model=List[GroupResponse])
def get_my_groups(current_user_id: int = Depends(get_user_id_from_token)) -> List[GroupResponse]:
    """Obtener todos los grupos del usuario"""
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT g.id, g.name, g.description, g.creator_id, g.created_at,
               (SELECT COUNT(*) FROM group_members WHERE group_id = g.id) as member_count
        FROM groups g
        INNER JOIN group_members gm ON g.id = gm.group_id
        WHERE gm.user_id = ?
        ORDER BY g.created_at DESC
        """,
        (current_user_id,),
    )
    rows = cur.fetchall()
    conn.close()
    
    return [
        GroupResponse(
            id=row["id"],
            name=row["name"],
            description=row["description"],
            creator_id=row["creator_id"],
            created_at=row["created_at"],
            member_count=row["member_count"],
        )
        for row in rows
    ]


@app.get("/api/groups/{group_id}", response_model=GroupResponse)
def get_group(group_id: int, current_user_id: int = Depends(get_user_id_from_token)) -> GroupResponse:
    """Obtener información de un grupo"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que el usuario es miembro del grupo
    cur.execute(
        "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, current_user_id),
    )
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=403, detail="not a member of this group")
    
    cur.execute(
        """
        SELECT g.id, g.name, g.description, g.creator_id, g.created_at,
               (SELECT COUNT(*) FROM group_members WHERE group_id = g.id) as member_count
        FROM groups g
        WHERE g.id = ?
        """,
        (group_id,),
    )
    row = cur.fetchone()
    conn.close()
    
    if row is None:
        raise HTTPException(status_code=404, detail="group not found")
    
    return GroupResponse(
        id=row["id"],
        name=row["name"],
        description=row["description"],
        creator_id=row["creator_id"],
        created_at=row["created_at"],
        member_count=row["member_count"],
    )


@app.get("/api/groups/{group_id}/members", response_model=List[GroupMemberResponse])
def get_group_members(group_id: int, current_user_id: int = Depends(get_user_id_from_token)) -> List[GroupMemberResponse]:
    """Obtener miembros de un grupo"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que el usuario es miembro del grupo
    cur.execute(
        "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, current_user_id),
    )
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=403, detail="not a member of this group")
    
    cur.execute(
        """
        SELECT gm.user_id, u.username, u.phone_e164, gm.role, gm.joined_at
        FROM group_members gm
        INNER JOIN users u ON gm.user_id = u.id
        WHERE gm.group_id = ?
        ORDER BY gm.role DESC, gm.joined_at ASC
        """,
        (group_id,),
    )
    rows = cur.fetchall()
    conn.close()
    
    return [
        GroupMemberResponse(
            user_id=row["user_id"],
            username=row["username"],
            phone_e164=row["phone_e164"],
            role=row["role"],
            joined_at=row["joined_at"],
        )
        for row in rows
    ]


@app.post("/api/groups/{group_id}/members")
def add_group_member(group_id: int, req: AddGroupMemberRequest, current_user_id: int = Depends(get_user_id_from_token)) -> dict:
    """Agregar un miembro al grupo (solo admin)"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que el usuario actual es admin del grupo
    cur.execute(
        "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, current_user_id),
    )
    row = cur.fetchone()
    if row is None or row["role"] != "admin":
        conn.close()
        raise HTTPException(status_code=403, detail="only admins can add members")
    
    # Verificar que el usuario a agregar existe
    cur.execute("SELECT id FROM users WHERE id = ?", (req.user_id,))
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=404, detail="user not found")
    
    try:
        cur.execute(
            """
            INSERT INTO group_members (group_id, user_id, role, joined_at)
            VALUES (?, ?, 'member', ?)
            """,
            (group_id, req.user_id, now_iso()),
        )
        conn.commit()
    except sqlite3.IntegrityError:
        conn.close()
        raise HTTPException(status_code=400, detail="user already in group")
    
    conn.close()
    return {"status": "ok"}


@app.delete("/api/groups/{group_id}/members/{user_id}")
def remove_group_member(group_id: int, user_id: int, current_user_id: int = Depends(get_user_id_from_token)) -> dict:
    """Eliminar un miembro del grupo (admin o el mismo usuario)"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar permisos
    cur.execute(
        "SELECT role FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, current_user_id),
    )
    row = cur.fetchone()
    if row is None:
        conn.close()
        raise HTTPException(status_code=403, detail="not a member of this group")
    
    # Solo admin puede eliminar otros, o el usuario puede salir él mismo
    if row["role"] != "admin" and user_id != current_user_id:
        conn.close()
        raise HTTPException(status_code=403, detail="only admins can remove other members")
    
    cur.execute(
        "DELETE FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, user_id),
    )
    conn.commit()
    conn.close()
    
    return {"status": "ok"}


@app.post("/api/groups/messages", response_model=GroupMessageResponse)
def send_group_message(req: GroupMessageRequest, current_user_id: int = Depends(get_user_id_from_token)) -> GroupMessageResponse:
    """Enviar un mensaje a un grupo"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que el usuario es miembro del grupo
    cur.execute(
        "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
        (req.group_id, current_user_id),
    )
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=403, detail="not a member of this group")
    
    # Deduplicación por local_id
    if req.local_id:
        cur.execute(
            "SELECT id FROM group_messages WHERE group_id = ? AND sender_id = ? AND local_id = ?",
            (req.group_id, current_user_id, req.local_id),
        )
        existing = cur.fetchone()
        if existing:
            # Ya existe, devolver el mensaje existente
            cur.execute(
                """
                SELECT gm.id, gm.group_id, gm.sender_id, u.username as sender_name,
                       gm.content, gm.created_at, gm.sent_at, gm.local_id
                FROM group_messages gm
                INNER JOIN users u ON gm.sender_id = u.id
                WHERE gm.id = ?
                """,
                (existing["id"],),
            )
            row = cur.fetchone()
            conn.close()
            return GroupMessageResponse(
                id=row["id"],
                group_id=row["group_id"],
                sender_id=row["sender_id"],
                sender_name=row["sender_name"],
                content=row["content"],
                created_at=row["created_at"],
                sent_at=row["sent_at"],
                local_id=row["local_id"],
            )
    
    created_at = now_iso()
    sent_at = req.sent_at or created_at
    
    cur.execute(
        """
        INSERT INTO group_messages (group_id, sender_id, content, created_at, sent_at, local_id)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (req.group_id, current_user_id, req.content, created_at, sent_at, req.local_id),
    )
    msg_id = cur.lastrowid
    conn.commit()
    
    # Obtener nombre del remitente
    cur.execute("SELECT username FROM users WHERE id = ?", (current_user_id,))
    sender_name = cur.fetchone()["username"]
    
    conn.close()
    
    return GroupMessageResponse(
        id=msg_id,
        group_id=req.group_id,
        sender_id=current_user_id,
        sender_name=sender_name,
        content=req.content,
        created_at=created_at,
        sent_at=sent_at,
        local_id=req.local_id,
    )


@app.get("/api/groups/{group_id}/messages", response_model=List[GroupMessageResponse])
def get_group_messages(group_id: int, since_id: int = 0, current_user_id: int = Depends(get_user_id_from_token)) -> List[GroupMessageResponse]:
    """Obtener mensajes de un grupo"""
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que el usuario es miembro del grupo
    cur.execute(
        "SELECT 1 FROM group_members WHERE group_id = ? AND user_id = ?",
        (group_id, current_user_id),
    )
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=403, detail="not a member of this group")
    
    cur.execute(
        """
        SELECT gm.id, gm.group_id, gm.sender_id, u.username as sender_name,
               gm.content, gm.created_at, gm.sent_at, gm.local_id
        FROM group_messages gm
        INNER JOIN users u ON gm.sender_id = u.id
        WHERE gm.group_id = ? AND gm.id > ?
        ORDER BY gm.id ASC
        LIMIT 100
        """,
        (group_id, since_id),
    )
    rows = cur.fetchall()
    conn.close()
    
    return [
        GroupMessageResponse(
            id=row["id"],
            group_id=row["group_id"],
            sender_id=row["sender_id"],
            sender_name=row["sender_name"],
            content=row["content"],
            created_at=row["created_at"],
            sent_at=row["sent_at"],
            local_id=row["local_id"],
        )
        for row in rows
    ]


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
