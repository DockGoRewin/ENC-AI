// hermes-api-proxy — Enc AI -> Worker ini -> InstaBox exec -> curl (latar belakang) -> gateway Hermes lokal
// Secret: INSTABOX_API_KEY, PROXY_KEY, HERMES_KEY
//
// Dukungan lampiran: Enc AI mengirim gambar (foto, halaman PDF) sebagai bagian `image_url`
// (data URL base64) dan isi file teks/DOCX sebagai teks biasa. Karena exec InstaBox dibatasi
// ~64 KB per argumen, permintaan besar diunggah ke mesin dalam potongan (chunk) lalu disatukan.

const MACHINE_ID = "m_agq6p3h3ajiaxhl46fxyjuw5mm";
const API_BASE = "https://api.instabox.sh";
const GATEWAY_URL = "http://127.0.0.1:8642";
const MODEL_ID = "hermes-agent";

const WORK_DIR = "/tmp/hermes-proxy";
const MAX_ARGV_BYTES = 56000; // batas argv InstaBox 64 KB -> ukuran satu potongan
const MAX_PAYLOAD_BYTES = 900000; // batas total permintaan ke gateway (~900 KB)
const MAX_IMAGE_BYTES = 700000; // batas total data gambar (base64) per permintaan
const UPLOAD_CONCURRENCY = 4; // jumlah unggahan potongan paralel
const CURL_MAX_TIME = 590; // detik, batas curl di mesin
const MAX_WAIT_MS = 600000; // batas Worker menunggu jawaban (10 menit)
const POLL_MS = 3000;

// Transfer file Hermes -> app: Hermes menyimpan file ke folder keluaran per-permintaan di mesin,
// Worker membacanya (base64, per potongan) dan menyisipkannya ke respons.
const MAX_OUT_FILES = 5; // jumlah file per jawaban
const MAX_OUT_FILE_BYTES = 1000000; // ukuran maks satu file (~1 MB)
const MAX_OUT_TOTAL_BYTES = 2000000; // total semua file (~2 MB)
const READ_CHUNK_CHARS = 150000; // karakter base64 yang dibaca per exec

const MIME_BY_EXT = {
  txt: "text/plain", md: "text/markdown", csv: "text/csv", json: "application/json",
  html: "text/html", htm: "text/html", xml: "application/xml", py: "text/x-python",
  js: "text/javascript", java: "text/x-java", pdf: "application/pdf",
  png: "image/png", jpg: "image/jpeg", jpeg: "image/jpeg", gif: "image/gif", webp: "image/webp",
  svg: "image/svg+xml", zip: "application/zip",
  docx: "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
  xlsx: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  pptx: "application/vnd.openxmlformats-officedocument.presentationml.presentation"
};

const mimeFor = name => MIME_BY_EXT[(name.split(".").pop() || "").toLowerCase()] || "application/octet-stream";

const pollDelay = elapsedMs => (elapsedMs < 20000 ? POLL_MS : elapsedMs < 90000 ? 7000 : 20000);
const KEEPALIVE_MS = 10000;
const WAKE_POLL_INTERVAL = 2000;
const WAKE_POLL_ATTEMPTS = 45;
const WAKE_CONFIRM_ATTEMPTS = 8;

const STOPPED_STATES = ["stopped", "exited", "created"];
const TRANSITIONAL_STATES = [
  "starting", "resuming", "provisioning", "creating", "restarting", "pending"
];

const jsonError = (status, message) =>
  Response.json({ error: { message, type: "proxy_error" } }, { status });

const sleep = ms => new Promise(r => setTimeout(r, ms));

const instaboxKey = env => String(env.INSTABOX_API_KEY || "").trim();
const proxyKey = env => String(env.PROXY_KEY || "").trim();
const hermesKey = env => String(env.HERMES_KEY || "").trim();

function authorized(request, env) {
  const h = (request.headers.get("Authorization") || "").trim();
  const key = proxyKey(env);
  return key.length > 0 && h === `Bearer ${key}`;
}

function redact(env, value) {
  let text = String(value || "");

  for (const secret of [instaboxKey(env), hermesKey(env)]) {
    if (secret) text = text.split(secret).join("***");
  }

  return text;
}

// ---------- InstaBox ----------

async function instabox(path, env, options = {}) {
  return fetch(`${API_BASE}${path}`, {
    ...options,
    headers: {
      "X-API-Key": instaboxKey(env),
      Accept: "application/json",
      ...(options.headers || {})
    }
  });
}

async function getMachine(env) {
  const res = await instabox(`/v1/machines/${MACHINE_ID}`, env);

  if (!res.ok) {
    throw new Error(
      `Machine status gagal: HTTP ${res.status} ${(await res.text()).slice(0, 300)}`
    );
  }

  return res.json();
}

async function execMachine(env, argv) {
  const res = await instabox(`/v1/machines/${MACHINE_ID}/exec`, env, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ argv })
  });

  const body = await res.text();
  let result;

  try {
    result = JSON.parse(body);
  } catch {
    throw new Error(`Exec mengembalikan JSON tidak valid: ${body.slice(0, 300)}`);
  }

  if (!res.ok) {
    throw new Error(`Exec API HTTP ${res.status}: ${body.slice(0, 300)}`);
  }

  return result;
}

const isReady = m => m?.state === "running" && m?.runtime?.ready !== false;
const describe = m => `status=${m?.state}; ready=${m?.runtime?.ready}`;

async function machineAction(env, action) {
  const res = await instabox(`/v1/machines/${MACHINE_ID}/${action}`, env, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Idempotency-Key": crypto.randomUUID()
    },
    body: "{}"
  });

  return { ok: res.ok, status: res.status, body: await res.text() };
}

async function confirmStateChange(env, startState) {
  let machine = null;

  for (let i = 0; i < WAKE_CONFIRM_ATTEMPTS; i++) {
    await sleep(WAKE_POLL_INTERVAL);
    machine = await getMachine(env);

    if (machine.state !== startState || isReady(machine)) {
      return { moved: true, machine };
    }
  }

  return { moved: false, machine };
}

async function wakeMachine(env) {
  let machine = await getMachine(env);
  if (isReady(machine)) return { ready: true };

  const notes = [];

  if (machine.state !== "running" && !TRANSITIONAL_STATES.includes(machine.state)) {
    const actions = STOPPED_STATES.includes(machine.state)
      ? ["start", "resume"]
      : ["resume", "start"];

    let moved = false;

    for (const action of actions) {
      const startState = machine.state;
      const result = await machineAction(env, action);

      if (!result.ok) {
        notes.push(`${action}: HTTP ${result.status} ${result.body.slice(0, 150)}`);
        if (result.status !== 409) continue;
      }

      const confirmation = await confirmStateChange(env, startState);
      machine = confirmation.machine || machine;

      if (confirmation.moved) {
        moved = true;
        break;
      }

      notes.push(`${action}: state tetap ${startState}`);
    }

    if (!moved) {
      throw new Error(
        `Perintah wake gagal mengubah state (${describe(machine)}). ${notes.join(" | ")}`
      );
    }

    if (isReady(machine)) return { ready: true };
  }

  for (let i = 0; i < WAKE_POLL_ATTEMPTS; i++) {
    await sleep(WAKE_POLL_INTERVAL);
    machine = await getMachine(env);
    if (isReady(machine)) return { ready: true };
  }

  return { ready: false, message: `Mesin belum siap (${describe(machine)}).` };
}

// ---------- Pesan & lampiran ----------

// JSON yang hanya berisi karakter ASCII (non-ASCII jadi \uXXXX), supaya
// panjang karakter = panjang byte dan pemotongan chunk aman.
const asciiJson = obj =>
  JSON.stringify(obj).replace(
    /[\u007f-\uffff]/g,
    c => "\\u" + c.charCodeAt(0).toString(16).padStart(4, "0")
  );

// Ubah content menjadi string, atau array bagian {type:"text"|"image_url"}.
function normalizeContent(content) {
  if (!Array.isArray(content)) return String(content ?? "");

  const parts = [];

  for (const p of content) {
    if (p?.type === "text" && typeof p.text === "string") {
      parts.push({ type: "text", text: p.text });
    } else if (p?.type === "image_url") {
      const url = typeof p.image_url === "string" ? p.image_url : p.image_url?.url;

      if (typeof url === "string" && /^(data:image\/|https?:\/\/)/i.test(url)) {
        parts.push({ type: "image_url", image_url: { url } });
      }
    }
  }

  return parts;
}

function normalizeMessages(messages) {
  return (messages || [])
    .filter(m => m && typeof m.role === "string")
    .map(m => ({ role: m.role, content: normalizeContent(m.content) }))
    .filter(m =>
      Array.isArray(m.content) ? m.content.length > 0 : m.content.trim().length > 0
    );
}

// Batasi total ukuran gambar: pesan terbaru diprioritaskan, gambar yang tidak muat
// diganti catatan teks.
function limitImages(msgs) {
  let budget = MAX_IMAGE_BYTES;

  for (let i = msgs.length - 1; i >= 0; i--) {
    const c = msgs[i].content;
    if (!Array.isArray(c)) continue;

    for (let j = 0; j < c.length; j++) {
      if (c[j].type !== "image_url") continue;

      const size = c[j].image_url.url.length;

      if (size <= budget) {
        budget -= size;
      } else {
        c[j] = { type: "text", text: "[gambar dilewati: total ukuran gambar terlalu besar]" };
      }
    }
  }
}

// Pesan tanpa gambar dikembalikan menjadi string biasa.
function collapseTextOnly(msgs) {
  for (const m of msgs) {
    if (Array.isArray(m.content) && !m.content.some(p => p.type === "image_url")) {
      m.content = m.content.map(p => p.text).join("\n");
    }
  }
}

// Tambahkan pengingat singkat di akhir pesan user terakhir (model sering
// mengabaikan system prompt dari klien, tapi memperhatikan pesan user).
function appendReminder(msgs, reminder) {
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i];
    if (m.role !== "user") continue;

    if (typeof m.content === "string") {
      m.content += reminder;
    } else if (Array.isArray(m.content)) {
      const t = m.content.find(p => p.type === "text");
      if (t) t.text += reminder;
      else m.content.unshift({ type: "text", text: reminder.trim() });
    }

    return;
  }
}

function buildGatewayBody(messages, outDir) {
  const msgs = normalizeMessages(messages);

  if (msgs.length === 0) throw new Error("messages kosong");

  limitImages(msgs);
  collapseTextOnly(msgs);

  // Beri tahu Hermes ke mana file untuk pengguna harus disimpan.
  msgs.unshift({
    role: "system",
    content:
      "Kamu terhubung ke aplikasi ENC AI lewat sistem transfer file otomatis, jadi kamu BISA memberi pengguna file untuk diunduh. " +
      `Caranya: tulis file di folder ${outDir} (jalankan mkdir -p bila belum ada), maksimal ${MAX_OUT_FILES} file ` +
      `dan tiap file di bawah ${Math.round(MAX_OUT_FILE_BYTES / 1000)} KB. ` +
      "Setelah file tersimpan di sana, file itu otomatis muncul sebagai tombol unduh di aplikasi pengguna. " +
      "JANGAN bilang kamu tidak bisa mengirim file, JANGAN minta atau membuat link/cloud/email, " +
      "dan JANGAN menyebut bahwa kamu hanya berjalan di server atau tidak punya link. " +
      "Cukup simpan filenya lalu sebutkan nama filenya di jawaban. Jangan menyebut path folder ini."
  });

  appendReminder(
    msgs,
    `\n\n[Catatan sistem: bila pengguna meminta file/dokumen/gambar untuk diunduh, simpan hasilnya di ${outDir}; ` +
    "file di folder itu otomatis terkirim ke pengguna sebagai tombol unduh. Tidak perlu link.]"
  );

  const make = () =>
    asciiJson({ model: MODEL_ID, messages: msgs, stream: false });

  let body = make();

  // Buang pesan non-system paling lama selama masih kebesaran
  while (body.length > MAX_PAYLOAD_BYTES && msgs.length > 1) {
    const i = msgs.findIndex(m => m.role !== "system");
    if (i === -1 || i === msgs.length - 1) break;
    msgs.splice(i, 1);
    body = make();
  }

  if (body.length > MAX_PAYLOAD_BYTES) {
    throw new Error(
      `Pesan/lampiran terlalu besar (maks ~${Math.round(MAX_PAYLOAD_BYTES / 1000)} KB). ` +
      "Kurangi jumlah lampiran atau ukuran file."
    );
  }

  return body;
}

// ---------- Unggah ke mesin ----------

async function runLimited(jobs, limit) {
  let next = 0;

  const workers = Array.from({ length: Math.min(limit, jobs.length) }, async () => {
    while (next < jobs.length) {
      const job = jobs[next++];
      await job();
    }
  });

  await Promise.all(workers);
}

// Permintaan besar: tulis per potongan (paralel) ke file .pNNN, lalu gabung ke .json
// dan cocokkan ukurannya.
async function uploadChunked(env, base, body, outDir = null) {
  const mk = await execMachine(env, ["mkdir", "-p", WORK_DIR, ...(outDir ? [outDir] : [])]);

  if (mk.exitCode !== 0) {
    throw new Error(
      "Gagal membuat folder kerja di mesin: " + redact(env, mk.stderr || "").slice(0, 200)
    );
  }

  const total = Math.ceil(body.length / MAX_ARGV_BYTES);

  const jobs = Array.from({ length: total }, (_, i) => async () => {
    const part = body.slice(i * MAX_ARGV_BYTES, (i + 1) * MAX_ARGV_BYTES);
    const file = `${base}.p${String(i).padStart(3, "0")}`;
    let lastErr;

    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const r = await execMachine(env, [
          "sh", "-c", 'printf %s "$2" > "$1"', "sh", file, part
        ]);

        if (r.exitCode === 0) return;

        lastErr = new Error(redact(env, r.stderr || "").slice(0, 200) || `exit ${r.exitCode}`);
      } catch (e) {
        lastErr = e;
      }
    }

    throw new Error(`Gagal mengunggah potongan ${i + 1}/${total}: ${redact(env, lastErr.message)}`);
  });

  await runLimited(jobs, UPLOAD_CONCURRENCY);

  const join = await execMachine(env, [
    "sh", "-c",
    'cat "$1".p[0-9][0-9][0-9] > "$1.json" && [ "$(wc -c < "$1.json")" -eq "$2" ]',
    "sh", base, String(body.length)
  ]);

  if (join.exitCode !== 0) {
    throw new Error(
      "Penyatuan potongan gagal / ukuran tidak cocok: " +
      redact(env, join.stderr || "").slice(0, 200)
    );
  }
}

// ---------- Gateway Hermes (lokal di mesin) ----------

// Posisi argumen: $1=base path, $2=max time, $3=url, $4=key
const INNER_SCRIPT =
  'curl -sS --max-time "$2" -X POST "$3" ' +
  '-H "Content-Type: application/json" -H "Authorization: Bearer $4" ' +
  '--data-binary @"$1.json" -o "$1.out" 2>"$1.err"; echo $? > "$1.done"';

const START_SCRIPT =
  'S=""; command -v setsid >/dev/null 2>&1 && S=setsid; ' +
  '$S nohup sh -c "$1" sh "$2" "$3" "$4" "$5" >/dev/null 2>&1 </dev/null & ' +
  "echo started";

const POLL_SCRIPT =
  'if [ -f "$1.done" ]; then ' +
  'echo "__CODE__:$(cat "$1.done")"; cat "$1.out"; echo; echo __ERR__; cat "$1.err"; ' +
  "else echo __PENDING__; fi";

function parseGatewayOutput(env, out) {
  const nl = out.indexOf("\n");
  const code = parseInt(out.slice("__CODE__:".length, nl), 10);
  const rest = out.slice(nl + 1);

  const marker = "\n__ERR__\n";
  const errIdx = rest.lastIndexOf(marker);

  const bodyText = (errIdx >= 0 ? rest.slice(0, errIdx) : rest).trim();
  const errText = errIdx >= 0 ? rest.slice(errIdx + marker.length).trim() : "";

  if (code !== 0) {
    throw new Error(
      `curl exit ${code}${code === 28 ? " (gateway tidak menjawab dalam batas waktu)" : ""}: ` +
      redact(env, errText).slice(0, 300)
    );
  }

  let data;

  try {
    data = JSON.parse(bodyText);
  } catch {
    throw new Error("Respons gateway bukan JSON: " + redact(env, bodyText.slice(0, 300)));
  }

  if (data.error) {
    throw new Error(
      `Gateway: ${data.error.message || JSON.stringify(data.error)}`.slice(0, 400)
    );
  }

  const text = String(data.choices?.[0]?.message?.content ?? "").trim();

  if (!text) throw new Error("Gateway tidak mengembalikan jawaban");

  return text;
}

// Ambil file yang dibuat Hermes di folder keluaran. Gagal membaca file tidak menggagalkan jawaban.
async function collectFiles(env, base) {
  const files = [];

  try {
    const list = await execMachine(env, [
      "sh", "-c",
      'd="$1-files"; [ -d "$d" ] || exit 0; ' +
      'find "$d" -type f | head -n "$2" | while IFS= read -r f; do ' +
      'printf "%s\\t%s\\n" "$(wc -c < "$f")" "$f"; done',
      "sh", base, String(MAX_OUT_FILES)
    ]);

    let total = 0;

    for (const line of String(list.stdout || "").split("\n")) {
      const tab = line.indexOf("\t");
      if (tab < 1) continue;

      const size = parseInt(line.slice(0, tab), 10);
      const path = line.slice(tab + 1).trim();

      if (!path.startsWith(`${base}-files/`) || !(size > 0)) continue;
      if (size > MAX_OUT_FILE_BYTES || total + size > MAX_OUT_TOTAL_BYTES) continue;

      const b64Len = Math.ceil(size / 3) * 4;
      let data = "";

      for (let start = 1; start <= b64Len; start += READ_CHUNK_CHARS) {
        const end = Math.min(b64Len, start + READ_CHUNK_CHARS - 1);

        const r = await execMachine(env, [
          "sh", "-c",
          'base64 "$1" | tr -d "\\n" | cut -c "$2"-"$3"',
          "sh", path, String(start), String(end)
        ]);

        data += String(r.stdout || "").replace(/\s+/g, "");
      }

      if (data.length < b64Len - 4) continue; // terbaca tidak utuh

      const name = (path.split("/").pop() || "file").replace(/[\\/:*?"<>|\x00-\x1f]/g, "_").slice(0, 120);

      files.push({ name, mime: mimeFor(name), size, data });
      total += size;
    }
  } catch (e) {
    console.warn("Ambil file gagal:", redact(env, e.message));
  }

  return files;
}

async function askGateway(env, messages) {
  const base = `${WORK_DIR}/${crypto.randomUUID()}`;
  const outDir = `${base}-files`;
  const body = buildGatewayBody(messages, outDir);

  try {
    // 1. Tulis permintaan ke file di mesin
    if (body.length <= MAX_ARGV_BYTES) {
      const prep = await execMachine(env, [
        "sh", "-c",
        'mkdir -p "$1" "$4" && printf %s "$2" > "$3.json"',
        "sh", WORK_DIR, body, base, outDir
      ]);

      if (prep.exitCode !== 0) {
        throw new Error(
          "Gagal menulis permintaan ke mesin: " + redact(env, prep.stderr || "").slice(0, 200)
        );
      }
    } else {
      await uploadChunked(env, base, body, outDir);
    }

    // 2. Jalankan curl di latar belakang (exec langsung kembali)
    const start = await execMachine(env, [
      "sh", "-c", START_SCRIPT,
      "sh", INNER_SCRIPT, base, String(CURL_MAX_TIME), `${GATEWAY_URL}/v1/chat/completions`, hermesKey(env)
    ]);

    if (start.exitCode !== 0) {
      throw new Error(
        "Gagal memulai curl di mesin: " + redact(env, start.stderr || "").slice(0, 200)
      );
    }

    // 3. Cek hasil secara berkala lewat exec singkat
    const startedAt = Date.now();
    const deadline = startedAt + MAX_WAIT_MS;
    let lastError = null;

    while (Date.now() < deadline) {
      await sleep(pollDelay(Date.now() - startedAt));

      let poll;

      try {
        poll = await execMachine(env, ["sh", "-c", POLL_SCRIPT, "sh", base]);
      } catch (e) {
        lastError = e;
        continue;
      }

      const out = String(poll.stdout || "");

      if (out.startsWith("__PENDING__")) continue;

      if (out.startsWith("__CODE__:")) {
        const text = parseGatewayOutput(env, out);
        return { text, files: await collectFiles(env, base) };
      }

      lastError = new Error("Output polling tidak dikenali: " + redact(env, out.slice(0, 200)));
    }

    throw new Error(
      `Gateway belum menjawab setelah ${Math.round(MAX_WAIT_MS / 1000)} detik` +
      (lastError ? `; error terakhir: ${redact(env, lastError.message).slice(0, 200)}` : "")
    );
  } finally {
    try {
      await execMachine(env, [
        "sh", "-c",
        'rm -f "$1.json" "$1.out" "$1.err" "$1.done" "$1".p[0-9][0-9][0-9]; rm -rf "$1-files"',
        "sh", base
      ]);
    } catch (e) {
      console.warn("Cleanup file sementara gagal:", redact(env, e.message));
    }
  }
}

// ---------- Debug ----------

async function runDebug(env, url) {
  const out = {
    secrets: {
      instaboxKeyPrefix: instaboxKey(env).slice(0, 4),
      hermesKeyPresent: hermesKey(env).length > 0
    }
  };

  try {
    const wake = await wakeMachine(env);
    out.ready = wake.ready;

    if (!wake.ready) {
      out.message = wake.message;
      return Response.json(out);
    }

    const sleepSec = Number(url.searchParams.get("sleep"));

    if (sleepSec > 0 && sleepSec <= 300) {
      const t = Date.now();
      const r = await execMachine(env, ["sleep", String(sleepSec)]);

      out.execSleep = {
        askedSeconds: sleepSec,
        ms: Date.now() - t,
        timedOut: Boolean(r.timedOut),
        exitCode: r.exitCode
      };
    }

    let t0 = Date.now();
    const health = await execMachine(env, [
      "curl", "-sS", "--max-time", "15", `${GATEWAY_URL}/health`
    ]);

    out.gatewayHealth = {
      ms: Date.now() - t0,
      exitCode: health.exitCode,
      stdout: redact(env, String(health.stdout || "").slice(0, 300)),
      stderr: redact(env, String(health.stderr || "").slice(0, 300))
    };

    t0 = Date.now();

    try {
      const { text: answer } = await askGateway(env, [
        { role: "user", content: "Balas tepat OK. Jangan gunakan tools." }
      ]);

      out.chat = { ms: Date.now() - t0, ok: true, answer: answer.slice(0, 200) };
    } catch (e) {
      out.chat = {
        ms: Date.now() - t0,
        ok: false,
        error: redact(env, e.message).slice(0, 500)
      };
    }

    // Uji transfer file end-to-end: /debug?file=1
    if (url.searchParams.get("file")) {
      t0 = Date.now();

      try {
        const { text: answer, files } = await askGateway(env, [
          {
            role: "user",
            content: "Buat file bernama test.txt berisi tulisan halo, lalu beri tahu aku."
          }
        ]);

        out.fileTest = {
          ms: Date.now() - t0,
          answer: answer.slice(0, 300),
          files: files.map(f => ({ name: f.name, mime: f.mime, size: f.size }))
        };
      } catch (e) {
        out.fileTest = { ok: false, error: redact(env, e.message).slice(0, 400) };
      }
    }

    if (url.searchParams.get("upload")) {
      t0 = Date.now();

      try {
        const base = `${WORK_DIR}/debug-${crypto.randomUUID()}`;
        const fake = asciiJson({ pad: "x".repeat(MAX_ARGV_BYTES * 2 + 1234) });

        try {
          await uploadChunked(env, base, fake);
          out.upload = { ok: true, bytes: fake.length, ms: Date.now() - t0 };
        } finally {
          await execMachine(env, [
            "sh", "-c", 'rm -f "$1.json" "$1".p[0-9][0-9][0-9]', "sh", base
          ]).catch(() => {});
        }
      } catch (e) {
        out.upload = { ok: false, error: redact(env, e.message).slice(0, 300) };
      }
    }
  } catch (e) {
    out.error = redact(env, e.message);
  }

  return Response.json(out);
}

// ---------- Handler ----------

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const { pathname } = url;

    if (pathname === "/health") {
      const key = instaboxKey(env);

      return Response.json({
        status: "ok",
        instaboxKey: {
          present: key.length > 0,
          length: key.length,
          prefix: key.slice(0, 4)
        },
        hermesKeyPresent: hermesKey(env).length > 0,
        proxyKeyPresent: proxyKey(env).length > 0
      });
    }

    if (!authorized(request, env)) return jsonError(401, "API key tidak valid");

    if (pathname === "/debug" && request.method === "POST") {
      return runDebug(env, url);
    }

    if (pathname === "/v1/models" && request.method === "GET") {
      return Response.json({
        object: "list",
        data: [{ id: MODEL_ID, object: "model", owned_by: "hermes" }]
      });
    }

    if (pathname !== "/v1/chat/completions" || request.method !== "POST") {
      return jsonError(404, "Route tidak ditemukan");
    }

    let body;

    try {
      body = await request.json();
    } catch {
      return jsonError(400, "JSON tidak valid");
    }

    if (!Array.isArray(body.messages) || body.messages.length === 0) {
      return jsonError(400, "messages kosong");
    }

    const model = body.model || MODEL_ID;
    const id = `chatcmpl-${crypto.randomUUID()}`;
    const created = Math.floor(Date.now() / 1000);

    const ask = async () => {
      const wake = await wakeMachine(env);
      if (!wake.ready) throw new Error(wake.message);
      return askGateway(env, body.messages);
    };

    // Mode non-stream
    if (!body.stream) {
      try {
        const { text: answer, files } = await ask();

        return Response.json({
          id,
          object: "chat.completion",
          created,
          model,
          choices: [{
            index: 0,
            message: { role: "assistant", content: answer, ...(files.length ? { files } : {}) },
            finish_reason: "stop"
          }],
          usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 }
        });
      } catch (e) {
        return jsonError(502, redact(env, e.message).slice(0, 700));
      }
    }

    // Mode stream (SSE) dengan keep-alive
    const { readable, writable } = new TransformStream();
    const writer = writable.getWriter();
    const enc = new TextEncoder();
    const send = s => writer.write(enc.encode(s));

    const chunk = (delta, finish = null) =>
      `data: ${JSON.stringify({
        id,
        object: "chat.completion.chunk",
        created,
        model,
        choices: [{ index: 0, delta, finish_reason: finish }]
      })}\n\n`;

    ctx.waitUntil((async () => {
      const keepAlive = setInterval(
        () => { send(": keep-alive\n\n").catch(() => {}); },
        KEEPALIVE_MS
      );

      try {
        await send(chunk({ role: "assistant", content: "" }));
        const { text: answer, files } = await ask();

        for (let i = 0; i < answer.length; i += 200) {
          await send(chunk({ content: answer.slice(i, i + 200) }));
        }

        // File dikirim satu per satu agar tiap event SSE tidak terlalu besar
        for (const f of files) await send(chunk({ files: [f] }));

        await send(chunk({}, "stop"));
        await send("data: [DONE]\n\n");
      } catch (e) {
        await send(
          chunk({ content: `[Error: ${redact(env, e.message).slice(0, 500)}]` })
        ).catch(() => {});
        await send(chunk({}, "stop")).catch(() => {});
        await send("data: [DONE]\n\n").catch(() => {});
      } finally {
        clearInterval(keepAlive);
        await writer.close().catch(() => {});
      }
    })());

    return new Response(readable, {
      headers: {
        "Content-Type": "text/event-stream",
        "Cache-Control": "no-cache"
      }
    });
  }
};
