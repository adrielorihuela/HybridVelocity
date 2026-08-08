# Current proxy behavior for offline players (before this update)
The proxy currently handles offline players using the following process:
1. A player connects to the proxy.
2. The proxy attempts Mojang authentication.
2.1. Mojang authentication fails because the player profile is not found.
3. The proxy modifies the offline player's username by adding "." at the end of the original username. Example:
Original username: Steve123
Modified username: Steve123.
The modified username is the internal identity used by the proxy for offline players.
3.1. The proxy generates the offline UUID using the modified username. Example: UUID = generateOfflineUUID("Steve123.")
This UUID generation must remain deterministic. The same modified username must always generate the same UUID.
4. All offline player identity-related data must be associated only with the generated offline UUID and the modified username. The original username without "." must not be used as the player identifier.
5. The proxy immediately allows the player to connect to the backend server.
Note: This is the current behavior. The authentication system described below does not exist yet and must be added between UUID generation and backend connection.

# Current connection flow and required modification point
The current connection flow is:
1 Player connects
        |
        v
2 Mojang authentication fails
        |
        v
3 Proxy modifies username: Steve123 → Steve123.
        |
        v
3.1 Proxy generates UUID: generateOfflineUUID("Steve123.")
        |
        v
5 Backend connection is allowed immediately
Required change: The authentication system must be inserted after UUID generation and before the backend connection step.

# debes añadir el siguiente proceso despues del punto 4 y antes del punto 5:
The following authentication system must only be applied to offline-mode players. Premium authenticated players must continue using the existing Velocity flow without changes.
4.1 The proxy must not connect the player to any backend server yet. The player connection must remain active only on the proxy (el proxy tambien debe evitar que el launcher del cliente lo quite del servidor). The proxy must keep the client session alive by handling the required Minecraft protocol packets (such as KeepAlive and Ping responses) while the player is waiting for authentication.
4.2 The proxy must keep the player in the Minecraft PLAY protocol state without creating a backend server connection. However, only authentication commands are accepted. Normal chat messages and all other commands must be intercepted by the proxy and must never reach the backend server (como la cola de espera de el servidor 2b2t; enviar KeepAlive; mantener estado PLAY; the backend connection must not be established until authentication is completed successfully; responder Ping; etc)
4.3 se explicara mas adelante
4.4 el proxy continua los procesos necesarios para llegar al punto 5

# explicacion del proceso del punto 4.3:
## si el proxy no tiene una contraseña registrada para el jugador, entonces en el chat se envia un mensaje que dice:
"
Type /register <password> <password> to register.
"
el jugador debe escribir ese commando para registrarse.
### Si el jugador escribe su contraseña 2 veces iguales, entonces continua al proceso de guardado de contraseña (esto es paraa evitar que el jugador registre una contraseña por error), luego el proceso continua al punto 4.4
### si el jugador no escribe la contraseña 2 veces iguales, entonces se envia un mensaje al chat que dice:
"
Error: Passwords do not match. Please register again.
"
luego de eso el jugador debe volver a registrarse.
### si el jugador escribe el commando con argumentos incorrectos, entonces en el chat se envia el siguiente mensaje:
"
Usage: /register <password> <password>
"

## si el jugador ya tiene una contraseña registrada en el proxy, entonces en el chat se envia el siguiente mensaje:
"
Type /login <password> to log in.
"
el jugador debe escribir el comando.
### si el jugador escribe correctamente la contraseña registrada entonces el proceso continua al punto 4.4
### si el jugador no escribe correctamente la contraseña registada, entonces en el chat se envia:
"
Incorrect password. Please try again.
"
y el jugador debe volver a escribir el comando
### si el jugador escribe el commando con argumentos erroneous entonces sale el siguiente mensaje:
"
Usage: /login <password>
"
### si el jugador falla la contraseña 3 veces entonces es expulsado con el siguiente mensaje:
"
Too many failed login attempts.
"

# despues de todos esos procesos
despues que el jugador se haya registrado o haya iniciado sesión (log in) correctamente (cuando haya entrado al servidor backend) entonces en el chat se escribe el siguiente mensaje:
"
Tip: Use /changepassword to change your password.
"
si el jugador ejecuta /changepassword sin argumentos o con argumentos incorrectos, entonces se envia el siguiente mensaje:
"
Usage: /changepassword <current password> <new password> <new password>
"
si la contraseña actual es incorrecta:
"
Incorrect current password. Please try again.
"
si las dos contraseñas nuevas no coinciden:
"
Error: New passwords do not match. Please try again.
"
si todo sale bien:
"
Password changed successfully.
"
entonces continua al proceso de guardado de contraseña.

# restricciones de contraseña
## la contraseña debe tener un minimo de 4 caracteres y maximo 16, de lo contrario hay error e indica:
"
Error: Password must be between 4 and 16 characters long.
"
## en la contraseña solo se permiten los ASCII printable characters y la "ñ" y "Ñ", si el jugador escribe un caracter que no esta permitido, entonces enviar un mensaje que indique los caracteres que es invalidos:
"
Error: Invalid characters detected: <characters>.
"
If multiple invalid characters are detected, the error message must list all unique invalid characters separated by commas.
Duplicate invalid characters should only be displayed once.

# Password storage system
The proxy must store only the current password for each player.

Passwords must never be stored in plain text.
The proxy must use a password hashing algorithm designed for password storage, such as Argon2id, bcrypt, or PBKDF2.
Fast cryptographic hashes such as SHA-256, SHA-1, or MD5 must not be used for password storage.
The salt must be unique for every password record and must be stored together with the password hash in the database.
The salt is not secret and is required to verify the password later.
The original password must not be recoverable from the stored data.

Each password record must be associated with:

- The player's offline UUID generated from the modified username (username + ".").
- The modified player name, including the "." added by the proxy.
- The exact date and time when the password was registered.
- The exact date and time when the password was last updated.

Example:

Player:
Steve123.

UUID:
Generated from "Steve123."

Stored data:

UUID:
xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx

Username:
Steve123.

Password hash record:
<algorithm parameters + salt + derived hash>

Created at:
YYYY-MM-DD HH:MM:SS

Last updated at:
YYYY-MM-DD HH:MM:SS

When the player changes the password:

- The previous password hash must be replaced with the new password hash.
- The previous password must not be stored.
- The update date and time must be updated.
- The UUID and modified username association must remain unchanged.

The UUID is the only identifier used to locate the player's password record.

# Password storage backend

The proxy must use an embedded SQLite database to store authentication data.

The SQLite database must be created and managed automatically by the proxy.

No external database server, installation, or manual configuration must be required.

The database file must be created inside the proxy data directory.

Example:

velocity/
 └─ player_auth.db

The proxy must create the required database tables automatically on first startup.

If the SQLite database file does not exist, the proxy must create it automatically.

If the database cannot be opened or initialized, the proxy must log the error and authentication must fail safely without allowing unauthenticated offline players to connect to the backend.

The storage system must support asynchronous database operations.
Database reads and writes must never block the Netty event loop.

The database structure must allow storing:

- Player offline UUID.
- Modified player username.
- Current password hash.
- Password creation date and time.
- Password last update date and time.

Example table:

Table: offline_player_auth

Columns:

uuid TEXT PRIMARY KEY
username TEXT
password_hash TEXT NOT NULL
created_at DATETIME NOT NULL
updated_at DATETIME NOT NULL

# mensajes de chat
todos los mensajes que se envien en el chat Deben estar escritos en el idioma ingles, correctamente o como se acostumbra escribir en contextos de videojuegos como Minecraft, para mantenar consistencia
prioriza no cambiar los mensajes de chat, pero si me falto algo o hace falta editarlos, entonces asegurate de mantenerlos en el idioma ingles
si el jugador no escribe nada por 60 segundos o mas, entonces se desconecta con el mensaje:
"
Authentication timed out.
"
durante la autentificacion el jugador solo puede usar los commandos de autentificacion, no puede escribir mensajes, ni usar otros comandos, si lo hace se enviara:
"
Please log in first.
"
y si aun no se ha registrado entonces:
"
Please register first.
"
y el resto de mensajes o commandos seran ignorados.
todo lo que se envie al chat durante la autentificacion, solo sera visto por el jugador y el proxy, ningun otro jugador vera los mensajes de otro jugador.

# Non-functional requirements
If the proxy restarts after a successful authentication, the player session state does not need to be restored. The player must authenticate again on the next connection.
Only one active authentication session per offline UUID is allowed.
If the same UUID connects again while another session is active, the proxy must handle the connection according to Velocity's default duplicate login behavior.
SQLite database operations must be asynchronous.
Database reads and writes must never block the Netty event loop.
The database file must be stored outside the proxy JAR and must persist between proxy restarts.
The proxy must continue handling thousands of players.
Authentication must not introduce lag.
Existing Velocity behavior for premium players must remain unchanged.
The feature must only affect offline-mode players.
The implementation must follow the existing Velocity architecture and coding style.
Avoid modifying unrelated classes.

# arquitectura final del proxy
Velocity Proxy
│
├── Premium players
│       └── Existing Velocity flow
│
└── Offline players
        │
        ├── Modify username (.)
        │
        ├── Generate offline UUID
        │
        ├── Authentication system
        │
        └── SQLite
              │
              ├── UUID
              ├── Username
              ├── Password hash
              ├── Created date
              └── Updated date
