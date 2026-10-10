import http from 'k6/http';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

export const readCount = new Counter('total_reads');
export const readLatency = new Trend('read_latency_ms', true);

export const options = {
  scenarios: {
    high_tps_reads: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 150,
      stages: [
        { duration: '5s', target: 200 },   // ramp up to 200 req/s
        { duration: '10s', target: 500 },  // push to 500 req/s
        { duration: '5s', target: 100 },   // ramp down
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<100'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
  const timestamp = Date.now();
  const username = `k6_read_${timestamp}`;
  const regRes = http.post(
    `${BASE_URL}/api/v1/auth/register`,
    JSON.stringify({
      firstName: 'Reader',
      lastName: 'User',
      username: username,
      email: `${username}@titan.com`,
      password: 'Password123!',
      pin: '1234',
    }),
    { headers: { 'Content-Type': 'application/json' } }
  );

  const token = regRes.json().token;
  const authHeaders = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };

  http.post(
    `${BASE_URL}/api/v1/accounts`,
    JSON.stringify({
      accountType: 'SAVINGS',
      currency: 'USD',
      initialDeposit: 1000.0,
    }),
    { headers: authHeaders }
  );

  return { token: token };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${data.token}`,
  };

  const start = Date.now();
  const res = http.get(`${BASE_URL}/api/v1/accounts`, { headers });
  readLatency.add(Date.now() - start);

  const ok = check(res, {
    'status is 200': (r) => r.status === 200,
  });

  if (ok) {
    readCount.add(1);
  }
}
