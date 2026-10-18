import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';

const prompts = new SharedArray('gateway prompts', () => open('./dataset.jsonl').trim().split('\n').map(JSON.parse));
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'bench-key';
const RATE = Number(__ENV.RATE || 30);
const DURATION = __ENV.DURATION || '5m';

export const options = {
  scenarios: {
    sustained: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.max(40, RATE * 2),
      maxVUs: Math.max(100, RATE * 5),
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const item = prompts[(__ITER + __VU * 37) % prompts.length];
  const body = JSON.stringify({model:'mock-1',temperature:0,messages:[{role:'user',content:item.prompt}]});
  const response = http.post(`${BASE_URL}/v1/chat/completions`, body, {
    headers:{'Content-Type':'application/json','Authorization':`Bearer ${API_KEY}`},
    tags:{kind:item.kind, family:item.family},
  });
  check(response, {'chat completion returned 200': (result) => result.status === 200});
}
