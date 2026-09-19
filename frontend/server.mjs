import express from "express";
import archiver from "archiver";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import https from "node:https";
import path from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = Number(process.env.PORT || 8080);
const PACK_DIR = path.resolve(__dirname, process.env.PACK_DIR || "pack");
const DIST_DIR = path.join(__dirname, "dist");
const CLIENT_VERSION = process.env.CLIENT_VERSION || "2.1.0";
const SOURCE_NAME = process.env.SOURCE_NAME || "GeoRail";
const BASE_URL = process.env.BASE_URL || ""; // e.g. https://packwiz.justnoone.eu

const SSL_CERT = process.env.SSL_CERT || "";
const SSL_KEY = process.env.SSL_KEY || "";

if (!fs.existsSync(DIST_DIR)) {
  console.error(`[geopackwiz-site] Built frontend not found at ${DIST_DIR}. Run "npm run build" first.`);
  process.exit(1);
}

/* ------------------------------------------------------------------ */
/* Pack metadata generation (SHA-1 + size + mtime per file, v1 protocol) */
/* ------------------------------------------------------------------ */

function walkDir(dir) {
  const dirs = new Set();
  const files = [];
  const walk = (current, rel) => {
    for (const entry of fs.readdirSync(current, { withFileTypes: true })) {
      const entryPath = path.join(current, entry.name);
      const entryRel = rel ? `${rel}/${entry.name}` : entry.name;
      if (entry.isDirectory()) {
        dirs.add(entryRel);
        walk(entryPath, entryRel);
      } else {
        files.push({ rel: entryRel, abs: entryPath });
      }
    }
  };
  walk(dir, "");
  return { dirs: [...dirs], files };
}

let cachedSignature = null;
let cachedMetadata = null;

function treeSignature(entries) {
  return entries.files
    .map((f) => {
      const st = fs.statSync(f.abs);
      return `${f.rel}:${st.size}:${Math.floor(st.mtimeMs)}`;
    })
    .sort()
    .join("|");
}

function buildMetadata() {
  if (!fs.existsSync(PACK_DIR)) {
    fs.mkdirSync(PACK_DIR, { recursive: true });
  }

  const entries = walkDir(PACK_DIR);
  const signature = treeSignature(entries);
  if (signature === cachedSignature && cachedMetadata) {
    return cachedMetadata;
  }

  const dirs = {};
  for (const dir of ["", ...entries.dirs].sort()) {
    dirs[dir] = {};
  }

  const files = {};
  for (const file of entries.files) {
    const buf = fs.readFileSync(file.abs);
    const st = fs.statSync(file.abs);
    files[file.rel] = {
      sha1: crypto.createHash("sha1").update(buf).digest("hex"),
      size: st.size,
      mtime: Math.floor(st.mtimeMs / 1000),
    };
  }

  cachedSignature = signature;
  cachedMetadata = {
    version: 1,
    client_version: CLIENT_VERSION,
    encrypt: false,
    dirs,
    files,
  };
  console.log(`[geopackwiz-site] metadata.json regenerated: ${entries.files.length} files`);
  return cachedMetadata;
}

/* ------------------------------------------------------------------ */
/* Path safety                                                         */
/* ------------------------------------------------------------------ */

function safeResolve(baseDir, rel) {
  const target = path.resolve(baseDir, rel);
  const base = path.resolve(baseDir);
  if (target !== base && !target.startsWith(base + path.sep)) return null;
  return target;
}

/* ------------------------------------------------------------------ */
/* App                                                                 */
/* ------------------------------------------------------------------ */

const app = express();
app.set("trust proxy", true);
app.disable("x-powered-by");

app.use((req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("Referrer-Policy", "no-referrer");
  if (SSL_CERT && SSL_KEY) {
    res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
  }
  next();
});

// Built frontend
app.use(express.static(DIST_DIR));

// Favicon / pack icon
app.get("/icon.png", (req, res) => {
  const packIcon = path.join(PACK_DIR, "pack.png");
  if (fs.existsSync(packIcon)) {
    return res.sendFile(packIcon);
  }
  res.sendFile(path.join(__dirname, "..", "logo.png"));
});

// Pack manifest consumed by the GeoRail PackWiz mod
app.get("/metadata.json", (req, res) => {
  res.setHeader("Cache-Control", "no-store");
  res.json(buildMetadata());
});

// Mod's remote config: pushes the source list to clients
app.get("/client_config.json", (req, res) => {
  const baseUrl = BASE_URL || `${req.protocol}://${req.get("host")}`;
  res.setHeader("Cache-Control", "no-store");
  res.json({
    sources: [
      { name: SOURCE_NAME, baseUrl, hasDirHash: false, hasArchive: false },
    ],
  });
});

// Pack files, one per URL path
app.get("/dist/*", (req, res) => {
  const rel = req.params[0] || "";
  const filePath = safeResolve(PACK_DIR, rel);
  if (!filePath || !fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
    return res.status(404).json({ error: "Not found" });
  }
  res.setHeader("Cache-Control", "public, max-age=86400");
  res.sendFile(filePath);
});

// Manual / browser download of the whole pack
app.get("/pack/latest.zip", (req, res) => {
  if (!fs.existsSync(PACK_DIR)) {
    return res.status(404).json({ error: "No pack configured yet" });
  }
  res.setHeader("Content-Type", "application/zip");
  res.setHeader("Content-Disposition", 'attachment; filename="latest.zip"');
  const archive = archiver("zip", { zlib: { level: 9 } });
  archive.on("error", (err) => {
    console.error("[geopackwiz-site] zip error:", err);
    res.status(500).end();
  });
  archive.pipe(res);
  archive.directory(PACK_DIR, false);
  archive.finalize();
});

app.use((req, res) => res.status(404).json({ error: "Not found" }));

/* ------------------------------------------------------------------ */
/* Start                                                               */
/* ------------------------------------------------------------------ */

const server =
  SSL_CERT && SSL_KEY
    ? https.createServer({ cert: fs.readFileSync(SSL_CERT), key: fs.readFileSync(SSL_KEY) }, app)
    : http.createServer(app);

server.listen(PORT, () => {
  const scheme = SSL_CERT && SSL_KEY ? "https" : "http";
  console.log(`[geopackwiz-site] ${scheme}://localhost:${PORT} (pack dir: ${PACK_DIR})`);
  console.log(`[geopackwiz-site] baseUrl served to the mod: ${BASE_URL || `http://localhost:${PORT}`}`);
});
