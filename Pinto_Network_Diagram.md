# Pinto Android App - Network Diagram

## Overview

Pinto is an Android client that connects to the backend controller via WebSocket and performs payment processing locally through the Planet terminal. The payment side is on the Planet terminal, not the controller.

---

## Mermaid Diagram

```mermaid
flowchart TD
  subgraph Controller["Backend Controller"]
    WS["WebSocket Server"]
    Limits["Daily Limit Check"]
    PostFlow["Post-payment Flow (Receipt/Ticket/Thank You)"]
  end

  subgraph Pinto["Pinto Android App (Device)"]
    UI["UI Screens (Local StateFlow)"]
    WSClient["WebSocket Client"]
    Payment["Payment Manager (Planet/Mock)"]
    DB["Local Config DB (Room)"]
  end

  subgraph Planet["Planet Payment Terminal"]
    Terminal["Planet Terminal (Payment Side)"]
    Reader["Card Reader / NFC"]
  end

  UI <-->|"Local screen flow"| WSClient
  WSClient <-->|"WS/WSS JSON"| WS
  WSClient -->|"Card token"| Limits
  Limits -->|"Limit result"| WSClient
  WSClient -->|"Payment result"| PostFlow

  Payment -->|"TCP 1234 (Integra)"| Terminal
  Terminal --> Reader
  Payment --> DB

  note right of Controller
    Controller does not process
    card payments
  end note
```

---

## Network Notes

- **Controller ↔ Pinto**: WebSocket (WS/WSS), JSON messages
- **Pinto ↔ Planet Terminal**: TCP 1234 (Integra SDK)
- **Payment Processing**: Executed on the Planet terminal (payment side)
