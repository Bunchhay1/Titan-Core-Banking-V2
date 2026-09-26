import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
export const totalTransfers = new Counter('successful_transfers_count');
export const failedTransfers = new Counter('failed_transfers_count');
export const transferErrorRate = new Rate('transfer_error_rate');
export const transferDuration = new Trend('transfer_duration_ms', true);

// Test configuration: ramping from 10 to 30 concurrent transfer users
export const options = {
  stages: [
    { duration: '3s', target: 10 },   // Ramp-up to 10 VUs
    { duration: '15s', target: 30 },  // Sustained load with 30 VUs doing cross-transfers
    { duration: '3s', target: 0 },    // Cool-down
  ],
  thresholds: {
    transfer_error_rate: ['rate<0.02'],   // < 2% error rate
    transfer_duration_ms: ['p(95)<300'],  // 95% of transfers under 300ms
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Setup initializes 10 users and accounts
export function setup() {
  const users = [];
  const userCount = 8;

  for (let i = 0; i < userCount; i++) {
    const timestamp = Date.now() + '_' + i;
    const username = `k6_tx_user_${timestamp}`;
    const password = 'Password123!';
    const pin = '1234';

    // 1. Register User
    const regRes = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({
        firstName: 'Bank',
        lastName: `Customer${i}`,
        username: username,
        email: `${username}@titanbank.com`,
        password: password,
        pin: pin,
      }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    let token = '';
    try {
      token = regRes.json().token;
    } catch (e) {
      console.error(`Register failed: ${regRes.body}`);
      continue;
    }

    const authHeaders = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    };

    // 2. Create Account with initial deposit
    const accRes = http.post(
      `${BASE_URL}/api/v1/accounts`,
      JSON.stringify({
        accountType: 'SAVINGS',
        currency: 'USD',
        initialDeposit: 1000000.0,
      }),
      { headers: authHeaders }
    );

    const acc = accRes.json();
    users.push({
      id: acc.id,
      accountNumber: acc.accountNumber,
      username: username,
      token: token,
      pin: pin,
    });
  }

  console.log(`[k6 setup] Initialized ${users.length} accounts for bidirectional transfer testing`);
  return { users: users };
}

export default function (data) {
  if (!data.users || data.users.length < 2) return;

  // Pick sender and receiver
  const senderIdx = Math.floor(Math.random() * data.users.length);
  let receiverIdx = Math.floor(Math.random() * data.users.length);
  while (receiverIdx === senderIdx) {
    receiverIdx = Math.floor(Math.random() * data.users.length);
  }

  const sender = data.users[senderIdx];
  const receiver = data.users[receiverIdx];

  const payload = JSON.stringify({
    fromAccountNumber: sender.accountNumber,
    toAccountNumber: receiver.accountNumber,
    amount: 10.0,
    pin: sender.pin,
    note: `Transfer from ${sender.accountNumber} to ${receiver.accountNumber}`,
  });

  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${sender.token}`,
  };

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/v1/transactions/transfer`, payload, { headers });
  const latency = Date.now() - start;
  transferDuration.add(latency);

  const isSuccess = check(res, {
    'transfer status is 200': (r) => r.status === 200,
    'transfer response has SUCCESS status': (r) => {
      try {
        return r.json().status === 'SUCCESS';
      } catch (e) {
        return false;
      }
    },
  });

  if (isSuccess) {
    totalTransfers.add(1);
    transferErrorRate.add(0);
  } else {
    failedTransfers.add(1);
    transferErrorRate.add(1);
    console.error(`Transfer error (${res.status}): ${res.body}`);
  }

  // Small pacing
  sleep(0.01);
}
