import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { SharedArray } from 'k6/data';

const prompts = new SharedArray('gateway prompts', () => open('./dataset.jsonl').trim().split('\n').map(JSON.parse));
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'chaos-key';
const ADMIN_TOKEN = __ENV.ADMIN_TOKEN || 'local-dev-only';
const chatErrors = new Counter('chat_errors');

export const options = {
  scenarios: {
    traffic: {executor:'constant-arrival-rate',exec:'traffic',rate:Number(__ENV.RATE || 20),timeUnit:'1s',duration:'100s',preAllocatedVUs:50,maxVUs:120},
    disable_primary: {executor:'per-vu-iterations',exec:'disablePrimary',vus:1,iterations:1,startTime:'30s'},
    disable_secondary: {executor:'per-vu-iterations',exec:'disableSecondary',vus:1,iterations:1,startTime:'60s'},
  },
  thresholds: {chat_errors:['count==0']},
};

function setNode(id, enabled) {
  const response = http.post(`${BASE_URL}/admin/providers/${id}/state`,JSON.stringify({enabled}),{
    headers:{'Content-Type':'application/json','X-Admin-Token':ADMIN_TOKEN},
    tags:{kind:'control'},
  });
  if (response.status !== 200) throw new Error(`${id} toggle failed: ${response.status} ${response.body}`);
}

export function setup() {
  setNode('primary',true);
  setNode('secondary',true);
}

export function traffic() {
  const item = prompts[(__ITER + __VU * 17) % prompts.length];
  const response = http.post(`${BASE_URL}/v1/chat/completions`,JSON.stringify({model:'mock-1',temperature:0,messages:[{role:'user',content:item.prompt}]}),{
    headers:{'Content-Type':'application/json','Authorization':`Bearer ${API_KEY}`,'X-Cache-Bypass':'true'},
    tags:{kind:'chaos-chat'},
  });
  if (response.status !== 200) chatErrors.add(1);
  check(response, {'no dropped client request':(result) => result.status === 200});
}

export function disablePrimary() {setNode('primary',false);}
export function disableSecondary() {setNode('secondary',false);}

export function teardown() {
  setNode('primary',true);
  setNode('secondary',true);
}
