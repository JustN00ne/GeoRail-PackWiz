import "./style.css";

const $ = (id: string): HTMLElement | null => document.getElementById(id);

function fmtBytes(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MiB`;
  if (bytes >= 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
  return `${bytes} B`;
}

function fmtDate(epochSec: number): string {
  if (!epochSec) return "unknown";
  return new Date(epochSec * 1000).toLocaleString();
}

async function loadStatus(): Promise<void> {
  try {
    const res = await fetch("/metadata.json", { cache: "no-store" });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const meta = await res.json();

    const files = meta.files ?? {};
    const entries = Object.entries(files) as [string, { size?: number; mtime?: number }][];
    const totalSize = entries.reduce((sum, [, f]) => sum + (f.size ?? 0), 0);
    const latestMtime = entries.reduce((max, [, f]) => Math.max(max, f.mtime ?? 0), 0);

    $("stat-files")!.textContent = String(entries.length);
    $("stat-size")!.textContent = fmtBytes(totalSize);
    $("stat-updated")!.textContent = fmtDate(latestMtime);
    $("stat-protocol")!.textContent = `v${meta.version ?? 1}${meta.encrypt ? " · encrypted" : ""}`;
  } catch (err) {
    $("stat-files")!.textContent = "unavailable";
    $("stat-size")!.textContent = "—";
    $("stat-updated")!.textContent = "—";
    $("stat-protocol")!.textContent = "—";
    console.error("Failed to load pack metadata:", err);
  }
}

void loadStatus();
