import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
export const totalTransactions = new Counter('total_transactions_processed');
export const transferTpsCounter = new Counter('transfers_processed');
export const readTpsCounter = new Counter('reads_processed');
export const depositTpsCounter = new Counter('deposits_processed');

export const errorRate = new Rate('error_rate');
export const transferDuration = new Trend('transfer_latency_ms', true);
export const readDuration = new Trend('read_latency_ms', true);

export const options = {
  stages: [
    { duration: '5s', target: 20 },   // Warm-up to 20 users
    { duration: '20s', target: 50 },  // Sustained load with 50 users
    { duration: '5s', target: 0 },    // Cool down
  ],
  thresholds: {
    error_rate: ['rate<0.05'],         // < 5% error rate
    http_req_duration: ['p(95)<400'],  // 95% latency under 400ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup creates multiple users and accounts to eliminate single-row lock contention
export function setup() {
  const users = [];
  const numUsers = 5;

  for (let i = 0; i < numUsers; i++) {
    const timestamp = Date.now() + '_' + i;
    const username = `k6_user_${timestamp}`;
    const email = `${username}@titan.com`;
    const password = 'Password123!';
    const pin = '1234';

    // 1. Register User
    const regRes = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({
        firstName: 'Tester',
        lastName: `User${i}`,
        username: username,
        email: email,
        password: password,
        pin: pin,
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    let token = '';
    try {
      token = regRes.json().token;
    } catch (e) {
      console.error(`Register failed for ${username}: ${regRes.body}`);
      continue;
    }

    const authHeaders = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    };

    // 2. Create Account for this user
    const accRes = http.post(
      `${BASE_URL}/api/v1/accounts`,
      JSON.stringify({
        accountType: i % 2 === 0 ? 'SAVINGS' : 'CHECKING',
        currency: 'USD',
        initialDeposit: 5000000.0,
      }),
      { headers: authHeaders }
    );

    try {
      const acc = accRes.json();
      users.push({
        username: username,
        token: token,
        accountNumber: acc.accountNumber,
        pin: pin,
      });
    } catch (e) {
      console.error(`Account creation failed: ${accRes.body}`);
    }
  }

  console.log(`[k6 setup] Successfully initialized ${users.length} bank users and accounts`);
  return { users: users };
}

export default function (data) {
  if (!data.users || data.users.length < 2) {
    return;
  }

  // Pick a random user for this execution
  const senderIndex = Math.floor(Math.random() * data.users.length);
  let receiverIndex = Math.floor(Math.random() * data.users.length);
  while (receiverIndex === senderIndex) {
    receiverIndex = Math.floor(Math.random() * data.users.length);
  }

  const sender = data.users[senderIndex];
  const receiver = data.users[receiverIndex];

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${sender.token}`,
  };

  const action = Math.random();

  if (action < 0.5) {
    // 50% Reads: Get My Accounts & Balances
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/accounts`, { headers });
    readDuration.add(Date.now() - start);

    const ok = check(res, { 'get accounts status 200': (r) => r.status === 200 });
    if (ok) {
      readTpsCounter.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  } else if (action < 0.75) {
    // 25% Writes: Direct Deposit
    const depositPayload = JSON.stringify({
      toAccountNumber: sender.accountNumber,
      amount: 10.0,
      pin: sender.pin,
      note: 'k6 test deposit',
    });

    const res = http.post(`${BASE_URL}/api/v1/transactions/deposit`, depositPayload, { headers });
    const ok = check(res, { 'deposit status 200': (r) => r.status === 200 });
    if (ok) {
      depositTpsCounter.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  } else {
    // 25% Writes: Atomic Inter-Account Fund Transfer
    const transferPayload = JSON.stringify({
      fromAccountNumber: sender.accountNumber,
      toAccountNumber: receiver.accountNumber,
      amount: 5.0,
      pin: sender.pin,
      note: 'k6 test transfer',
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/v1/transactions/transfer`, transferPayload, { headers });
    transferDuration.add(Date.now() - start);

    const ok = check(res, { 'transfer status 200': (r) => r.status === 200 });
    if (ok) {
      transferTpsCounter.add(1);
      totalTransactions.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }
  }

  sleep(0.01);
}
