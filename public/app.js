let all = [];
let filter = "all";

const $ = (id) => document.getElementById(id);
const money = (n) => "$" + Number(n).toFixed(2);
const today = () => new Date().toISOString().slice(0, 10);

async function load() {
  const params = new URLSearchParams();
  if ($("q").value.trim()) params.set("q", $("q").value.trim());
  if ($("sort").value) params.set("sort", $("sort").value);
  const res = await fetch("/api/invoices?" + params.toString());
  all = await res.json();
  // client-side paid filter (server also supports ?paid=)
  render();
  loadStats();
}

async function loadStats() {
  try {
    const s = await (await fetch("/api/stats")).json();
    $("tBilled").textContent = money(s.billed);
    $("tPaid").textContent = money(s.paid);
    $("tPending").textContent = money(s.pending);
    $("tOverdue").textContent = s.overdue;
  } catch { /* offline */ }
}

function render() {
  const rows = all.filter((i) => {
    if (filter === "paid") return i.paid;
    if (filter === "pending") return !i.paid;
    return true;
  });

  $("rows").innerHTML = rows.length ? rows.map((i) => {
    const overdue = !i.paid && i.due < today();
    return `
    <tr class="${i.paid ? "ispaid" : ""} ${overdue ? "isoverdue" : ""}">
      <td><b>${escapeHtml(i.client)}</b>${i.notes ? `<div class="note">${escapeHtml(i.notes)}</div>` : ""}</td>
      <td>${money(i.amount)}</td>
      <td>${escapeHtml(i.due)}${overdue ? ` <span class="pill bad">overdue</span>` : ""}</td>
      <td><span class="pill ${i.paid ? "yes" : "no"}">${i.paid ? "paid" : "pending"}</span></td>
      <td class="actions">
        <button onclick="togglePaid('${i.id}')">${i.paid ? "Undo" : "Mark paid"}</button>
        <button class="danger" onclick="removeInv('${i.id}')">Delete</button>
      </td>
    </tr>`;
  }).join("")
  : `<tr><td colspan="5">No invoices found. Add one above — future you says thanks.</td></tr>`;
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
  }[c]));
}

async function togglePaid(id) {
  await fetch("/api/invoices/toggle?id=" + encodeURIComponent(id), { method: "POST" });
  await load();
}

async function removeInv(id) {
  if (!confirm("Delete this invoice?")) return;
  await fetch("/api/invoices?id=" + encodeURIComponent(id), { method: "DELETE" });
  await load();
}

document.getElementById("form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = $("msg");
  msg.textContent = "";
  const payload = {
    client: $("client").value.trim(),
    amount: parseFloat($("amount").value),
    due: $("due").value,
    notes: $("notes").value.trim()
  };
  const res = await fetch("/api/invoices", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload)
  });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    msg.textContent = err.error || "Could not save. Check the fields.";
    return;
  }
  e.target.reset();
  msg.textContent = "Saved ✓";
  await load();
});

document.querySelectorAll(".chip").forEach((b) => {
  b.addEventListener("click", () => {
    document.querySelectorAll(".chip").forEach((x) => x.classList.remove("active"));
    b.classList.add("active");
    filter = b.dataset.f;
    render();
  });
});

let t;
$("q").addEventListener("input", () => { clearTimeout(t); t = setTimeout(load, 250); });
$("sort").addEventListener("change", load);

load();
