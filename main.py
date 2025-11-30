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
    try:
        cur.execute("ALTER TABLE messages ADD COLUMN image_data TEXT")  # Base64 encoded image
    except:
        pass
    # Actualizar registros existentes
    cur.execute("UPDATE messages SET sent_at = created_at WHERE sent_at IS NULL")
    cur.execute("UPDATE messages SET message_type = 'text' WHERE message_type IS NULL")
    
    # Agregar columna de contraseña de recuperación
    try:
        cur.execute("ALTER TABLE users ADD COLUMN recovery_password TEXT")
    except:
        pass

    # Device backups - respaldos por dispositivo
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS device_backups (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            user_id INTEGER NOT NULL,
            device_id TEXT NOT NULL,
            device_name TEXT,
            backup_data TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            FOREIGN KEY(user_id) REFERENCES users(id),
            UNIQUE(user_id, device_id)
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
    
    # Notificaciones de contacto eliminado
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS contact_deleted_notifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            deleted_by_user_id INTEGER NOT NULL,
            notify_user_id INTEGER NOT NULL,
            deleted_by_username TEXT NOT NULL,
            deleted_by_phone TEXT NOT NULL,
            created_at TEXT NOT NULL,
            seen INTEGER DEFAULT 0,
            FOREIGN KEY(deleted_by_user_id) REFERENCES users(id),
            FOREIGN KEY(notify_user_id) REFERENCES users(id)
        );
        """
    )
    
    # Solicitudes de restauración de contacto
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS contact_restore_requests (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            requester_user_id INTEGER NOT NULL,
            target_user_id INTEGER NOT NULL,
            requester_username TEXT NOT NULL,
            requester_phone TEXT NOT NULL,
            created_at TEXT NOT NULL,
            status TEXT DEFAULT 'pending',
            responded_at TEXT,
            FOREIGN KEY(requester_user_id) REFERENCES users(id),
            FOREIGN KEY(target_user_id) REFERENCES users(id)
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


# Números de prueba predefinidos con contraseña de recuperación
TEST_USERS = [
    {"username": "alvaro puebla", "password": "test123", "country_code": "+52", "national": "2228165690", "recovery": "190285"},
    {"username": "alvaro tulancingo", "password": "test123", "country_code": "+52", "national": "7753574534", "recovery": "190285"},
]


def create_test_users() -> None:
    """Crea usuarios de prueba si no existen y actualiza contraseña de recuperación"""
    import hashlib
    conn = get_db()
    cur = conn.cursor()
    
    for user in TEST_USERS:
        phone_e164 = user["country_code"] + user["national"]
        recovery_hash = hashlib.sha256(user["recovery"].encode()).hexdigest()
        
        # Verificar si ya existe
        cur.execute("SELECT id FROM users WHERE phone_e164 = ?", (phone_e164,))
        existing = cur.fetchone()
        
        if existing is None:
            # Crear usuario de prueba
            cur.execute(
                """
                INSERT INTO users (username, password_hash, phone_country_code, phone_national, phone_e164, created_at, recovery_password)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    user["username"],
                    hash_password(user["password"]),
                    user["country_code"],
                    user["national"],
                    phone_e164,
                    now_iso(),
                    recovery_hash,
                ),
            )
            print(f"✅ Usuario de prueba creado: {phone_e164}")
        else:
            # Actualizar contraseña de recuperación si no tiene
            cur.execute(
                "UPDATE users SET recovery_password = ? WHERE phone_e164 = ? AND (recovery_password IS NULL OR recovery_password = '')",
                (recovery_hash, phone_e164)
            )
            if cur.rowcount > 0:
                print(f"🔑 Contraseña de recuperación actualizada: {phone_e164}")
    
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


class SetRecoveryPasswordRequest(BaseModel):
    recovery_password: str = Field(..., min_length=4, max_length=50)


class RecoverAccountRequest(BaseModel):
    phone_country_code: str
    phone_national: str
    recovery_password: str


class RecoverAccountResponse(BaseModel):
    user_id: int
    username: str
    token: str
    has_backup: bool


class SendMessageRequest(BaseModel):
    recipient_id: int
    content: str = Field(..., min_length=1, max_length=1000)
    local_id: Optional[str] = None  # ID local para evitar duplicados
    sent_at: Optional[str] = None   # Timestamp de envío del cliente
    message_type: str = "text"      # "text", "voice" o "image"
    audio_data: Optional[str] = None  # Base64 encoded audio
    audio_duration: int = 0         # Duración en segundos
    image_data: Optional[str] = None  # Base64 encoded image


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
    image_data: Optional[str] = None


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


@app.post("/api/recovery/set-password")
def set_recovery_password(req: SetRecoveryPasswordRequest, current_user_id: int = Depends(get_user_id_from_token)):
    """Establece o actualiza la contraseña de recuperación"""
    
    conn = get_db()
    cur = conn.cursor()
    
    # Hashear la contraseña de recuperación
    import hashlib
    password_hash = hashlib.sha256(req.recovery_password.encode()).hexdigest()
    
    cur.execute(
        "UPDATE users SET recovery_password = ? WHERE id = ?",
        (password_hash, current_user_id)
    )
    conn.commit()
    conn.close()
    
    return {"status": "ok", "message": "Contraseña de recuperación establecida"}


@app.post("/api/recovery/recover", response_model=RecoverAccountResponse)
def recover_account(req: RecoverAccountRequest):
    """Recupera una cuenta usando el teléfono y contraseña de recuperación"""
    
    cc, nat, e164 = normalize_phone(req.phone_country_code, req.phone_national)
    
    conn = get_db()
    cur = conn.cursor()
    
    # Buscar usuario por teléfono
    cur.execute(
        "SELECT id, username, recovery_password FROM users WHERE phone_e164 = ?",
        (e164,)
    )
    row = cur.fetchone()
    
    if row is None:
        conn.close()
        raise HTTPException(status_code=404, detail="No existe cuenta con este número")
    
    # Verificar contraseña de recuperación
    if row["recovery_password"] is None:
        conn.close()
        raise HTTPException(status_code=400, detail="Esta cuenta no tiene contraseña de recuperación")
    
    import hashlib
    password_hash = hashlib.sha256(req.recovery_password.encode()).hexdigest()
    
    if row["recovery_password"] != password_hash:
        conn.close()
        raise HTTPException(status_code=401, detail="Contraseña de recuperación incorrecta")
    
    user_id = int(row["id"])
    username = row["username"]
    
    # Verificar si tiene respaldos
    cur.execute("SELECT COUNT(*) as count FROM device_backups WHERE user_id = ?", (user_id,))
    backup_count = cur.fetchone()["count"]
    
    # Generar nuevo token
    token = str(uuid.uuid4())
    cur.execute(
        "INSERT INTO auth_tokens (user_id, token, created_at) VALUES (?, ?, ?)",
        (user_id, token, now_iso()),
    )
    conn.commit()
    conn.close()
    
    return RecoverAccountResponse(
        user_id=user_id,
        username=username,
        token=token,
        has_backup=backup_count > 0
    )


@app.get("/api/recovery/check/{phone_e164}")
def check_recovery_available(phone_e164: str):
    """Verifica si un número tiene cuenta y contraseña de recuperación"""
    
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        "SELECT id, recovery_password FROM users WHERE phone_e164 = ?",
        (phone_e164,)
    )
    row = cur.fetchone()
    conn.close()
    
    if row is None:
        return {"exists": False, "has_recovery": False}
    
    return {
        "exists": True,
        "has_recovery": row["recovery_password"] is not None
    }


# ---------- Cleanup endpoints ----------


class CleanupRequest(BaseModel):
    days_to_keep: int = Field(default=7, ge=1, le=365)


class CleanupResponse(BaseModel):
    deleted_messages: int
    remaining_messages: int
    cleanup_date: str


@app.post("/api/cleanup/messages", response_model=CleanupResponse)
def cleanup_old_messages(
    req: CleanupRequest,
    current_user_id: int = Depends(get_user_id_from_token)
) -> CleanupResponse:
    """
    Eliminar mensajes antiguos del usuario actual.
    Solo elimina mensajes más antiguos que days_to_keep días.
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Calcular fecha límite
    from datetime import datetime, timedelta
    cutoff_date = (datetime.utcnow() - timedelta(days=req.days_to_keep)).isoformat() + "Z"
    
    # Contar mensajes a eliminar
    cur.execute(
        """
        SELECT COUNT(*) as count FROM messages 
        WHERE (sender_id = ? OR recipient_id = ?) 
        AND created_at < ?
        """,
        (current_user_id, current_user_id, cutoff_date)
    )
    deleted_count = cur.fetchone()["count"]
    
    # Eliminar mensajes antiguos
    cur.execute(
        """
        DELETE FROM messages 
        WHERE (sender_id = ? OR recipient_id = ?) 
        AND created_at < ?
        """,
        (current_user_id, current_user_id, cutoff_date)
    )
    
    # Contar mensajes restantes
    cur.execute(
        """
        SELECT COUNT(*) as count FROM messages 
        WHERE sender_id = ? OR recipient_id = ?
        """,
        (current_user_id, current_user_id)
    )
    remaining_count = cur.fetchone()["count"]
    
    conn.commit()
    conn.close()
    
    print(f"🗑️ Usuario {current_user_id}: {deleted_count} mensajes eliminados (anteriores a {cutoff_date})")
    
    return CleanupResponse(
        deleted_messages=deleted_count,
        remaining_messages=remaining_count,
        cleanup_date=cutoff_date
    )


@app.get("/api/cleanup/stats")
def get_cleanup_stats(current_user_id: int = Depends(get_user_id_from_token)):
    """
    Obtener estadísticas de mensajes del usuario para decidir limpieza.
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Total de mensajes
    cur.execute(
        """
        SELECT COUNT(*) as total FROM messages 
        WHERE sender_id = ? OR recipient_id = ?
        """,
        (current_user_id, current_user_id)
    )
    total = cur.fetchone()["total"]
    
    # Mensajes por antigüedad
    from datetime import datetime, timedelta
    
    stats = {"total": total, "by_age": {}}
    
    for days in [7, 14, 30, 60, 90]:
        cutoff = (datetime.utcnow() - timedelta(days=days)).isoformat() + "Z"
        cur.execute(
            """
            SELECT COUNT(*) as count FROM messages 
            WHERE (sender_id = ? OR recipient_id = ?) 
            AND created_at < ?
            """,
            (current_user_id, current_user_id, cutoff)
        )
        stats["by_age"][f"older_than_{days}_days"] = cur.fetchone()["count"]
    
    conn.close()
    
    return stats


# ---------- Full Backup endpoint ----------


class FullBackupResponse(BaseModel):
    user_id: int
    username: str
    phone_e164: str
    contacts: List[dict]
    messages: List[dict]
    total_messages: int
    total_contacts: int
    backup_date: str


@app.get("/api/backup/full")
def get_full_backup(current_user_id: int = Depends(get_user_id_from_token)):
    """
    Obtener backup completo del usuario:
    - Todos los contactos (usuarios con los que ha chateado)
    - Todos los mensajes de todas las conversaciones
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Obtener datos del usuario actual
    cur.execute("SELECT username, phone_e164 FROM users WHERE id = ?", (current_user_id,))
    user_row = cur.fetchone()
    if not user_row:
        conn.close()
        raise HTTPException(status_code=404, detail="user not found")
    
    # Obtener TODOS los mensajes del usuario (enviados y recibidos)
    cur.execute(
        """
        SELECT m.id, m.sender_id, m.recipient_id, m.content, m.created_at, m.sent_at, m.local_id,
               sender.username as sender_name, sender.phone_e164 as sender_phone,
               recipient.username as recipient_name, recipient.phone_e164 as recipient_phone
        FROM messages m
        JOIN users sender ON sender.id = m.sender_id
        JOIN users recipient ON recipient.id = m.recipient_id
        WHERE m.sender_id = ? OR m.recipient_id = ?
        ORDER BY m.created_at ASC
        """,
        (current_user_id, current_user_id)
    )
    message_rows = cur.fetchall()
    
    messages = []
    contact_ids = set()
    
    for r in message_rows:
        messages.append({
            "id": r["id"],
            "sender_id": r["sender_id"],
            "recipient_id": r["recipient_id"],
            "content": r["content"],
            "created_at": r["created_at"],
            "sent_at": r["sent_at"],
            "local_id": r["local_id"],
            "sender_name": r["sender_name"],
            "sender_phone": r["sender_phone"],
            "recipient_name": r["recipient_name"],
            "recipient_phone": r["recipient_phone"],
            "is_outgoing": r["sender_id"] == current_user_id
        })
        
        # Recopilar IDs de contactos
        if r["sender_id"] != current_user_id:
            contact_ids.add(r["sender_id"])
        if r["recipient_id"] != current_user_id:
            contact_ids.add(r["recipient_id"])
    
    # Obtener información de todos los contactos
    contacts = []
    if contact_ids:
        placeholders = ",".join("?" for _ in contact_ids)
        cur.execute(
            f"SELECT id, username, phone_e164 FROM users WHERE id IN ({placeholders})",
            tuple(contact_ids)
        )
        contact_rows = cur.fetchall()
        
        for c in contact_rows:
            # Contar mensajes con este contacto
            cur.execute(
                """
                SELECT COUNT(*) as msg_count FROM messages 
                WHERE (sender_id = ? AND recipient_id = ?) OR (sender_id = ? AND recipient_id = ?)
                """,
                (current_user_id, c["id"], c["id"], current_user_id)
            )
            msg_count = cur.fetchone()["msg_count"]
            
            contacts.append({
                "id": c["id"],
                "username": c["username"],
                "phone_e164": c["phone_e164"],
                "message_count": msg_count
            })
    
    conn.close()
    
    return {
        "user_id": current_user_id,
        "username": user_row["username"],
        "phone_e164": user_row["phone_e164"],
        "contacts": contacts,
        "messages": messages,
        "total_messages": len(messages),
        "total_contacts": len(contacts),
        "backup_date": now_iso()
    }


# ---------- Contact deletion with notification ----------


class DeleteContactRequest(BaseModel):
    contact_user_id: int


class ContactDeletedNotification(BaseModel):
    id: int
    deleted_by_user_id: int
    deleted_by_username: str
    deleted_by_phone: str
    created_at: str


@app.post("/api/contacts/delete")
def delete_contact_with_notification(
    req: DeleteContactRequest,
    current_user_id: int = Depends(get_user_id_from_token)
):
    """
    Eliminar un contacto y notificar al otro usuario.
    El otro usuario recibirá una notificación para decidir si conservar o eliminar el historial.
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Obtener información del usuario que elimina
    cur.execute("SELECT username, phone_e164 FROM users WHERE id = ?", (current_user_id,))
    current_user = cur.fetchone()
    
    if not current_user:
        conn.close()
        raise HTTPException(status_code=404, detail="user not found")
    
    # Verificar que el contacto existe
    cur.execute("SELECT id FROM users WHERE id = ?", (req.contact_user_id,))
    if cur.fetchone() is None:
        conn.close()
        raise HTTPException(status_code=404, detail="contact not found")
    
    # Crear notificación para el otro usuario
    cur.execute(
        """
        INSERT INTO contact_deleted_notifications 
        (deleted_by_user_id, notify_user_id, deleted_by_username, deleted_by_phone, created_at)
        VALUES (?, ?, ?, ?, ?)
        """,
        (
            current_user_id,
            req.contact_user_id,
            current_user["username"],
            current_user["phone_e164"],
            now_iso()
        )
    )
    
    conn.commit()
    conn.close()
    
    print(f"🔔 Notificación: {current_user['username']} eliminó a usuario {req.contact_user_id}")
    
    return {
        "success": True,
        "message": "Contacto eliminado y notificación enviada"
    }


@app.get("/api/contacts/deleted-notifications", response_model=List[ContactDeletedNotification])
def get_deleted_contact_notifications(
    current_user_id: int = Depends(get_user_id_from_token)
) -> List[ContactDeletedNotification]:
    """
    Obtener notificaciones de contactos que te han eliminado.
    """
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT id, deleted_by_user_id, deleted_by_username, deleted_by_phone, created_at
        FROM contact_deleted_notifications
        WHERE notify_user_id = ? AND seen = 0
        ORDER BY created_at DESC
        """,
        (current_user_id,)
    )
    rows = cur.fetchall()
    conn.close()
    
    return [
        ContactDeletedNotification(
            id=r["id"],
            deleted_by_user_id=r["deleted_by_user_id"],
            deleted_by_username=r["deleted_by_username"],
            deleted_by_phone=r["deleted_by_phone"],
            created_at=r["created_at"]
        )
        for r in rows
    ]


@app.post("/api/contacts/deleted-notifications/{notification_id}/seen")
def mark_notification_seen(
    notification_id: int,
    current_user_id: int = Depends(get_user_id_from_token)
):
    """
    Marcar una notificación como vista.
    """
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        "UPDATE contact_deleted_notifications SET seen = 1 WHERE id = ? AND notify_user_id = ?",
        (notification_id, current_user_id)
    )
    
    conn.commit()
    conn.close()
    
    return {"success": True}


# ---------- Contact restore requests ----------


class RestoreContactRequest(BaseModel):
    target_user_id: int


class ContactRestoreRequestDto(BaseModel):
    id: int
    requester_user_id: int
    requester_username: str
    requester_phone: str
    created_at: str
    status: str


class RespondRestoreRequest(BaseModel):
    request_id: int
    accept: bool


@app.post("/api/contacts/restore-request")
def send_restore_request(
    req: RestoreContactRequest,
    current_user_id: int = Depends(get_user_id_from_token)
):
    """
    Enviar solicitud para restaurar contacto eliminado.
    El otro usuario recibirá una notificación para aceptar o rechazar.
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Obtener información del usuario que solicita
    cur.execute("SELECT username, phone_e164 FROM users WHERE id = ?", (current_user_id,))
    current_user = cur.fetchone()
    
    if not current_user:
        conn.close()
        raise HTTPException(status_code=404, detail="user not found")
    
    # Verificar que no exista una solicitud pendiente
    cur.execute(
        """
        SELECT id FROM contact_restore_requests 
        WHERE requester_user_id = ? AND target_user_id = ? AND status = 'pending'
        """,
        (current_user_id, req.target_user_id)
    )
    if cur.fetchone():
        conn.close()
        raise HTTPException(status_code=400, detail="Ya existe una solicitud pendiente")
    
    # Crear solicitud de restauración
    cur.execute(
        """
        INSERT INTO contact_restore_requests 
        (requester_user_id, target_user_id, requester_username, requester_phone, created_at, status)
        VALUES (?, ?, ?, ?, ?, 'pending')
        """,
        (
            current_user_id,
            req.target_user_id,
            current_user["username"],
            current_user["phone_e164"],
            now_iso()
        )
    )
    
    conn.commit()
    conn.close()
    
    print(f"🔄 Solicitud de restauración: {current_user['username']} → usuario {req.target_user_id}")
    
    return {
        "success": True,
        "message": "Solicitud enviada. El usuario será notificado."
    }


@app.get("/api/contacts/restore-requests", response_model=List[ContactRestoreRequestDto])
def get_restore_requests(
    current_user_id: int = Depends(get_user_id_from_token)
) -> List[ContactRestoreRequestDto]:
    """
    Obtener solicitudes de restauración de contacto pendientes.
    """
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT id, requester_user_id, requester_username, requester_phone, created_at, status
        FROM contact_restore_requests
        WHERE target_user_id = ? AND status = 'pending'
        ORDER BY created_at DESC
        """,
        (current_user_id,)
    )
    rows = cur.fetchall()
    conn.close()
    
    return [
        ContactRestoreRequestDto(
            id=r["id"],
            requester_user_id=r["requester_user_id"],
            requester_username=r["requester_username"],
            requester_phone=r["requester_phone"],
            created_at=r["created_at"],
            status=r["status"]
        )
        for r in rows
    ]


@app.post("/api/contacts/restore-requests/respond")
def respond_to_restore_request(
    req: RespondRestoreRequest,
    current_user_id: int = Depends(get_user_id_from_token)
):
    """
    Responder a una solicitud de restauración de contacto.
    """
    conn = get_db()
    cur = conn.cursor()
    
    # Verificar que la solicitud existe y es para este usuario
    cur.execute(
        """
        SELECT id, requester_user_id, requester_username FROM contact_restore_requests 
        WHERE id = ? AND target_user_id = ? AND status = 'pending'
        """,
        (req.request_id, current_user_id)
    )
    request_row = cur.fetchone()
    
    if not request_row:
        conn.close()
        raise HTTPException(status_code=404, detail="Solicitud no encontrada")
    
    new_status = "accepted" if req.accept else "rejected"
    
    # Actualizar estado de la solicitud
    cur.execute(
        """
        UPDATE contact_restore_requests 
        SET status = ?, responded_at = ?
        WHERE id = ?
        """,
        (new_status, now_iso(), req.request_id)
    )
    
    # Si se acepta, eliminar cualquier notificación de eliminación pendiente
    if req.accept:
        cur.execute(
            """
            DELETE FROM contact_deleted_notifications 
            WHERE (deleted_by_user_id = ? AND notify_user_id = ?)
            OR (deleted_by_user_id = ? AND notify_user_id = ?)
            """,
            (current_user_id, request_row["requester_user_id"],
             request_row["requester_user_id"], current_user_id)
        )
    
    conn.commit()
    conn.close()
    
    action = "aceptada" if req.accept else "rechazada"
    print(f"✅ Solicitud {action}: {request_row['requester_username']} ↔ usuario {current_user_id}")
    
    return {
        "success": True,
        "accepted": req.accept,
        "message": f"Solicitud {action}"
    }


@app.get("/api/contacts/restore-requests/status/{target_user_id}")
def check_restore_request_status(
    target_user_id: int,
    current_user_id: int = Depends(get_user_id_from_token)
):
    """
    Verificar el estado de una solicitud de restauración enviada.
    """
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT id, status, responded_at FROM contact_restore_requests 
        WHERE requester_user_id = ? AND target_user_id = ?
        ORDER BY created_at DESC LIMIT 1
        """,
        (current_user_id, target_user_id)
    )
    row = cur.fetchone()
    conn.close()
    
    if not row:
        return {"has_request": False}
    
    return {
        "has_request": True,
        "request_id": row["id"],
        "status": row["status"],
        "responded_at": row["responded_at"]
    }


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
        INSERT INTO messages (sender_id, recipient_id, content, created_at, sent_at, local_id, is_delivered, message_type, audio_data, audio_duration, image_data)
        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
        """,
        (current_user_id, req.recipient_id, req.content, created_at, sent_at, req.local_id, req.message_type, req.audio_data, req.audio_duration, req.image_data),
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
        image_data=req.image_data,
    )


@app.get("/api/messages", response_model=List[MessageResponse])
def get_messages(with_user_id: int, limit: int = 50, current_user_id: int = Depends(get_user_id_from_token)) -> List[MessageResponse]:
    conn = get_db()
    cur = conn.cursor()

    cur.execute(
        """
        SELECT id, sender_id, recipient_id, content, created_at, sent_at, received_at, is_delivered, local_id,
               message_type, audio_data, audio_duration, image_data
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
                image_data=r["image_data"],
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
               message_type, audio_data, audio_duration, image_data
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
                image_data=r["image_data"],
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


# ========== Sistema de Relay de Multimedia ==========

# Almacenamiento temporal en memoria (en producción usar Redis o similar)
media_relay_storage: dict = {}  # {message_local_id: {"data": base64, "sender_id": int, "created_at": str}}

class MediaUploadRequest(BaseModel):
    message_local_id: str
    recipient_id: int
    media_type: str  # "voice" o "image"
    media_data: str  # Base64 encoded

class MediaDownloadResponse(BaseModel):
    message_local_id: str
    media_type: str
    media_data: str
    sender_id: int

@app.post("/api/media/upload")
def upload_media(req: MediaUploadRequest, current_user_id: int = Depends(get_user_id_from_token)):
    """Sube multimedia temporalmente al servidor para que el receptor la descargue"""
    
    # Limitar tamaño (max 5MB en base64 ≈ 3.75MB real)
    if len(req.media_data) > 5 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Archivo muy grande (max 5MB)")
    
    # Guardar en almacenamiento temporal
    media_relay_storage[req.message_local_id] = {
        "data": req.media_data,
        "sender_id": current_user_id,
        "recipient_id": req.recipient_id,
        "media_type": req.media_type,
        "created_at": now_iso()
    }
    
    # Limpiar archivos viejos (más de 24 horas)
    cleanup_old_media()
    
    return {"status": "uploaded", "message_local_id": req.message_local_id}

@app.get("/api/media/download/{message_local_id}")
def download_media(message_local_id: str, current_user_id: int = Depends(get_user_id_from_token)):
    """Descarga multimedia y la elimina del servidor"""
    
    if message_local_id not in media_relay_storage:
        raise HTTPException(status_code=404, detail="Multimedia no encontrada o ya descargada")
    
    media = media_relay_storage[message_local_id]
    
    # Verificar que el usuario es el destinatario
    if media["recipient_id"] != current_user_id:
        raise HTTPException(status_code=403, detail="No autorizado")
    
    # Obtener datos
    response = MediaDownloadResponse(
        message_local_id=message_local_id,
        media_type=media["media_type"],
        media_data=media["data"],
        sender_id=media["sender_id"]
    )
    
    # Eliminar del almacenamiento temporal
    del media_relay_storage[message_local_id]
    
    return response

@app.get("/api/media/pending")
def get_pending_media(current_user_id: int = Depends(get_user_id_from_token)):
    """Obtiene lista de multimedia pendiente para el usuario"""
    
    pending = []
    for local_id, media in media_relay_storage.items():
        if media["recipient_id"] == current_user_id:
            pending.append({
                "message_local_id": local_id,
                "media_type": media["media_type"],
                "sender_id": media["sender_id"],
                "created_at": media["created_at"]
            })
    
    return {"pending": pending, "count": len(pending)}

def cleanup_old_media():
    """Elimina multimedia con más de 24 horas"""
    from datetime import datetime, timedelta
    
    cutoff = datetime.utcnow() - timedelta(hours=24)
    to_delete = []
    
    for local_id, media in media_relay_storage.items():
        try:
            created = datetime.fromisoformat(media["created_at"].replace("Z", "+00:00"))
            if created.replace(tzinfo=None) < cutoff:
                to_delete.append(local_id)
        except:
            pass
    
    for local_id in to_delete:
        del media_relay_storage[local_id]


# ========== Sistema de Respaldo y Recuperación ==========

class BackupRequest(BaseModel):
    device_id: str
    device_name: Optional[str] = None
    backup_data: str  # JSON string con los chats

class BackupResponse(BaseModel):
    id: int
    device_id: str
    device_name: Optional[str]
    created_at: str
    updated_at: str
    has_data: bool

class RestoreResponse(BaseModel):
    device_id: str
    device_name: Optional[str]
    backup_data: str
    updated_at: str

@app.post("/api/backup")
def create_or_update_backup(req: BackupRequest, current_user_id: int = Depends(get_user_id_from_token)):
    """Crea o actualiza un respaldo para el dispositivo"""
    
    conn = get_db()
    cur = conn.cursor()
    
    now = now_iso()
    
    # Intentar actualizar si ya existe
    cur.execute(
        """
        UPDATE device_backups 
        SET backup_data = ?, device_name = ?, updated_at = ?
        WHERE user_id = ? AND device_id = ?
        """,
        (req.backup_data, req.device_name, now, current_user_id, req.device_id)
    )
    
    if cur.rowcount == 0:
        # No existe, crear nuevo
        cur.execute(
            """
            INSERT INTO device_backups (user_id, device_id, device_name, backup_data, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (current_user_id, req.device_id, req.device_name, req.backup_data, now, now)
        )
    
    conn.commit()
    conn.close()
    
    return {"status": "saved", "device_id": req.device_id, "updated_at": now}

@app.get("/api/backup/list")
def list_backups(current_user_id: int = Depends(get_user_id_from_token)):
    """Lista todos los respaldos del usuario"""
    
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT id, device_id, device_name, created_at, updated_at, LENGTH(backup_data) as data_size
        FROM device_backups
        WHERE user_id = ?
        ORDER BY updated_at DESC
        """,
        (current_user_id,)
    )
    rows = cur.fetchall()
    conn.close()
    
    backups = []
    for r in rows:
        backups.append({
            "id": r["id"],
            "device_id": r["device_id"],
            "device_name": r["device_name"],
            "created_at": r["created_at"],
            "updated_at": r["updated_at"],
            "data_size": r["data_size"]
        })
    
    return {"backups": backups, "count": len(backups)}

@app.get("/api/backup/restore/{device_id}")
def restore_backup(device_id: str, current_user_id: int = Depends(get_user_id_from_token)):
    """Restaura un respaldo específico"""
    
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        """
        SELECT device_id, device_name, backup_data, updated_at
        FROM device_backups
        WHERE user_id = ? AND device_id = ?
        """,
        (current_user_id, device_id)
    )
    row = cur.fetchone()
    conn.close()
    
    if row is None:
        raise HTTPException(status_code=404, detail="Respaldo no encontrado")
    
    return RestoreResponse(
        device_id=row["device_id"],
        device_name=row["device_name"],
        backup_data=row["backup_data"],
        updated_at=row["updated_at"]
    )

@app.delete("/api/backup/{device_id}")
def delete_backup(device_id: str, current_user_id: int = Depends(get_user_id_from_token)):
    """Elimina un respaldo específico"""
    
    conn = get_db()
    cur = conn.cursor()
    
    cur.execute(
        "DELETE FROM device_backups WHERE user_id = ? AND device_id = ?",
        (current_user_id, device_id)
    )
    conn.commit()
    deleted = cur.rowcount
    conn.close()
    
    if deleted == 0:
        raise HTTPException(status_code=404, detail="Respaldo no encontrado")
    
    return {"status": "deleted", "device_id": device_id}


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
