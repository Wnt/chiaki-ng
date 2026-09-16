#!/usr/bin/env node
// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
// Proves the mock's passkey branch against its live origin (PLE-284): headless Chrome with a CDP
// virtual authenticator creates a passkey on the authorize page, signs in with it, and must end on
// the redirect URL with a code. No phone, no Google account.
//
//   node android/psn-mock/passkey_check.mjs [host]      (default pleikkari-psn.lab.madekivi.fi)
import { spawn } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const host = process.argv[2] ?? "pleikkari-psn.lab.madekivi.fi";
const account = `empty-consoles+passkey${Date.now()}@mock`;
const redirect = `https://${host}/remoteplay/redirect`;
const authorize = `https://${host}/2.0/oauth/authorize?` + new URLSearchParams({
	response_type: "code", client_id: "ba495a24-818c-472b-b12d-ff231c1b5745", redirect_uri: redirect, scope: "psn:clientapp",
});
const port = 9300 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), "psn-mock-passkey-"));
const chrome = spawn(process.env.CHROME ?? "google-chrome", [
	"--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, "--no-first-run", "about:blank",
], { stdio: "ignore" });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const STOP = Symbol("stop");
let failed = false;
const fail = (message) => {
	if (!failed) { failed = true; console.log(`passkey: FAIL: ${message}`); cleanup(1); }
	throw STOP;
};
function cleanup(code) {
	chrome.once("exit", () => { try { rmSync(profile, { recursive: true, force: true }); } catch {} process.exit(code); });
	chrome.kill();
}
setTimeout(() => { try { fail("timed out after 60 s"); } catch {} }, 60_000);

let target;
for (let i = 0; i < 50 && !target; i++) {
	try { target = (await (await fetch(`http://127.0.0.1:${port}/json`)).json()).find((t) => t.type === "page"); } catch { await sleep(200); }
}
if (!target) { try { fail("Chrome did not start"); } catch {} await new Promise(() => {}); }
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r));
let nextId = 0;
const pending = new Map();
const navigations = [];
ws.addEventListener("message", ({ data }) => {
	const msg = JSON.parse(data);
	if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
	if (msg.method === "Page.frameRequestedNavigation" || msg.method === "Page.frameNavigated")
		navigations.push(msg.params.url ?? msg.params.frame?.url);
});
const send = (method, params = {}) => new Promise((resolve, reject) => {
	const id = ++nextId;
	pending.set(id, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)));
	ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expression) => (await send("Runtime.evaluate", { expression, awaitPromise: true, returnByValue: true })).result.value;
const waitFor = async (expression, what) => {
	for (let i = 0; i < 100; i++) { if (await evaluate(expression)) return; await sleep(100); }
	fail(`waited for ${what}`);
};

try {
	await send("Page.enable");
	await send("WebAuthn.enable");
	await send("WebAuthn.addVirtualAuthenticator", { options: {
		protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true, automaticPresenceSimulation: true,
	} });
	await send("Page.navigate", { url: authorize });
	await waitFor("!!document.getElementById('passkey-create')", "the authorize page");
	await evaluate(`document.getElementById('account').value = ${JSON.stringify(account)}; createPasskey()`);
	await waitFor("document.getElementById('status').textContent.length > 0", "passkey creation");
	const created = await evaluate("document.getElementById('status').textContent");
	if (!created.startsWith("Passkey created")) fail(`create: ${created}`);
	await evaluate("signInWithPasskey()");
	for (let i = 0; i < 100 && !navigations.some((u) => u?.startsWith(redirect)); i++) await sleep(100);
	const landed = navigations.find((u) => u?.startsWith(redirect));
	if (!landed) fail(`sign-in did not redirect; status: ${await evaluate("document.getElementById('status')?.textContent")}`);
	const params = new URL(landed).searchParams;
	if (!params.get("code")) fail("redirect carried no code");
	console.log(`passkey: PASS: created a passkey for ${account} on https://${host} and signed in with it; the redirect carried a code`);
	cleanup(0);
} catch (error) {
	if (error !== STOP) try { fail(error.message); } catch {}
}
