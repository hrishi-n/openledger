// Runs sustained transfers between two wallets, then checks reconciliation.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE = __ENV.BASE || "http://localhost:8080";
const SOURCE = __ENV.SOURCE;
const SINK = __ENV.SINK;
const CURRENCY = __ENV.CURRENCY || "USD";
const TENANT = __ENV.TENANT || "11111111-1111-1111-1111-111111111111";

export const options = {
  scenarios: {
    transfers: {
      executor: "ramping-vus",
      startVUs: 5,
      stages: [
        { duration: "30s", target: 50 },
        { duration: "2m", target: 50 },
        { duration: "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(99)<250"],
  },
};

export default function () {
  const res = http.post(
    `${BASE}/transfers`,
    JSON.stringify({
      sourceAccountId: SOURCE,
      destinationAccountId: SINK,
      amount: 1,
      currency: CURRENCY,
    }),
    { headers: { "Content-Type": "application/json", "X-Tenant-Id": TENANT } },
  );
  check(res, { "transfer posted": (r) => r.status === 201 });
  sleep(0.1);
}

export function teardown() {
  const res = http.post(`${BASE}/reconciliation/run`, null, {
    headers: { "X-Tenant-Id": TENANT },
  });
  check(res, { "ledger reconciles with zero drift": (r) => r.status === 200 });
  console.log(`reconciliation: ${res.body}`);
}
