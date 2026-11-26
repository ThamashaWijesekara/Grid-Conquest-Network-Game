# Grid Conquest Arena (Java Network Programming Project)

## Implemented Modules
- **UDP Real-Time Sync (`game.udp`)**: Live hover position, dice roll broadcast, cell claims, join events, and turn handoff over UDP (`DatagramSocket` / `DatagramPacket`).
- **Interactive Game Board (`game.ui.InteractiveGridFrame`)**: 10x10 territory capture grid with dice roll animation, fair turn rotation, and score tracking.
- **Secure Lobby (`game.security`)**: TLS login, salted PBKDF2 password hashing, expiring authentication tokens, HMAC-SHA256 packet integrity, replay protection, and packet validation.

## Pending Group Modules (To Integrate)
- **Member 02**: TCP Programming (reliable state/move channel)
- **Member 03**: Multi-Threading & Java NIO concepts
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
