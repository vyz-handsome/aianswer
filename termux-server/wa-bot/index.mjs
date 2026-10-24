// Jembatan WhatsApp: terima perintah dari chat ke diri sendiri, teruskan ke server.py, kirim balasan.
import fs from "node:fs";
import baileys from "@itsliaaa/baileys";
import pino from "pino";

const B = baileys.default ?? baileys;
const makeWASocket = typeof B === "function" ? B : baileys.makeWASocket;
const useMultiFileAuthState = baileys.useMultiFileAuthState ?? B.useMultiFileAuthState;
const DisconnectReason = baileys.DisconnectReason ?? B.DisconnectReason;
const fetchLatestBaileysVersion = baileys.fetchLatestBaileysVersion ?? B.fetchLatestBaileysVersion;

const NUMBER = (process.env.WA_NUMBER || "").replace(/\D/g, "");
const API = process.env.WA_API;
const SECRET = process.env.WA_SECRET;
const AUTH = process.env.WA_AUTH || "./auth";
const log = pino({ level: "warn" });
const sent = new Set(); // id pesan buatan bot sendiri -> anti-loop

if (!NUMBER || !API || !SECRET) {
  console.error("WA_NUMBER / WA_API / WA_SECRET belum diisi");
  process.exit(1);
}

const bare = (jid) => (jid || "").split("@")[0].split(":")[0];

async function callServer(path, body) {
  const r = await fetch(API + path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Internal": SECRET },
    body: JSON.stringify(body),
  });
  if (!r.ok) throw new Error("server " + r.status);
  return r.json();
}

function asText(item) {
  if (item.type === "text") return item.text;
  if (item.type === "table") {
    const rows = [item.header, ...item.rows];
    const w = item.header.map((_, i) => Math.min(24, Math.max(...rows.map((r) => String(r[i] ?? "").length))));
    const line = (r) => r.map((c, i) => String(c ?? "").slice(0, w[i]).padEnd(w[i])).join(" | ");
    const out = [];
    if (item.title) out.push("*" + item.title + "*");
    if (item.headerText) out.push(item.headerText);
    out.push("```\n" + line(item.header) + "\n" + w.map((n) => "-".repeat(n)).join("-+-") + "\n" +
      item.rows.map(line).join("\n") + "\n```");
    if (item.footer) out.push("_" + item.footer + "_");
    return out.join("\n");
  }
  return "";
}

async function send(sock, jid, content) {
  const m = await sock.sendMessage(jid, content);
  if (m?.key?.id) sent.add(m.key.id);
  return m;
}

async function sendItem(sock, jid, item) {
  if (item.type === "document") {
    await send(sock, jid, {
      document: fs.readFileSync(item.path),
      fileName: item.filename,
      mimetype: "application/vnd.android.package-archive",
      caption: item.caption,
    });
    return;
  }
  if (item.type === "table") {
    try {
      // Pesan kaya (tabel asli) bila didukung library; kalau gagal, jatuh ke tabel teks.
      await send(sock, jid, {
        table: { title: item.title, headerText: item.headerText, footer: item.footer,
                 headers: item.header, rows: item.rows },
      });
      return;
    } catch (e) {
      console.error("tabel kaya gagal, pakai teks:", e?.message || e);
    }
  }
  await send(sock, jid, { text: asText(item) });
}

async function start() {
  const { state, saveCreds } = await useMultiFileAuthState(AUTH);
  let version;
  try { version = (await fetchLatestBaileysVersion()).version; } catch {}
  const sock = makeWASocket({ auth: state, version, logger: log, printQRInTerminal: false,
                              browser: ["Assistant", "Chrome", "1.0"], syncFullHistory: false });
  sock.ev.on("creds.update", saveCreds);

  if (!sock.authState.creds.registered) {
    setTimeout(async () => {
      try {
        const code = await sock.requestPairingCode(NUMBER);
        console.log("\n=== KODE PAIRING WHATSAPP: " + code + " ===");
        console.log("WhatsApp > Perangkat tertaut > Tautkan perangkat > Tautkan dengan nomor telepon.\n");
      } catch (e) { console.error("gagal minta kode pairing:", e?.message || e); }
    }, 3000);
  }

  sock.ev.on("connection.update", ({ connection, lastDisconnect }) => {
    if (connection === "open") {
      console.log("WhatsApp tersambung.");
      callServer("/internal/wa/notify", { text: "Bot WhatsApp tersambung." }).catch(() => {});
    }
    if (connection === "close") {
      const code = lastDisconnect?.error?.output?.statusCode;
      if (code === DisconnectReason.loggedOut) {
        console.error("Logout dari WhatsApp. Hapus folder data/wa-auth lalu jalankan lagi untuk pairing ulang.");
        process.exit(2);
      }
      setTimeout(start, 3000);
    }
  });

  sock.ev.on("messages.upsert", async ({ messages, type }) => {
    if (type !== "notify") return;
    for (const msg of messages) {
      try {
        const key = msg.key;
        if (!key?.id || sent.has(key.id) || !key.fromMe) continue; // hanya pesan dari akun sendiri
        const jid = key.remoteJid || "";
        // chat ke diri sendiri: JID nomor sendiri, atau LID milik sendiri
        const myLid = bare(sock.user?.lid);
        const selfChat = bare(jid) === NUMBER || (myLid && bare(jid) === myLid);
        if (!selfChat) continue;
        const m = msg.message || {};
        const text = m.conversation || m.extendedTextMessage?.text || "";
        if (!text.trim().startsWith("/")) continue;
        const res = await callServer("/internal/wa/command", { text });
        for (const item of res.items || []) await sendItem(sock, jid, item);
      } catch (e) {
        console.error("gagal memproses pesan:", e?.message || e);
      }
    }
  });
}

start().catch((e) => { console.error(e); process.exit(1); });
