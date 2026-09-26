import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

// Custom Metrics
export const totalOps = new Counter('total_operations');
export const transferCount = new Counter('transfers_successful');
export const qrPaymentsCount = new Counter('qr_payments_successful');
export const idorBlockedCount = new Counter('idor_attacks_blocked_403');
export const errorRate = new Rate('error_rate');

export const transferLatency = new Trend('transfer_latency_ms', true);
export const qrLatency = new Trend('qr_latency_ms', true);
export const accountReadLatency = new Trend('account_read_latency_ms', true);

export const options = {
  stages: [
    { duration: '5s', target: 20 },   // Warm-up ramp
    { duration: '20s', target: 60 },  // Heavy concurrent load
    { duration: '5s', target: 0 },    // Cool down
  ],
  thresholds: {
    error_rate: ['rate<0.05'],         // < 5% error rate across entire system
    http_req_duration: ['p(95)<500'],  // 95% of requests under 500ms
    idor_attacks_blocked_403: ['count>0'], // Ensure IDOR protections actively block unauthorized attempts
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const users = [];
  const numUsers = 8;

  for (let i = 0; i < numUsers; i++) {
    const timestamp = Date.now() + '_' + i;
    const username = `k6_banker_${timestamp}`;
    const email = `${username}@titanbank.com`;
    const password = 'Password123!';
    const pin = '1234';

    // 1. Register User
    const regRes = http.post(
      `${BASE_URL}/api/v1/auth/register`,
      JSON.stringify({
        firstName: 'Tester',
        lastName: `Banker${i}`,
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

    // 2. Create Primary Account
    const accRes = http.post(
      `${BASE_URL}/api/v1/accounts`,
      JSON.stringify({
        accountType: i % 2 === 0 ? 'SAVINGS' : 'CHECKING',
        currency: 'USD',
        initialDeposit: 10000000.0,
      }),
      { headers: authHeaders }
    );

    let accData;
    try {
      accData = accRes.json();
    } catch (e) {
      console.error(`Account creation failed: ${accRes.body}`);
      continue;
    }

    // 3. Generate QR code for payee
    const qrRes = http.post(
      `${BASE_URL}/api/v1/qr/generate`,
      JSON.stringify({
        payeeAccountNumber: accData.accountNumber,
        amount: 25.0,
        note: `k6 setup QR for user ${i}`,
        ttlMinutes: 60,
      }),
      { headers: authHeaders }
    );

    let qrCode = null;
    try {
      qrCode = qrRes.json().qrCode;
    } catch (e) {}

    users.push({
      id: accData.id,
      username: username,
      token: token,
      accountNumber: accData.accountNumber,
      pin: pin,
      qrCode: qrCode,
    });
  }

  console.log(`[k6 setup] Successfully initialized ${users.length} full-system bank users & accounts`);
  return { users: users };
}

export default function (data) {
  if (!data.users || data.users.length < 2) {
    return;
  }

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

  const rand = Math.random();

  if (rand < 0.30) {
    // 1. FUND TRANSFER (Deterministic Locking Under High Concurrency)
    const transferPayload = JSON.stringify({
      fromAccountNumber: sender.accountNumber,
      toAccountNumber: receiver.accountNumber,
      amount: 10.0,
      pin: sender.pin,
      note: 'k6 concurrent transfer',
    });

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/v1/transactions/transfer`, transferPayload, { headers });
    transferLatency.add(Date.now() - start);

    const ok = check(res, {
      'transfer 200 OK': (r) => r.status === 200,
    });

    if (ok) {
      transferCount.add(1);
      totalOps.add(1);
      errorRate.add(0);
    } else {
      console.warn(`Transfer failed [${res.status}]: ${res.body}`);
      errorRate.add(1);
    }

  } else if (rand < 0.50) {
    // 2. READ OPERATIONS & BALANCE CHECKS
    const start = Date.now();
    const res = http.get(`${BASE_URL}/api/v1/accounts`, { headers });
    accountReadLatency.add(Date.now() - start);

    const ok = check(res, {
      'get accounts 200 OK': (r) => r.status === 200,
      'accounts array returned': (r) => Array.isArray(r.json()),
    });

    if (ok) {
      totalOps.add(1);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }

  } else if (rand < 0.70) {
    // 3. IDOR SECURITY VERIFICATION: Sender tries to access Receiver's account directly by ID
    // Expected: 403 FORBIDDEN (Protected by @PreAuthorize @accountSecurity.isAccountOwner)
    const forbiddenRes = http.get(`${BASE_URL}/api/v1/accounts/${receiver.id}`, { headers });
    
    const blocked = check(forbiddenRes, {
      'IDOR probe blocked (403 Forbidden)': (r) => r.status === 403,
    });

    if (blocked) {
      idorBlockedCount.add(1);
      totalOps.add(1);
      errorRate.add(0);
    } else {
      console.error(`SECURITY ALERT: IDOR check failed! Expected 403 but got ${forbiddenRes.status}`);
      errorRate.add(1);
    }

    // Sender accessing their own account: Expected 200 OK
    const ownRes = http.get(`${BASE_URL}/api/v1/accounts/${sender.id}`, { headers });
    check(ownRes, {
      'Own account access (200 OK)': (r) => r.status === 200,
    });

  } else if (rand < 0.85) {
    // 4. DEPOSIT & WITHDRAWAL PIPELINE
    const depositRes = http.post(
      `${BASE_URL}/api/v1/transactions/deposit`,
      JSON.stringify({
        toAccountNumber: sender.accountNumber,
        amount: 50.0,
        pin: sender.pin,
        note: 'k6 deposit',
      }),
      { headers }
    );

    const depositOk = check(depositRes, { 'deposit 200 OK': (r) => r.status === 200 });

    const withdrawRes = http.post(
      `${BASE_URL}/api/v1/transactions/withdraw`,
      JSON.stringify({
        fromAccountNumber: sender.accountNumber,
        amount: 20.0,
        pin: sender.pin,
        note: 'k6 withdrawal',
      }),
      { headers }
    );

    const withdrawOk = check(withdrawRes, { 'withdraw 200 OK': (r) => r.status === 200 });

    if (depositOk && withdrawOk) {
      totalOps.add(2);
      errorRate.add(0);
    } else {
      errorRate.add(1);
    }

  } else {
    // 5. QR CODE PAYMENT LIFECYCLE (Generate -> Pay with Pessimistic Locking)
    // Payee generates QR
    const receiverHeaders = {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${receiver.token}`,
    };

    const genQrRes = http.post(
      `${BASE_URL}/api/v1/qr/generate`,
      JSON.stringify({
        payeeAccountNumber: receiver.accountNumber,
        amount: 15.0,
        note: 'k6 dynamic QR pay',
        ttlMinutes: 10,
      }),
      { headers: receiverHeaders }
    );

    if (genQrRes.status === 200) {
      const qrCodeToken = genQrRes.json().qrCode;

      // Sender scans and pays with PIN
      const start = Date.now();
      const payQrRes = http.post(
        `${BASE_URL}/api/v1/qr/pay`,
        JSON.stringify({
          qrCode: qrCodeToken,
          payerAccountNumber: sender.accountNumber,
          amount: 15.0,
          pin: sender.pin,
        }),
        { headers }
      );
      qrLatency.add(Date.now() - start);

      const qrOk = check(payQrRes, {
        'QR pay 200 OK': (r) => r.status === 200,
        'QR status COMPLETED': (r) => r.json().status === 'COMPLETED',
      });

      if (qrOk) {
        qrPaymentsCount.add(1);
        totalOps.add(1);
        errorRate.add(0);
      } else {
        errorRate.add(1);
      }
    }
  }

  sleep(0.02);
}
