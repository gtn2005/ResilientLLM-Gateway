const $ = (id) => document.getElementById(id);
const state = {token: sessionStorage.getItem('strata-admin-token') || ((location.hostname === 'localhost' || location.hostname === '127.0.0.1') ? 'local-dev-only' : ''), providers: [], snapshot: null, busy: false};

function setConnection(online, label) {
  $('connectionBadge').classList.toggle('offline', !online);
  $('connectionLabel').textContent = label;
}

function tickClock() {
  $('clock').textContent = new Date().toISOString().slice(11, 19);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (character) => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[character]));
}

function number(value, decimals = 0) {
  return Number(value || 0).toFixed(decimals);
}

function renderMetrics(telemetry) {
  $('rpsValue').textContent = number(telemetry.rps, 1);
  $('p95Value').textContent = number(telemetry.p95Ms);
  $('cacheValue').textContent = number(telemetry.cacheHitRate, 1);
  $('savedValue').textContent = number(telemetry.reduction, 1);
  $('savedCount').textContent = number(telemetry.cacheHits);
  $('chartTotal').textContent = `${number(telemetry.requests)} TOTAL REQUESTS`;
  $('latencyFill').style.width = `${Math.min(100, (telemetry.p95Ms || 0) / 1000 * 100)}%`;
  const series = telemetry.series || [];
  const bars = series.slice(-22);
  const max = Math.max(1, ...bars.map((point) => point.value));
  $('rpsBars').innerHTML = bars.map((point) => `<i style="height:${Math.max(7, point.value / max * 100)}%"></i>`).join('');
  const segments = Math.max(0, Math.min(20, Math.round((telemetry.cacheHitRate || 0) / 5)));
  $('cacheSegments').innerHTML = Array.from({length:20}, (_, index) => `<i class="${index < segments ? 'on' : ''}"></i>`).join('');
  renderChart(series);
  renderActivity(telemetry.events || []);
}

function renderChart(series) {
  if (!series.length) return;
  const max = Math.max(5, ...series.map((point) => point.value));
  $('chartMax').textContent = max;
  const coords = series.map((point, index) => {
    const x = index / Math.max(1, series.length - 1) * 1000;
    const y = 210 - point.value / max * 195;
    return [x, y];
  });
  const line = coords.map(([x, y], index) => `${index ? 'L' : 'M'}${x.toFixed(1)} ${y.toFixed(1)}`).join(' ');
  $('chartLine').setAttribute('d', line);
  $('chartArea').setAttribute('d', `${line} L1000 210 L0 210 Z`);
}

function renderProviders(providers) {
  state.providers = providers;
  $('providerGrid').innerHTML = providers.map((provider, index) => {
    const enabled = provider.enabled !== false;
    const open = provider.circuit === 'OPEN';
    const status = !enabled ? 'DISABLED' : open ? 'OPEN' : provider.circuit || 'UNKNOWN';
    return `<article class="provider-card ${!enabled ? 'disabled' : open ? 'open' : ''}" data-index="0${index + 1}">
      <div class="provider-head"><span class="provider-priority">PRIORITY 0${index + 1} / ${index === 0 ? 'PRIMARY' : index === 1 ? 'BACKUP' : 'FALLBACK'}</span><span class="provider-state"><i></i>${escapeHtml(status)}</span></div>
      <div class="provider-name">${escapeHtml(provider.id)}</div><div class="provider-model">${escapeHtml(provider.type.toUpperCase())} // ${escapeHtml(provider.model || 'DEFAULT')}</div>
      <div class="provider-footer"><span>FAILURE RATE <strong>${provider.failureRate < 0 ? 'N/A' : number(provider.failureRate, 0) + '%'}</strong></span><button class="provider-toggle" data-provider="${escapeHtml(provider.id)}" data-enabled="${enabled}" ${provider.type !== 'mock' ? 'disabled title="Real provider controls unavailable"' : ''}>${enabled ? 'DISABLE NODE' : 'RESTORE NODE'}</button></div>
    </article>`;
  }).join('');
  document.querySelectorAll('.provider-toggle').forEach((button) => button.addEventListener('click', () => toggleProvider(button)));
}

function renderActivity(events) {
  if (!events.length) {
    $('activityRows').innerHTML = '<div class="empty-state">No requests recorded. Launch a request to wake the system.</div>';
    return;
  }
  $('activityRows').innerHTML = events.map((event) => {
    const time = new Date(event.time).toISOString().slice(11, 19);
    const okay = event.status < 500;
    return `<div class="activity-row"><span>${time}</span><span>${escapeHtml(event.provider.toUpperCase())}</span><span class="${event.cache === 'hit' ? 'hit' : ''}">${escapeHtml(event.cache.toUpperCase())}</span><span>${number(event.latencyMs)} MS</span><span class="${okay ? 'ok' : 'bad'}">${event.status}</span></div>`;
  }).join('');
}

async function refresh() {
  if (!state.token || state.busy) return;
  state.busy = true;
  try {
    const response = await fetch('/admin/status', {headers:{'X-Admin-Token':state.token}, cache:'no-store'});
    if (!response.ok) throw new Error(response.status === 401 ? 'Invalid admin token' : `Gateway returned ${response.status}`);
    const status = await response.json();
    state.snapshot = status;
    renderMetrics(status.telemetry);
    renderProviders(status.providers);
    setConnection(true, 'SYSTEM LIVE');
    $('connectionError').textContent = '';
  } catch (error) {
    setConnection(false, 'LINK LOST');
    $('connectionError').textContent = error.message;
  } finally {
    state.busy = false;
  }
}

async function toggleProvider(button) {
  const id = button.dataset.provider;
  const enabled = button.dataset.enabled === 'true';
  button.disabled = true;
  button.textContent = 'UPDATING...';
  try {
    const response = await fetch(`/admin/providers/${encodeURIComponent(id)}/state`, {
      method:'POST', headers:{'Content-Type':'application/json','X-Admin-Token':state.token}, body:JSON.stringify({enabled:!enabled})
    });
    if (!response.ok) throw new Error(`Control request failed: ${response.status}`);
    await refresh();
  } catch (error) {
    button.textContent = 'RETRY CONTROL';
    button.disabled = false;
    $('responseText').textContent = error.message;
  }
}

async function sendRequest() {
  const button = $('sendButton');
  const prompt = $('prompt').value.trim();
  const apiKey = $('apiKey').value.trim();
  if (!prompt || !apiKey) {
    $('responseText').textContent = 'Enter a message and bearer API key.';
    return;
  }
  button.disabled = true;
  button.innerHTML = 'TRANSMITTING <span>◌</span>';
  $('responseMeta').textContent = 'IN FLIGHT';
  const started = performance.now();
  try {
    const response = await fetch('/v1/chat/completions', {
      method:'POST', headers:{'Content-Type':'application/json','Authorization':`Bearer ${apiKey}`,'X-Cache-Bypass':$('cacheBypass').checked ? 'true' : 'false'},
      body:JSON.stringify({model:'mock-1',temperature:0,messages:[{role:'user',content:prompt}]})
    });
    const data = await response.json();
    $('responseMeta').textContent = `${response.status} / ${response.headers.get('X-Provider') || 'NONE'} / ${response.headers.get('X-Cache') || 'SKIP'} / ${Math.round(performance.now() - started)} MS`;
    $('responseText').textContent = data.choices?.[0]?.message?.content || JSON.stringify(data, null, 2);
  } catch (error) {
    $('responseMeta').textContent = 'TRANSMISSION FAILED';
    $('responseText').textContent = error.message;
  } finally {
    button.disabled = false;
    button.innerHTML = 'EXECUTE REQUEST <span>↗</span>';
    refresh();
  }
}

$('connectButton').addEventListener('click', () => {
  $('adminToken').value = state.token;
  $('connectionDialog').showModal();
});
$('saveConnection').addEventListener('click', async () => {
  state.token = $('adminToken').value.trim();
  if (state.token) sessionStorage.setItem('strata-admin-token', state.token);
  else sessionStorage.removeItem('strata-admin-token');
  await refresh();
  if (!$('connectionBadge').classList.contains('offline')) $('connectionDialog').close();
});
$('adminToken').addEventListener('keydown', (event) => {if (event.key === 'Enter') {event.preventDefault();$('saveConnection').click();}});
$('sendButton').addEventListener('click', sendRequest);
tickClock();
setInterval(tickClock, 1000);
refresh();
setInterval(refresh, 3000);
