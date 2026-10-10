import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom metrics
export const successfulTransfers = new Counter('successful_transfers');
export const failedTransfers = new Counter('failed_transfers');
export const transferDuration = new Trend('transfer_duration_ms', true);
export const errorRate = new Rate('error_rate');

// Test options: 30-second benchmark ramping up to measure real TPS
export const options = {
  stages: [
    { duration: '5s', target: 20 },   // Warm-up to 20 VUs
    { duration: '15s', target: 50 },  // Sustained load at 50 VUs
    { duration: '5s', target: 100 },  // Peak load at 100 VUs
    { duration: '5s', target: 0 },    // Cool-down
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],     // http errors < 5%
    http_req_duration: ['p(95)<500'],   // 95% of requests under 500ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup phase runs ONCE before VUs start: registers/logs in user and creates accounts
export function setup() {
  const uniqueId = Date.now();
  const username = `k6_perf_${uniqueId}`;
  const email = `${username}@titan.test`;
  const password = 'Password123!';
  const pin = '1234';

  // 1. Register User
  const registerPayload = JSON.stringify({
    firstName: 'K6',
    lastName: 'Tester',
    username: username,
    email: email,
    password: password,
    pin: pin,
  });

  const regRes = http.post(`${BASE_URL}/api/v1/auth/register`, registerPayload, {
    headers: { 'Content-Type': 'application/json' },
  });

  check(regRes, {
    'registered successfully': (r) => r.status === 200 || r.status === 201,
  });

  let token = '';
  try {
    const regJson = regRes.json();
    token = regJson.token;
  } catch (e) {
    console.error('Failed to parse register response: ' + regRes.body);
  }

  // 2. Create Account A (From Account)
  const authHeaders = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };

  const accRes1 = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({
      accountType: 'SAVINGS',
      currency: 'USD',
      initialDeposit: 5000000.0,
    }),
    { headers: authHeaders }
  );

  const acc1 = accRes1.json();

  // 3. Create Account B (To Account)
  const accRes2 = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({
      accountType: 'CHECKING',
      currency: 'USD',
      initialDeposit: 5000000.0,
    }),
    { headers: authHeaders }
  );

  const acc2 = accRes2.json();

  console.log(`[k6 setup] User: ${username}`);
  console.log(`[k6 setup] Account A: ${acc1.accountNumber}, Account B: ${acc2.accountNumber}`);

  return {
    token: token,
    fromAccount: acc1.accountNumber,
    toAccount: acc2.accountNumber,
  };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };

  // 70% Read operations (Get Accounts / Balance), 30% Transfers
  const rand = Math.random();

  if (rand < 0.7) {
    // Read accounts (Read TPS)
    const res = http.get(`${BASE_URL}/api/v1/accounts`, { headers });
    const success = check(res, {
      'get accounts status 200': (r) => r.status === 200,
    });
    errorRate.add(!success);
  } else {
    // Execute Transfer (Transactional Write TPS)
    const transferPayload = JSON.stringify({
      fromAccountNumber: data.fromAccount,
      toAccountNumber: data.toAccount,
      amount: 1.0,
      pin: '1234',
      note: 'k6 load test',
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/v1/transactions/transfer`, transferPayload, { headers });
    const duration = Date.now() - start;
    transferDuration.add(duration);

    const success = check(res, {
      'transfer status 200': (r) => r.status === 200,
    });

    if (success) {
      successfulTransfers.add(1);
      errorRate.add(0);
    } else {
      failedTransfers.add(1);
      errorRate.add(1);
    }
  }

  // Small pacing between requests (10ms)
  sleep(0.01);
}
