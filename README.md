# Grid Conquest Arena (Java Network Programming Project)

## Implemented Modules
- **UDP Real-Time Sync (`game.udp`)**: Live hover position, dice roll broadcast, cell claims, join events, and turn handoff over UDP (`DatagramSocket` / `DatagramPacket`).
- **Interactive Game Board (`game.ui.InteractiveGridFrame`)**: 10x10 territory capture grid with dice roll animation, fair turn rotation, and score tracking.
- **Secure Lobby (`game.security`)**: TLS login, salted PBKDF2 password hashing, expiring authentication tokens, HMAC-SHA256 packet integrity, replay protection, and packet validation.
- **Multi-Threaded NIO Game Server (`game.server`)**: one Java NIO `Selector` thread for all sockets, a worker thread pool for game logic, and a lock-protected authoritative `GameRoom` (server-rolled dice, turn order, turn timer) hosting up to 5 concurrent players over TCP. Details in [`docs/MEMBER3_SERVER.md`](docs/MEMBER3_SERVER.md).

## Pending Group Modules (To Integrate)
- **Member 02**: TCP Programming (reliable state/move channel)
- **Member 05**: Addressing & Network Discovery (`InetAddress` / LAN discovery)

## Member 04 Security Flow

1. A player logs in to the lobby over an encrypted `SSLSocket` connection.
2. The lobby verifies a salted PBKDF2 password hash and returns a signed 30-minute token.
3. Every UDP message contains the token, timestamp, random nonce, and HMAC-SHA256 signature.
4. Receivers reject invalid tokens, changed packets, player impersonation, packets older than 10 seconds, replayed packets, and invalid game values.

This is intentionally a small classroom implementation. Users are stored in memory and `P1` through `P5` have the demo password `player123`.

## Compile and Run

Requirements: JDK 17 or newer and PowerShell.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\setup-security.ps1
New-Item -ItemType Directory -Force build\classes | Out-Null
javac -d build\classes (Get-ChildItem -Recurse src -Filter *.java | ForEach-Object FullName)
```

Start the secure lobby in the first terminal:

```powershell
java "-Djavax.net.ssl.keyStore=config/grid-conquest.p12" "-Djavax.net.ssl.keyStorePassword=changeit" -cp build\classes Main server
```

Start each player in another terminal. The same local certificate is used as the classroom trust store:

```powershell
java "-Djavax.net.ssl.trustStore=config/grid-conquest.p12" "-Djavax.net.ssl.trustStorePassword=changeit" -cp build\classes Main P1 RED
```

Use `P2 BLUE`, `P3 GREEN`, and so on for other players. For different computers, change the lobby host in `InteractiveGridFrame` from `localhost` to the server's LAN address and copy only the trusted certificate/trust store to clients. Never copy a production server's private key.

All machines must set the same `GRID_SECURITY_SECRET` environment variable. The included default exists only to make the classroom demonstration easy to run.

## Security Test

```powershell
java -cp build\classes game.security.SecuritySelfTest
```

The test verifies correct and incorrect passwords, modified tokens, modified packets, replayed packets, and lobby authentication. With the TLS server running, test the real secure socket too:

```powershell
java "-Djavax.net.ssl.trustStore=config/grid-conquest.p12" "-Djavax.net.ssl.trustStorePassword=changeit" -cp build\classes game.security.SecuritySelfTest tls
```

## Member 03 Multi-Threaded NIO Game Server

Compile as shown above, then start the server (default TCP port `9900`, all network interfaces):

```powershell
java -cp build\classes Main game-server
```

Optional flags: `--port N`, `--workers N`, `--max-players N`, `--turn-seconds N`, `--idle-seconds N`, `--require-token`, `--quiet`.
`--idle-seconds N` drops connections silent for `N` seconds (`0` by default, since waiting for your turn is silent).
With `--require-token` a client must present the token issued by the secure lobby in `JOIN|id|token`
(same `GRID_SECURITY_SECRET` as the lobby). Type `status`, `players`, `kick <id>` or `quit` in the server console.

Play without opening GUI windows - one terminal per scripted player (arguments: host, port, id, think-time in ms):

```powershell
java -cp build\classes Main game-bot 127.0.0.1 9900 P1 300
java -cp build\classes Main game-bot 127.0.0.1 9900 P2 300
java -cp build\classes Main game-bot 127.0.0.1 9900 P3 300
```

Against a server started with `--require-token`, add `auto` as a fifth argument so each bot signs its own token.

Automated concurrency tests (join races, rule enforcement, turn-timer races, disconnect hand-over, 8000-message hover flood, 900-connection churn, lobby-token checks). Exit code `0` means every check passed:

```powershell
java -cp build\classes Main game-stress
```

Current integration status: the GUI still plays through the UDP layer. The server's event names (`JOIN`, `HOVER`, `DICE`, `CLAIM`, `TURN_DONE`) match `UdpEventListener` one-to-one, and `game.server.demo.BotClient` is a working reference for a client that talks to the server.
