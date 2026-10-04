#!/usr/bin/env node
/**
 * Burst test for the seat reservation API. Zero dependencies: Node 18+ only.
 *
 * One wave of concurrent POST /shows/{id}/reserve requests against a fresh show:
 *   - storm:   many users fight over a few hot seats (exactly one winner per seat, everyone else 409)
 *   - retries: some storm requests are sent twice with the same idempotency key (never two 201s)
 *   - greedy:  one user fires many parallel requests for distinct seats (per-user limit must hold)
 * Then it checks the final show state, idempotent replay / key reuse, and the Prometheus counters.
 *
 * Usage:
 *   node burst/burst.mjs <base-url> [--requests 20000] [--concurrency 1000] [--hot-seats 10]
 *                                   [--users 2000] [--retry-rate 0.1] [--greedy 20] [--timeout-ms 60000]
 *
 * ADMIN_SECRET is read from the environment, falling back to the repo's .env. It is never printed.
 * Exit code is 0 only if every check passes.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import https from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const DEFAULTS = {
  requests: 20000,
  concurrency: 1000,
  hotSeats: 10,
  users: 2000,
  retryRate: 0.1,
  greedy: 20,
  timeoutMs: 60000,
};
const PRICE_PAISE = 25000;
const PER_USER_LIMIT = 4;
const READINESS_WAIT_MS = 180000;
const TOKEN_CONCURRENCY = 50;
const SETUP_ATTEMPTS = 5;
const MAX_BUSY_RETRIES = 5;
const MAX_TRANSIENT_RETRIES = 3;
const DEFAULT_RETRY_AFTER_MS = 1000;
const TRANSIENT_BACKOFF_MS = 1000;
const RESULTS_FILE = join(dirname(fileURLToPath(import.meta.url)), 'last-run.json');
const EXPECTED_DECLINES = new Set(['seat_taken', 'per_user_limit']);

const config = parseArgs(process.argv.slice(2));
const runId = Date.now().toString(36);
const agent = new (config.baseUrl.startsWith('https') ? https : http).Agent({
  keepAlive: true,
  maxSockets: config.concurrency,
});
const checks = [];

main().catch((error) => {
  console.error(`\nBurst aborted: ${error.message}`);
  process.exit(2);
});

async function main() {
  console.log(`Target ${config.baseUrl}  run ${runId}`);
  await waitUntilReady();

  const adminToken = await mintToken(`burst-admin-${runId}`, 'admin', loadAdminSecret());
  const hotSeats = labels('H', config.hotSeats);
  const greedySeats = labels('G', config.greedy);
  const showId = await createShow(adminToken, [...hotSeats, ...greedySeats]);
  console.log(`Show ${showId}: ${hotSeats.length} hot seats, ${greedySeats.length} greedy seats, limit ${PER_USER_LIMIT}`);

  const users = Array.from({ length: config.users }, (_, i) => `u${i}-${runId}`);
  const greedyUser = `greedy-${runId}`;
  console.log(`Minting ${users.length + 1} user tokens...`);
  const tokens = await mintTokens([...users, greedyUser]);

  const plan = buildPlan(users, greedyUser, hotSeats, greedySeats);
  const metricsBefore = await scrapeMetrics();

  console.log(`Firing ${plan.length} reserve requests, ${config.concurrency} in flight...`);
  const startedAt = performance.now();
  const results = await runPool(plan, config.concurrency, (task) => reserve(showId, tokens.get(task.user), task));
  const elapsedMs = performance.now() - startedAt;
  reportSendRate(results, startedAt);

  const metricsAfter = await scrapeMetrics();
  const show = await getJson(`/shows/${showId}`);

  reportTraffic(results, elapsedMs);
  saveResults(results);
  checkNoServerErrors(results);
  checkDeclinesAreClean(results);
  checkNobodyLeftBusy(results);
  checkOneWinnerPerHotSeat(results, hotSeats);
  checkRetriesNeverDoubleBook(results);
  checkPerUserLimit(results, greedyUser);
  checkShowState(show, hotSeats, results, greedyUser);
  await checkReplayAndKeyReuse(showId, tokens, results);
  checkMetrics(metricsBefore, metricsAfter, results);

  const failed = checks.filter((check) => !check.passed);
  console.log(`\n${failed.length === 0 ? 'ALL CHECKS PASSED' : `${failed.length} CHECK(S) FAILED`}`);
  agent.destroy();
  process.exit(failed.length === 0 ? 0 : 1);
}

// ---------------------------------------------------------------- plan

function buildPlan(users, greedyUser, hotSeats, greedySeats) {
  const plan = [];
  const stormSize = config.requests - greedySeats.length;
  for (let i = 0; plan.length < stormSize; i++) {
    const task = { kind: 'storm', user: users[i % users.length], key: `storm-${i}`, seats: [pick(hotSeats)] };
    plan.push(task);
    if (plan.length < stormSize && Math.random() < config.retryRate) {
      plan.push({ ...task, kind: 'retry' });
    }
  }
  greedySeats.forEach((seat, i) => plan.push({ kind: 'greedy', user: greedyUser, key: `greedy-${i}`, seats: [seat] }));
  return shuffle(plan);
}

// ---------------------------------------------------------------- checks

/**
 * Every attempt counts here, including ones retried away: a 5xx carrying the application's JSON error body
 * came from the app; anything else (502/520 HTML, connection reset) was produced by the network or proxy.
 */
function checkNoServerErrors(results) {
  const attempts = results.flatMap((r) => [...r.transientErrors, r]);
  const appErrors = attempts.filter((a) => a.status >= 500 && a.body?.error);
  record('zero 5xx from the application on any attempt', appErrors.length === 0,
    appErrors.length === 0 ? '0' : `${appErrors.length}, e.g. ${appErrors[0].status} ${JSON.stringify(appErrors[0].body)}`);

  const unanswered = results.filter((r) => r.status === 0 || r.status >= 500);
  const retried = results.filter((r) => r.transientErrors.length > 0).length;
  record(`every request got an answer (up to ${MAX_TRANSIENT_RETRIES} same-key retries on network/proxy errors)`,
    unanswered.length === 0, `${unanswered.length} unanswered, ${retried} needed a retry`);
}

/** Successful answers (201 created or 200 replay) collapsed to one entry per reservation id. */
function distinctReservations(results) {
  const byId = new Map();
  for (const r of results) {
    if ((r.status === 201 || r.status === 200) && r.body?.reservation_id && !byId.has(r.body.reservation_id)) {
      byId.set(r.body.reservation_id, r);
    }
  }
  return [...byId.values()];
}

function checkDeclinesAreClean(results) {
  const unexpected = results.filter((r) => r.status >= 400 && r.status < 500 && r.status !== 429
    && !(r.status === 409 && EXPECTED_DECLINES.has(r.body?.error)));
  record('every decline is 409 seat_taken or per_user_limit', unexpected.length === 0,
    unexpected.length === 0 ? 'ok' : `${unexpected.length} unexpected, e.g. ${unexpected[0].status} ${JSON.stringify(unexpected[0].body)}`);
}

/** 429 is back-pressure, not an answer: a well-behaved client retries after Retry-After, as this script does. */
function checkNobodyLeftBusy(results) {
  const stillBusy = results.filter((r) => r.status === 429).length;
  const busyRetries = results.reduce((sum, r) => sum + r.busyRetries, 0);
  record(`no request still 429 after ${MAX_BUSY_RETRIES} Retry-After retries`, stillBusy === 0,
    `${stillBusy} still busy, ${busyRetries} retries honoured`);
}

function checkOneWinnerPerHotSeat(results, hotSeats) {
  const winners = new Map(hotSeats.map((seat) => [seat, 0]));
  for (const r of distinctReservations(results.filter((r) => r.task.kind !== 'greedy'))) {
    r.task.seats.forEach((seat) => winners.set(seat, winners.get(seat) + 1));
  }
  const counts = [...winners.values()];
  const recovered = results.filter((r) => r.status === 200 && r.transientErrors.length > 0).length;
  record('exactly one winning reservation per hot seat', counts.every((n) => n === 1),
    `winners per seat: ${counts.join(',')}; ${recovered} lost 201s recovered as 200 replays`);
}

function checkRetriesNeverDoubleBook(results) {
  const byKey = groupBy(results.filter((r) => r.task.kind !== 'greedy'), (r) => `${r.task.user}|${r.task.key}`);
  let doubleBooked = 0;
  let doubleCreated = 0;
  for (const attempts of byKey.values()) {
    if (distinctReservations(attempts).length > 1) doubleBooked++;
    if (attempts.filter((r) => r.status === 201).length > 1) doubleCreated++;
  }
  const replays = results.filter((r) => r.status === 200).length;
  record('a retried key never gets two 201s', doubleCreated === 0, `${doubleCreated} keys double-created`);
  record('every 200 replay returns the original reservation', doubleBooked === 0,
    `${replays} replays, ${doubleBooked} keys mapped to more than one reservation`);
}

function checkPerUserLimit(results, greedyUser) {
  const greedy = results.filter((r) => r.task.user === greedyUser);
  const confirmed = distinctReservations(greedy).length;
  const limited = greedy.filter((r) => r.body?.error === 'per_user_limit').length;
  record(`greedy user gets exactly ${PER_USER_LIMIT} seats from ${greedy.length} parallel requests`,
    confirmed === PER_USER_LIMIT, `${confirmed} confirmed, ${limited} per_user_limit`);
}

function checkShowState(show, hotSeats, results, greedyUser) {
  const { available, held, confirmed } = show.counts;
  record('available + held + confirmed == total_seats', available + held + confirmed === show.total_seats,
    `${available} + ${held} + ${confirmed} = ${available + held + confirmed} vs ${show.total_seats}`);

  const won = distinctReservations(results).reduce((sum, r) => sum + r.task.seats.length, 0);
  record('confirmed seats == seats in successful responses', confirmed === won, `${confirmed} vs ${won}`);

  const status = new Map(show.seats.map((seat) => [seat.label, seat.status]));
  const unsold = hotSeats.filter((seat) => status.get(seat) !== 'confirmed');
  record('every hot seat ends confirmed', unsold.length === 0, unsold.length === 0 ? 'ok' : `unsold: ${unsold.join(',')}`);
}

async function checkReplayAndKeyReuse(showId, tokens, results) {
  const winner = distinctReservations(results.filter((r) => r.task.kind !== 'greedy'))[0];
  if (!winner) {
    record('post-burst replay and key reuse', false, 'no winner to replay');
    return;
  }
  const token = tokens.get(winner.task.user);
  const replay = await reserve(showId, token, winner.task);
  record('replaying a winning key returns 200 with the same reservation',
    replay.status === 200 && replay.body?.reservation_id === winner.body.reservation_id, `got ${replay.status}`);

  const otherSeat = winner.task.seats[0] === 'H1' ? 'H2' : 'H1';
  const reused = await reserve(showId, token, { ...winner.task, seats: [otherSeat] });
  record('same key with different seats returns 409 idempotency_key_reused',
    reused.status === 409 && reused.body?.error === 'idempotency_key_reused', `got ${reused.status} ${reused.body?.error}`);
}

function checkMetrics(before, after, results) {
  if (!before || !after) {
    record('metrics endpoint scraped', false, '/metrics unavailable');
    return;
  }
  const restarted = after.get('process_start_time_seconds') !== before.get('process_start_time_seconds');
  record('server did not restart during the burst', !restarted, restarted ? 'process start time changed' : 'ok');

  const delta = (name) => (after.get(name) ?? 0) - (before.get(name) ?? 0);
  const created = distinctReservations(results).length;
  record('reservations_confirmed_total grew by the number of reservations',
    delta('reservations_confirmed_total') === created, `+${delta('reservations_confirmed_total')} vs ${created}`);

  const declined = ['seat_taken', 'per_user_limit', 'idempotent_replay', 'key_reused']
    .map((reason) => `${reason}=+${delta(`reservations_declined_total{reason="${reason}"}`)}`);
  console.log(`  metrics: declined ${declined.join(' ')}  seats_available=${after.get('seats_available')}`);
}

function reportTraffic(results, elapsedMs) {
  const byStatus = groupBy(results, (r) => r.status);
  const byError = groupBy(results.filter((r) => r.body?.error), (r) => `${r.status} ${r.body.error}`);
  const latencies = results.map((r) => r.ms).sort((a, b) => a - b);

  console.log(`\nTraffic: ${results.length} requests in ${(elapsedMs / 1000).toFixed(1)}s `
    + `(${Math.round(results.length / (elapsedMs / 1000))} req/s)`);
  console.log(`  status: ${[...byStatus].map(([status, rs]) => `${status || 'network-error'}=${rs.length}`).join('  ')}`);
  console.log(`  errors: ${[...byError].map(([error, rs]) => `${error}=${rs.length}`).join('  ') || 'none'}`);
  console.log(`  latency ms: p50=${percentile(latencies, 50)} p95=${percentile(latencies, 95)} `
    + `p99=${percentile(latencies, 99)} max=${Math.round(latencies.at(-1))}`);

  const transient = groupBy(results.flatMap((r) => r.transientErrors), (a) => a.error ?? `HTTP ${a.status}`);
  console.log(`  retried network/proxy errors: ${[...transient].map(([error, as]) => `${error}=${as.length}`).join('  ') || 'none'}`);
  console.log('\nChecks:');
}

function saveResults(results) {
  const rows = results.map(({ task, status, body, error, ms, busyRetries, transientErrors }) => ({
    ...task, status, body, error, ms: Math.round(ms), busyRetries, transientErrors,
  }));
  writeFileSync(RESULTS_FILE, JSON.stringify(rows));
  console.log(`  raw results: ${RESULTS_FILE}`);
}

/** How hard the server was hit: requests sent in each one-second window of the burst. */
function reportSendRate(results, startedAt) {
  const perSecond = new Map();
  for (const r of results) {
    const second = Math.floor((r.sentAt - startedAt) / 1000);
    perSecond.set(second, (perSecond.get(second) ?? 0) + 1);
  }
  const counts = [...perSecond.values()];
  const peak = Math.max(...counts);
  const average = Math.round(results.length / perSecond.size);
  console.log(`\nSend rate: ${perSecond.get(0) ?? 0} requests in the first second, peak ${peak}/s, `
    + `average ${average}/s over ${perSecond.size}s, ${config.concurrency} kept in flight`);
}

function record(name, passed, detail) {
  checks.push({ name, passed });
  console.log(`  ${passed ? 'PASS' : 'FAIL'}  ${name}  (${detail})`);
}

// ---------------------------------------------------------------- API calls

async function waitUntilReady() {
  const deadline = Date.now() + READINESS_WAIT_MS;
  while (Date.now() < deadline) {
    const response = await send('GET', '/health/readiness');
    if (response.status === 200) return;
    console.log(`Waiting for readiness (got ${response.status || response.error})...`);
    await sleep(5000);
  }
  throw new Error(`service not ready after ${READINESS_WAIT_MS / 1000}s`);
}

/** Setup, not the measured burst: transient network errors are retried. */
async function mintToken(userId, role, adminSecret) {
  const headers = adminSecret ? { 'X-Admin-Secret': adminSecret } : {};
  let response;
  for (let attempt = 1; attempt <= SETUP_ATTEMPTS; attempt++) {
    response = await send('POST', '/auth/token', { user_id: userId, ...(role && { role }) }, headers);
    if (response.status === 200) return response.body.access_token;
    if (response.status !== 0 && response.status < 500) break;
    await sleep(1000 * attempt);
  }
  throw new Error(`token for ${userId} failed: ${response.status || response.error} ${JSON.stringify(response.body)}`
    + (role === 'admin' ? ' (is ADMIN_SECRET the same as on the server?)' : ''));
}

async function mintTokens(userIds) {
  const tokens = await runPool(userIds, TOKEN_CONCURRENCY, (userId) => mintToken(userId));
  return new Map(userIds.map((userId, i) => [userId, tokens[i]]));
}

async function createShow(adminToken, seats) {
  const response = await send('POST', '/shows', {
    name: `burst-${runId}`,
    seats,
    price_paise: PRICE_PAISE,
    per_user_limit: PER_USER_LIMIT,
  }, auth(adminToken));
  if (response.status !== 201) {
    throw new Error(`create show failed: ${response.status} ${JSON.stringify(response.body)}`);
  }
  return response.body.id;
}

/**
 * Behaves like a well-behaved client: 429 waits for Retry-After; a dropped connection or proxy 5xx is retried
 * with the same Idempotency-Key, which is exactly what the key exists for (a lost 201 comes back as a 200 replay).
 */
async function reserve(showId, token, task) {
  const sentAt = performance.now();
  const transientErrors = [];
  let busyRetries = 0;
  for (;;) {
    const response = await send('POST', `/shows/${showId}/reserve`, { seats: task.seats },
      { ...auth(token), 'Idempotency-Key': task.key });
    const transient = response.status === 0 || response.status >= 500;
    if (response.status === 429 && busyRetries < MAX_BUSY_RETRIES) {
      busyRetries++;
      await sleep((Number(response.headers['retry-after']) * 1000) || DEFAULT_RETRY_AFTER_MS);
    } else if (transient && transientErrors.length < MAX_TRANSIENT_RETRIES) {
      transientErrors.push({ status: response.status, error: response.error, body: response.body });
      await sleep(TRANSIENT_BACKOFF_MS * transientErrors.length);
    } else {
      return { ...response, task, busyRetries, transientErrors, sentAt };
    }
  }
}

async function getJson(path) {
  const response = await send('GET', path);
  if (response.status !== 200) throw new Error(`GET ${path} failed: ${response.status}`);
  return response.body;
}

async function scrapeMetrics() {
  const response = await send('GET', '/metrics', undefined, {}, true);
  if (response.status !== 200) return null;
  const series = new Map();
  for (const line of response.text.split('\n')) {
    const match = /^(reservations_\w+(?:\{[^}]*\})?|seats_available|process_start_time_seconds)\s+(\S+)/.exec(line);
    if (match) series.set(match[1].replace(/,}/, '}'), Number(match[2]));
  }
  return series;
}

// ---------------------------------------------------------------- HTTP

function send(method, path, body, headers = {}, rawText = false) {
  const url = new URL(path, config.baseUrl);
  const payload = body === undefined ? undefined : JSON.stringify(body);
  const client = url.protocol === 'https:' ? https : http;
  const started = performance.now();

  return new Promise((resolve) => {
    const request = client.request(url, {
      method,
      agent,
      timeout: config.timeoutMs,
      headers: {
        Accept: rawText ? 'text/plain' : 'application/json',
        ...(payload && { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(payload) }),
        ...headers,
      },
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        resolve({
          status: response.statusCode,
          headers: response.headers,
          body: rawText ? null : parseJson(text),
          text,
          ms: performance.now() - started,
        });
      });
      response.on('error', (error) => resolve(failure(error, started)));
    });
    request.on('timeout', () => request.destroy(new Error(`timeout after ${config.timeoutMs}ms`)));
    request.on('error', (error) => resolve(failure(error, started)));
    if (payload) request.write(payload);
    request.end();
  });
}

function failure(error, started) {
  return { status: 0, headers: {}, body: null, text: '', error: error.message, ms: performance.now() - started };
}

/** Runs every item through fn with at most `limit` in flight; results keep the input order. */
async function runPool(items, limit, fn) {
  const results = new Array(items.length);
  let next = 0;
  const worker = async () => {
    while (next < items.length) {
      const index = next++;
      results[index] = await fn(items[index]);
    }
  };
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker));
  return results;
}

// ---------------------------------------------------------------- helpers

function parseArgs(args) {
  const baseUrl = args.find((arg) => !arg.startsWith('--')) ?? process.env.BASE_URL;
  if (!baseUrl) {
    console.error('Usage: node burst/burst.mjs <base-url> [--requests N] [--concurrency N] ...');
    process.exit(2);
  }
  const options = { ...DEFAULTS, baseUrl: baseUrl.replace(/\/+$/, '') };
  for (let i = 0; i < args.length; i++) {
    if (!args[i].startsWith('--')) continue;
    const key = args[i].slice(2).replace(/-(\w)/g, (_, c) => c.toUpperCase());
    if (!(key in DEFAULTS)) throw new Error(`unknown option ${args[i]}`);
    options[key] = Number(args[++i]);
  }
  return options;
}

function loadAdminSecret() {
  if (process.env.ADMIN_SECRET) return process.env.ADMIN_SECRET;
  try {
    const envFile = readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', '.env'), 'utf8');
    const line = envFile.split(/\r?\n/).find((l) => l.startsWith('ADMIN_SECRET='));
    if (line) return line.slice('ADMIN_SECRET='.length).trim();
  } catch {
    // fall through to the error below
  }
  throw new Error('ADMIN_SECRET is not set (environment variable or .env)');
}

function labels(prefix, count) {
  return Array.from({ length: count }, (_, i) => `${prefix}${i + 1}`);
}

function auth(token) {
  return { Authorization: `Bearer ${token}` };
}

function parseJson(text) {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

function groupBy(items, keyFn) {
  const groups = new Map();
  for (const item of items) {
    const key = keyFn(item);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return groups;
}

function percentile(sorted, p) {
  return sorted.length === 0 ? 0 : Math.round(sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))]);
}

function pick(items) {
  return items[Math.floor(Math.random() * items.length)];
}

function shuffle(items) {
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [items[i], items[j]] = [items[j], items[i]];
  }
  return items;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
