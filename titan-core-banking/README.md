# 🏦 Titan Core Banking Protocol

![Status](https://img.shields.io/badge/STATUS-OPERATIONAL-brightgreen)
![Version](https://img.shields.io/badge/VERSION-1.0.0-blue)
![Security](https://img.shields.io/badge/SECURITY-IRONCLAD-red)


> **Operation:** Ironclad (End-to-End Secure Banking System)

## 📖 Project Overview

**Titan Core Banking** is a high-performance, distributed banking system designed with a **Microservices Architecture**. It simulates a real-world financial backend featuring military-grade security, AI-powered fraud detection, and real-time observability.

---

##  System Architecture

The system utilizes a hybrid microservices approach, leveraging the best languages for specific tasks: **Java** for core logic, **Golang** for high-speed routing, and **Python** for AI analysis.

| Service | Technology | Port | Role |
| :--- | :--- | :--- | :--- |
| **Titan Gateway** | **Golang** (Gin/Fiber) | `:8000` | Entry point, Rate Limiting, Reverse Proxy |
| **Core Engine** | **Java** (Spring Boot 3) | `:8080` | Business Logic, Transactions, DB Management |
| **Risk Engine** | **Python** (FastAPI/gRPC) | `:50051` | AI Fraud Detection & Transaction Analysis |
| **Database** | **PostgreSQL 15** | `:5432` | Relational Data Persistence |
| **Cache** | **Redis Alpine** | `:6379` | OTP storage, Caching, Idempotency Keys |
| **Monitoring** | **Prometheus & Grafana** | `:3000` | Real-time System Observability |

---

##  Observability (Grafana)

Real-time monitoring of JVM memory, CPU usage, and API traffic spikes.

![Grafana Dashboard](screenshots/gr<img width="2292" height="1181" alt="Screenshot 2026-01-19 at 2 19 44 in the morning" src="https://github.com/user-attachments/assets/23438307-d294-4896-98c9-f916bcf220a3" />
afana-dashboard.png)
*(Live metrics visualizing Transaction Throughput and System Health)*

---

##  API Documentation (Swagger UI)

Full interactive API documentation allowing for real-time testing of endpoints.

![Swagger UI](screenshots/swagger-ui.png)<img width="2292" height="1125" alt="Screenshot 2026-01-19 at 2 05 47 in the morning" src="https://github.com/user-attachments/assets/5d5f959f-e92a-4e2b-ab40-fdc276443e03" />

*(Interactive API testing interface for Auth, Accounts, and Transactions)*

---

##  Key Features

###  Security (Operation Ironclad)
* **JWT Authentication:** Stateless session management with fixed secret keys.
* **2FA / OTP:** High-value transfers (>$10k) require a One-Time Password stored in Redis (5-minute TTL).
* **Smart Lock:** Account locks after 5 failed PIN attempts (Temporary) and 7 attempts (Permanent).
* **Rate Limiting:** Golang Token Bucket algorithm (100 req/s) to prevent DDoS.

###  Core Banking Logic
* **Transactions:** Deposit, Withdrawal, and Transfer with ACID compliance.
* **Idempotency:** Prevents double-spending using unique request headers cached in Redis.
* **Luhn Algorithm:** Generates professional, validatable 12-digit account numbers.

###  AI Sentinel (Risk Engine)
* Integrated Python Microservice.
* Analyzes transaction patterns in real-time.
* **Logic:** Automatically **BLOCKS** transactions flagged as "High Risk" (>$50,000) based on pre-trained logic.

---

##  Deployment & Installation

The entire system is containerized. You can deploy the full stack with a single command.

### Prerequisites
* Docker & Docker Compose
* Java 21 (for local dev)
* Python 3.x (for test scripts)
* Proxmox for controll VM and debian controll server 

### Quick Start
```bash
# 1. Clone the repository
git clone [https://github.com/bunchhay/titan-core-banking.git](https://github.com/bunchhay1/titan-core-banking.git)

# 2. Navigate to project directory
cd titan-core-banking

# 3. Deploy Infrastructure (Debian/Linux/Mac)
docker compose up -d --build
