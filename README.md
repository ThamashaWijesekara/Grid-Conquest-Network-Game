# Grid Conquest Arena (Java Network Programming Project)

## Current Implemented Module (Member 01)
- **UDP Real-Time Sync (`game.udp`)**: Live hover position, dice roll broadcast, cell claims, join events, and turn handoff over UDP (`DatagramSocket` / `DatagramPacket`).
- **Interactive Game Board (`game.ui.InteractiveGridFrame`)**: 10x10 territory capture grid with dice roll animation, fair turn rotation, and score tracking.

## Pending Group Modules (To Integrate)
- **Member 02**: TCP Programming (reliable state/move channel)
- **Member 03**: Multi-Threading & Java NIO concepts
- **Member 04**: Secure Network Programming (authentication / encryption / integrity)
- **Member 05**: Addressing & Network Discovery (`InetAddress` / LAN discovery)