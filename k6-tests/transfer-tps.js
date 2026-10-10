import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const transferCount = new Counter('transfers_total');
export const transferErrorRate = new Rate('transfer_error_rate');
export const transferLatency = new Trend('transfer_latency_ms', true);

// Constant arrival rate / high concurrency test to push maximum Transfer TPS
export const options = {
  scenarios: {
    constant_request_rate: {
      executor: 'constant-arrival-rate',
      rate: 200, // target 200 transfers per second
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 30,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    transfer_latency_ms: ['p(95)<300'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const uniqueId = Date.now();
  const username = `k6_transfer_${uniqueId}`;
  const email = `${username}@titan.test`;
  const password = 'Password123!';
  const pin = '1234';

  // 1. Register User
  const regRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      firstName: 'K6',
      lastName: 'TransferTester',
      username: username,
      email: email,
      password: password,
      pin: pin,
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const token = regRes.json().token;
  const authHeaders = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };

  // 2. Create Account A (From)
  const accRes1 = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({
      accountType: 'SAVINGS',
      currency: 'USD',
      initialDeposit: 10000000.0,
    }),
    { headers: authHeaders }
  );
  const acc1 = accRes1.json();

  // 3. Create Account B (To)
  const accRes2 = http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({
      accountType: 'CHECKING',
      currency: 'USD',
      initialDeposit: 10000000.0,
    }),
    { headers: authHeaders }
  );
  const acc2 = accRes2.json();

  return {
    token: token,
    fromAccount: acc1.accountNumber,
    toAccount: acc2.accountNumber,
  };
}

export default function (data) {
  const payload = JSON.stringify({
    fromAccountNumber: data.fromAccount,
    toAccountNumber: data.toAccount,
    amount: 1.0,
    pin: '1234',
    note: 'k6 high-tps transfer',
  });

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/transactions/transfer`, payload, { headers });
  transferLatency.add(Date.now() - start);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (ok) {
    transferCount.add(1);
    transferErrorRate.add(0);
  } else {
    transferErrorRate.add(1);
  }
}
