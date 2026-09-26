# Phase 8 Architecture: Gamification & Merchant Federation

## System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         Mobile App / Web Client                          │
└────────┬────────────────────────────────────────────────────────────┬───┘
         │                                                             │
         │ REST API                                          GraphQL WS│
         │                                                             │
┌────────▼─────────────────────────────────────────────────────────────▼───┐
│                     titan-promotions-service (Port 8083)                 │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐         │
│  │ Quest State     │  │ Referral Graph  │  │ Shadow Rule     │         │
│  │ Machine         │  │ Service         │  │ Engine          │         │
│  │ (Task 2)        │  │ (Task 1)        │  │ (Task 5)        │         │
│  └────────┬────────┘  └────────┬────────┘  └────────┬────────┘         │
│           │                     │                     │                   │
│           │ Redis               │ Neo4j               │ SpEL              │
│           ▼                     ▼                     ▼                   │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐         │
│  │ State Persist   │  │ Graph Traversal │  │ Cost Projection │         │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘         │
│                                                                           │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │              PromotionService (Orchestrator)                 │        │
│  └────┬──────────────┬──────────────┬──────────────┬───────────┘        │
│       │              │              │              │                     │
│       │ gRPC         │ REST         │ Kafka        │ CDC                 │
│       ▼              ▼              ▼              ▼                     │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐            │
│  │ Escrow  │  │ Dynamic  │  │ Clawback │  │ Iceberg      │            │
│  │ Client  │  │ Pricing  │  │ Service  │  │ CDC Export   │            │
│  │ (Task 3)│  │ (Task 6) │  │ (Task 10)│  │ (Task 9)     │            │
│  └────┬────┘  └────┬─────┘  └────┬─────┘  └──────┬───────┘            │
│       │            │              │                │                     │
└───────┼────────────┼──────────────┼────────────────┼─────────────────────┘
        │            │              │                │
        │            │              │                │
┌───────▼────────────▼──────────────▼────────────────▼─────────────────────┐
│                      External Services                                    │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐      │
│  │ titan-core-      │  │ titan-ai-service │  │ Kafka Cluster    │      │
│  │ banking          │  │                  │  │                  │      │
│  │ (gRPC:9090)      │  │ (REST:8085)      │  │ (9092)           │      │
│  │                  │  │                  │  │                  │      │
│  │ • Escrow Ledger  │  │ • RL Optimizer   │  │ • Refund Events  │      │
│  │ • Budget Lock    │  │ • Pricing Model  │  │ • Transaction    │      │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘      │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘

┌───────────────────────────────────────────────────────────────────────────┐
│                      Data Stores (Phase 8)                                │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐│
│  │ Neo4j        │  │ Redis        │  │ PostgreSQL   │  │ MinIO        ││
│  │ (7687)       │  │ (6379)       │  │ (5432)       │  │ (9000)       ││
│  │              │  │              │  │              │  │              ││
│  │ • Referral   │  │ • Quest      │  │ • Campaigns  │  │ • Iceberg    ││
│  │   Graph      │  │   States     │  │ • Shadow     │  │   Data Lake  ││
│  │ • 10K+ nodes │  │ • FSM        │  │ • Clawbacks  │  │ • Analytics  ││
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘│
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

## Data Flow Examples

### Referral Reward Flow (Task 1)
```
User 3003 makes $1000 transaction
    ↓
PromotionService.evaluatePromotions()
    ↓
ReferralGraphService.calculateReferralRewards(3003, 1000)
    ↓
Neo4j: MATCH (u {accountId: 3003})<-[:REFERRED*]-(ancestor)
    ↓
Returns: [2002, 1001]
    ↓
Rewards: 2002 gets $50 (5%), 1001 gets $30 (3%)
    ↓
Save to applied_promotions table
```

### Quest State Machine Flow (Task 2)
```
User starts quest
    ↓
QuestService.startQuest(1001)
    ↓
StateMachine created with ID: QUEST-1001-uuid
    ↓
State: QUEST_ACCEPTED → persisted to Redis
    ↓
User completes daily task
    ↓
QuestService.progressQuest(questId)
    ↓
State: QUEST_ACCEPTED → DAY_1_DONE
    ↓
Repeat 7 times → DAY_7_DONE
    ↓
User claims reward → COMPLETED
```

### Escrow Lock Flow (Task 3)
```
Merchant creates $50K campaign
    ↓
MerchantFederationController.createCampaign()
    ↓
EscrowClient.lockCampaignBudget(campaignId, 50000, "USD")
    ↓
gRPC call to titan-core-banking:9090
    ↓
Core banking: UPDATE accounts SET balance = balance - 50000, escrow = escrow + 50000
    ↓
Returns escrowId: ESC-uuid
    ↓
Campaign activated with guaranteed budget
```

### Clawback Flow (Task 10)
```
User buys $100 item → receives $2 cashback
    ↓
User refunds purchase
    ↓
Core banking emits: TransactionRefundedEvent to Kafka
    ↓
ClawbackService.handleRefund()
    ↓
Check: existsByOriginalTransactionId(12345)? → false
    ↓
Find original promotion: $2 cashback
    ↓
Create RewardClawback record (UNIQUE constraint)
    ↓
Deduct $2 from user account
    ↓
Status: COMPLETED
    ↓
Duplicate refund events → idempotency check blocks
```

---

## Concurrency Patterns

### Neo4j (Task 1)
- **Pattern**: Optimistic locking with MVCC
- **Isolation**: Read Committed
- **Scalability**: Causal clustering for multi-region

### Redis State Machine (Task 2)
- **Pattern**: Pessimistic locking with `SETNX`
- **Isolation**: Serializable (single-threaded event loop)
- **Scalability**: Redis Cluster with hash slots

### Shadow Evaluations (Task 5)
- **Pattern**: Lock-free (append-only writes)
- **Isolation**: Read Uncommitted (analytics queries)
- **Scalability**: Partition by `rule_id`

### Merchant Budget Deduction (Task 7)
- **Pattern**: Optimistic locking with `WHERE remainingBudget >= :amount`
- **Isolation**: Repeatable Read
- **Scalability**: Row-level locks, no table scans

### Clawback Idempotency (Task 10)
- **Pattern**: Unique constraint enforcement
- **Isolation**: Serializable (PostgreSQL)
- **Scalability**: Hash index on `original_transaction_id`

---

## Advanced Data Structures

### Neo4j Graph
- **Structure**: Adjacency list with bidirectional pointers
- **Traversal**: Cypher `[:REFERRED*1..10]` uses depth-limited DFS
- **Complexity**: O(b^d) where b=branching factor, d=depth (limited to 10)

### Spring Statemachine
- **Structure**: Finite State Automaton (FSA)
- **Transition Table**: HashMap<State, Map<Event, State>>
- **Persistence**: Serialized to Redis as JSON

### Shadow Evaluation
- **Structure**: Time-series append-only log
- **Aggregation**: Materialized view for `SUM(theoretical_payout)`
- **Retention**: 90 days, then archived to Iceberg

---

## Testing

### Unit Tests
```bash
./gradlew :titan-promotions-service:test
```

### Integration Tests
```bash
./test-phase8.sh
```

### Load Tests
```bash
# 10K concurrent referral lookups
ab -n 10000 -c 100 http://localhost:8083/api/referrals/add?referrerId=1001&referredAccountId=2002
```

---

## Troubleshooting

### Neo4j Connection Failed
```bash
docker logs titan-neo4j
# Check: NEO4J_AUTH environment variable
```

### State Machine Not Persisting
```bash
docker exec titan-redis redis-cli KEYS "quest:*"
# Should show quest IDs
```

### Clawback Not Triggering
```bash
# Verify Kafka topic exists
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 | grep refunded
```

---

## Production Checklist

- [ ] Neo4j cluster with 3+ nodes
- [ ] Redis Sentinel for HA
- [ ] gRPC mTLS certificates
- [ ] GraphQL subscription rate limiting
- [ ] Iceberg table compaction schedule
- [ ] Merchant API key authentication
- [ ] WASM module signature verification

---

**Phase 8 Status**: ✅ Complete  
**Next**: Phase 9 - Distributed Tracing & Observability
