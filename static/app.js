// dantez x mamank recing — Frontend Logic
let selectedFile = null;
let currentUploadId = null;
let currentJobId = null;
let activeKeyTab = "verboo";
let keysCache = { verboo: [], groq: [], gemini: [] };
let eventSource = null;
let currentSourceTab = "screenrec";
let currentScreenrecFolder = "";
let isDirectMode = false;
let savedWatermark = "";
let isEditingWatermark = false;

document.addEventListener("DOMContentLoaded", () => {
  if (window.lucide) {
    lucide.createIcons();
  }
  setupDropZone();
  refreshSystemStatus();
  loadKeys();
  initScreenrecorder();
  loadSavedWatermark();
});

// --- SCREEN RECORDER ZERO-COPY INTEGRATION ---
async function initScreenrecorder() {
  try {
    const res = await fetch("/api/screenrecorder/config");
    const data = await res.json();
    currentScreenrecFolder = data.current_folder || "";
    const folderEl = document.getElementById("screenrec-current-folder");
    if (folderEl) {
      folderEl.textContent = currentScreenrecFolder || "(Belum ada folder)";
      folderEl.title = currentScreenrecFolder;
    }
    loadScreenrecorderFiles();
  } catch (err) {
    console.error("Gagal inisialisasi screenrecorder:", err);
  }
}

function switchSourceTab(tab) {
  currentSourceTab = tab;
  const btnRec = document.getElementById("tab-src-screenrec");
  const btnUp = document.getElementById("tab-src-upload");
  const secRec = document.getElementById("src-screenrec-section");
  const secUp = document.getElementById("src-upload-section");

  if (tab === "screenrec") {
    btnRec.className = "py-1.5 px-3 rounded-md bg-zinc-800 text-white font-medium flex items-center justify-center gap-1.5 transition-colors";
    btnUp.className = "py-1.5 px-3 rounded-md text-zinc-400 hover:text-white flex items-center justify-center gap-1.5 transition-colors";
    secRec.classList.remove("hidden");
    secUp.classList.add("hidden");
  } else {
    btnUp.className = "py-1.5 px-3 rounded-md bg-zinc-800 text-white font-medium flex items-center justify-center gap-1.5 transition-colors";
    btnRec.className = "py-1.5 px-3 rounded-md text-zinc-400 hover:text-white flex items-center justify-center gap-1.5 transition-colors";
    secUp.classList.remove("hidden");
    secRec.classList.add("hidden");
  }
  if (window.lucide) lucide.createIcons();
}

async function loadScreenrecorderFiles() {
  const container = document.getElementById("screenrec-files-list");
  const countBadge = document.getElementById("screenrec-file-count");
  if (!container) return;

  container.innerHTML = `<div class="p-3 text-center text-zinc-500 text-xs font-mono">Memindai file rekaman...</div>`;

  try {
    const res = await fetch("/api/screenrecorder/files");
    const data = await res.json();
    const files = data.files || [];

    if (countBadge) countBadge.textContent = files.length;

    if (files.length === 0) {
      container.innerHTML = `
        <div class="p-4 text-center text-zinc-500 text-xs space-y-1">
          <p>Tidak ada file video (.mp4/.mkv) di folder ini.</p>
          <button onclick="openFolderConfigModal()" class="text-zinc-300 underline text-[11px]">Ubah Folder Rekaman Layar</button>
        </div>
      `;
      return;
    }

    container.innerHTML = files.map((f, i) => {
      const isTop = (i === 0);
      const safePath = f.path.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
      return `
        <div onclick="selectScreenrecorderFile('${safePath}', '${f.name}', ${f.size_mb})" 
             class="p-2 rounded bg-zinc-900/70 hover:bg-zinc-800 border border-zinc-800/80 hover:border-zinc-700 cursor-pointer flex items-center justify-between gap-2 text-xs transition-colors group">
          <div class="flex items-center gap-2 overflow-hidden">
            <svg class="w-3.5 h-3.5 text-zinc-400 group-hover:text-amber-400 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            <div class="truncate text-left font-mono">
              <div class="flex items-center gap-1.5">
                <span class="text-zinc-200 truncate font-medium text-[11px]">${f.name}</span>
                ${isTop ? '<span class="text-[9px] bg-emerald-950 text-emerald-400 border border-emerald-800 px-1 rounded font-semibold">Terbaru</span>' : ''}
              </div>
              <span class="text-[10px] text-zinc-500">${f.size_mb} MB • ${f.ago}</span>
            </div>
          </div>
          <span class="text-[10px] text-zinc-400 group-hover:text-white shrink-0 font-medium px-2 py-0.5 rounded bg-zinc-800/60 border border-zinc-700/50">
            Pilih ↗
          </span>
        </div>
      `;
    }).join("");

  } catch (err) {
    container.innerHTML = `<div class="p-3 text-center text-red-400 text-xs font-mono">Gagal memuat file: ${err.message}</div>`;
  }
}

async function selectScreenrecorderFile(fullPath, name, sizeMb) {
  try {
    const res = await fetch("/api/screenrecorder/select", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: fullPath })
    });

    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.detail || "Gagal memilih file rekaman.");
    }

    const data = await res.json();
    currentUploadId = data.upload_id;
    isDirectMode = true;
    selectedFile = null;

    // Update UI
    document.getElementById("file-name").textContent = data.filename;
    document.getElementById("file-size").textContent = `${data.size_mb} MB (Langsung dari perangkat - Zero Copy)`;
    document.getElementById("file-direct-badge").classList.remove("hidden");
    document.getElementById("file-info").classList.remove("hidden");
    document.getElementById("btn-process").disabled = false;

    // Setup Live Preview Player langsung streaming dari file asli
    const previewVideo = document.getElementById("preview-crop-video");
    const cropBox = document.getElementById("crop-preview-box");
    if (previewVideo && cropBox) {
      cropBox.classList.remove("hidden");
      previewVideo.src = `/api/source-video/${currentUploadId}`;
      previewVideo.play().catch(() => {});
      setupCropSliders();
    }

    addLogMessage(`⚡ Mode Langsung (Zero-Copy): Video terpilih langsung dari '${data.filename}' tanpa dicopy.`);
    if (window.lucide) lucide.createIcons();
  } catch (err) {
    alert("Gagal memilih file: " + err.message);
  }
}

async function submitDirectPathInput() {
  const input = document.getElementById("direct-path-input");
  const val = (input?.value || "").trim();
  if (!val) {
    alert("Masukkan path file video terlebih dahulu.");
    return;
  }
  const cleanName = val.split(/[/\\]/).pop();
  await selectScreenrecorderFile(val, cleanName, 0);
}

async function openFolderConfigModal() {
  const modal = document.getElementById("folder-modal");
  const input = document.getElementById("modal-folder-input");
  const candidatesBox = document.getElementById("modal-folder-candidates");
  if (!modal) return;

  modal.classList.remove("hidden");
  if (input) input.value = currentScreenrecFolder;

  try {
    const res = await fetch("/api/screenrecorder/config");
    const data = await res.json();
    const list = data.detected_folders || [];
    if (candidatesBox) {
      if (list.length === 0) {
        candidatesBox.innerHTML = `<span class="text-zinc-500">Tidak ada folder standar yang terdeteksi otomatis.</span>`;
      } else {
        candidatesBox.innerHTML = list.map(c => {
          const safeC = c.replace(/\\/g, '\\\\').replace(/'/g, "\\'");
          return `
            <div onclick="selectCandidateFolder('${safeC}')" class="p-1.5 rounded bg-zinc-900 hover:bg-zinc-800 border border-zinc-800 hover:border-zinc-700 cursor-pointer text-zinc-300 truncate flex items-center justify-between">
              <span class="truncate">${c}</span>
              <span class="text-[9px] text-zinc-500 shrink-0 ml-2">Gunakan ↗</span>
            </div>
          `;
        }).join("");
      }
    }
  } catch (err) {
    console.error("Gagal memuat kandidat folder:", err);
  }

  if (window.lucide) lucide.createIcons();
}

function selectCandidateFolder(pathStr) {
  const input = document.getElementById("modal-folder-input");
  if (input) input.value = pathStr;
}

function closeFolderConfigModal() {
  document.getElementById("folder-modal").classList.add("hidden");
}

async function saveFolderConfig() {
  const input = document.getElementById("modal-folder-input");
  const val = (input?.value || "").trim();
  if (!val) {
    alert("Path folder tidak boleh kosong.");
    return;
  }

  try {
    const res = await fetch("/api/screenrecorder/config", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ folder: val })
    });
    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.detail || "Gagal menyimpan folder.");
    }
    const data = await res.json();
    currentScreenrecFolder = data.folder;
    const folderEl = document.getElementById("screenrec-current-folder");
    if (folderEl) {
      folderEl.textContent = currentScreenrecFolder;
      folderEl.title = currentScreenrecFolder;
    }
    closeFolderConfigModal();
    loadScreenrecorderFiles();
  } catch (err) {
    alert("Error: " + err.message);
  }
}

// --- PERSISTENT WATERMARK SYSTEM ---
async function loadSavedWatermark() {
  try {
    const res = await fetch("/api/watermark");
    if (!res.ok) return;
    const data = await res.json();
    savedWatermark = (data.watermark || "").trim();
    renderWatermarkUI();
  } catch (err) {
    console.error("Gagal memuat watermark tersimpan:", err);
  }
}

function renderWatermarkUI() {
  const displayCard = document.getElementById("wm-display-card");
  const editCard = document.getElementById("wm-edit-card");
  const displayText = document.getElementById("wm-display-text");
  const subText = document.getElementById("wm-display-subtext");
  const statusBadge = document.getElementById("wm-status-badge");
  const indicator = document.getElementById("wm-status-indicator");
  const btnEditLabel = document.getElementById("wm-btn-edit-label");
  const btnDelete = document.getElementById("wm-btn-delete");
  const inputEl = document.getElementById("watermark-input");

  if (isEditingWatermark) {
    if (displayCard) displayCard.classList.add("hidden");
    if (editCard) editCard.classList.remove("hidden");
    if (inputEl) {
      inputEl.value = savedWatermark;
      setTimeout(() => inputEl.focus(), 50);
    }
  } else {
    if (editCard) editCard.classList.add("hidden");
    if (displayCard) displayCard.classList.remove("hidden");

    if (savedWatermark) {
      if (displayText) {
        displayText.textContent = savedWatermark;
        displayText.className = "text-xs font-mono font-bold text-emerald-400 truncate";
      }
      if (subText) subText.textContent = "Otomatis dipasang ke setiap video yang diproses";
      if (statusBadge) {
        statusBadge.textContent = "Aktif Otomatis";
        statusBadge.className = "text-[9px] px-1.5 py-0.5 rounded font-semibold shrink-0 bg-emerald-950/90 text-emerald-400 border border-emerald-800/80";
      }
      if (indicator) {
        indicator.className = "w-2 h-2 rounded-full bg-emerald-400 shrink-0 shadow-[0_0_8px_rgba(52,211,153,0.6)]";
      }
      if (btnEditLabel) btnEditLabel.textContent = "Ganti";
      if (btnDelete) btnDelete.classList.remove("hidden");
    } else {
      if (displayText) {
        displayText.textContent = "Tanpa Watermark";
        displayText.className = "text-xs font-mono font-medium text-zinc-400 truncate";
      }
      if (subText) subText.textContent = "Video akan diekspor bersih tanpa watermark";
      if (statusBadge) {
        statusBadge.textContent = "Nonaktif";
        statusBadge.className = "text-[9px] px-1.5 py-0.5 rounded font-medium shrink-0 bg-zinc-800 text-zinc-400";
      }
      if (indicator) {
        indicator.className = "w-2 h-2 rounded-full bg-zinc-600 shrink-0";
      }
      if (btnEditLabel) btnEditLabel.textContent = "+ Pasang Watermark";
      if (btnDelete) btnDelete.classList.add("hidden");
    }
  }

  if (window.lucide) {
    lucide.createIcons();
  }
}

function startWatermarkEdit() {
  isEditingWatermark = true;
  renderWatermarkUI();
}

function cancelWatermarkEdit() {
  isEditingWatermark = false;
  renderWatermarkUI();
}

async function saveNewWatermark() {
  const inputEl = document.getElementById("watermark-input");
  const saveBtn = document.getElementById("wm-btn-save");
  const val = (inputEl?.value || "").trim();

  if (saveBtn) {
    saveBtn.disabled = true;
    saveBtn.innerHTML = `<span class="inline-block animate-spin mr-1">↻</span> Menyimpan...`;
  }

  try {
    const res = await fetch("/api/watermark", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ watermark: val })
    });
    if (!res.ok) throw new Error("Gagal menyimpan watermark ke database.");
    const data = await res.json();
    savedWatermark = data.watermark || "";
    isEditingWatermark = false;
    renderWatermarkUI();
  } catch (err) {
    alert("Error: " + err.message);
  } finally {
    if (saveBtn) {
      saveBtn.disabled = false;
      saveBtn.innerHTML = `<i data-lucide="check" class="w-3 h-3"></i><span>Simpan</span>`;
      if (window.lucide) lucide.createIcons();
    }
  }
}

async function deleteSavedWatermark() {
  if (!savedWatermark) return;
  const ok = confirm(`Hapus watermark "${savedWatermark}"?\n\nVideo berikutnya akan diekspor tanpa watermark.`);
  if (!ok) return;

  try {
    const res = await fetch("/api/watermark", {
      method: "DELETE"
    });
    if (!res.ok) throw new Error("Gagal menghapus watermark.");
    savedWatermark = "";
    isEditingWatermark = false;
    renderWatermarkUI();
  } catch (err) {
    alert("Error: " + err.message);
  }
}

// --- DROP ZONE & FILE SELECTION ---
function setupDropZone() {
  const dropZone = document.getElementById("drop-zone");
  const fileInput = document.getElementById("video-input");

  dropZone.addEventListener("click", () => fileInput.click());

  dropZone.addEventListener("dragover", (e) => {
    e.preventDefault();
    dropZone.classList.add("border-amber-500", "bg-slate-900/90");
  });

  dropZone.addEventListener("dragleave", () => {
    dropZone.classList.remove("border-amber-500", "bg-slate-900/90");
  });

  dropZone.addEventListener("drop", (e) => {
    e.preventDefault();
    dropZone.classList.remove("border-amber-500", "bg-slate-900/90");
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      handleFile(e.dataTransfer.files[0]);
    }
  });

  fileInput.addEventListener("change", (e) => {
    if (e.target.files && e.target.files[0]) {
      handleFile(e.target.files[0]);
    }
  });
}

async function handleFile(file) {
  // Cek apakah file ini cocok dengan file yang ada di folder screenrecorder lokal (Smart Zero-Copy match)
  try {
    const checkRes = await fetch(`/api/find-local-file?filename=${encodeURIComponent(file.name)}&size=${file.size}`);
    const checkData = await checkRes.json();
    if (checkData.found) {
      await selectScreenrecorderFile(checkData.full_path, checkData.filename, checkData.size_mb);
      addLogMessage(`⚡ Terdeteksi: '${file.name}' cocok dengan file lokal! Beralih otomatis ke Zero-Copy.`);
      return;
    }
  } catch (e) {
    // Fallback normal
  }

  selectedFile = file;
  isDirectMode = false;
  currentUploadId = null;
  document.getElementById("file-name").textContent = file.name;
  document.getElementById("file-size").textContent = (file.size / (1024 * 1024)).toFixed(1) + " MB (Upload Browser)";
  document.getElementById("file-direct-badge").classList.add("hidden");
  document.getElementById("file-info").classList.remove("hidden");
  document.getElementById("btn-process").disabled = false;

  // Setup Live Crop Video Preview
  const previewVideo = document.getElementById("preview-crop-video");
  const cropBox = document.getElementById("crop-preview-box");
  if (previewVideo && cropBox) {
    cropBox.classList.remove("hidden");
    const objectUrl = URL.createObjectURL(file);
    previewVideo.src = objectUrl;
    previewVideo.play().catch(() => {});
    setupCropSliders();
  }

  if (window.lucide) lucide.createIcons();
}

function setupCropSliders() {
  const zoomSlider = document.getElementById("zoom-slider");
  const yOffsetSlider = document.getElementById("y-offset-slider");

  if (zoomSlider) {
    zoomSlider.oninput = updateCropPreview;
  }
  if (yOffsetSlider) {
    yOffsetSlider.oninput = updateCropPreview;
  }
  updateCropPreview();
}

function updateCropPreview() {
  const zoomSlider = document.getElementById("zoom-slider");
  const yOffsetSlider = document.getElementById("y-offset-slider");
  const previewVideo = document.getElementById("preview-crop-video");
  const zoomLabel = document.getElementById("zoom-value-label");
  const yLabel = document.getElementById("y-offset-value-label");

  if (!zoomSlider || !previewVideo) return;

  const zoomPercent = parseInt(zoomSlider.value, 10);
  const zoomFactor = zoomPercent / 100;
  const yOffsetVal = parseInt(yOffsetSlider ? yOffsetSlider.value : 0, 10);

  if (zoomLabel) zoomLabel.textContent = `${zoomPercent}%`;
  if (yLabel) yLabel.textContent = `${yOffsetVal > 0 ? '+' : ''}${yOffsetVal}%`;

  previewVideo.style.transform = `scale(${zoomFactor}) translateY(${yOffsetVal * 0.65}%)`;
}

function resetCropSliders() {
  const zoomSlider = document.getElementById("zoom-slider");
  const yOffsetSlider = document.getElementById("y-offset-slider");
  if (zoomSlider) zoomSlider.value = "100";
  if (yOffsetSlider) yOffsetSlider.value = "0";
  updateCropPreview();
}

function clearSelectedFile() {
  selectedFile = null;
  currentUploadId = null;
  isDirectMode = false;
  document.getElementById("video-input").value = "";
  document.getElementById("file-info").classList.add("hidden");
  document.getElementById("file-direct-badge").classList.add("hidden");
  
  const cropBox = document.getElementById("crop-preview-box");
  const previewVideo = document.getElementById("preview-crop-video");
  if (cropBox) cropBox.classList.add("hidden");
  if (previewVideo) {
    previewVideo.pause();
    previewVideo.src = "";
  }
  resetCropSliders();
  document.getElementById("btn-process").disabled = true;
}

// --- SYSTEM STATUS & API KEYS ---
async function refreshSystemStatus() {
  try {
    const res = await fetch("/api/system/status");
    const data = await res.json();
    const statVerboo = document.getElementById("stat-verboo");
    if (statVerboo) statVerboo.textContent = `${data.verboo_active || 0} Aktif`;
    document.getElementById("stat-groq").textContent = `${data.groq_active} Aktif`;
    document.getElementById("stat-gemini").textContent = `${data.gemini_active} Aktif`;
  } catch (err) {
    console.error("Gagal memeriksa status sistem:", err);
  }
}

async function loadKeys() {
  try {
    const res = await fetch("/api/keys");
    keysCache = await res.json();
    renderKeysTable();
    updateKeyCounters();
  } catch (err) {
    console.error("Gagal memuat API keys:", err);
  }
}

function updateKeyCounters() {
  const verbooCount = keysCache.verboo ? keysCache.verboo.length : 0;
  const groqCount = keysCache.groq ? keysCache.groq.length : 0;
  const geminiCount = keysCache.gemini ? keysCache.gemini.length : 0;
  const countVerboo = document.getElementById("count-verboo-pill");
  if (countVerboo) countVerboo.textContent = verbooCount;
  document.getElementById("count-groq-pill").textContent = groqCount;
  document.getElementById("count-gemini-pill").textContent = geminiCount;
}

function renderKeysTable() {
  const container = document.getElementById("keys-table-body");
  const list = keysCache[activeKeyTab] || [];

  if (list.length === 0) {
    container.innerHTML = `
      <div class="p-6 text-center text-slate-500">
        Belum ada API Key untuk ${activeKeyTab.toUpperCase()}. Silakan tambahkan melalui form di atas.
      </div>
    `;
    return;
  }

  container.innerHTML = list.map(item => {
    let badgeClass = "bg-zinc-800 text-zinc-300 border-zinc-700";
    let statusText = "Aktif";
    if (item.status === "exhausted") {
      badgeClass = "bg-red-950/50 text-red-400 border-red-900/50";
      statusText = "Habis";
    }

    return `
      <div class="p-2.5 sm:p-3 flex items-center justify-between gap-3 hover:bg-zinc-900/40 transition-colors">
        <div class="flex items-center gap-2 overflow-hidden">
          <span class="font-mono text-xs text-zinc-300 truncate">${item.masked_key}</span>
          <span class="text-[10px] font-mono px-1.5 py-0.2 rounded border ${badgeClass}">
            ${statusText}
          </span>
          ${item.exhausted_reason ? `<span class="text-[10px] text-zinc-500 truncate max-w-[120px]" title="${item.exhausted_reason}">(${item.exhausted_reason})</span>` : ''}
        </div>
        <div class="flex items-center gap-1.5 shrink-0">
          ${item.status === "exhausted" ? `
            <button onclick="resetKey('${activeKeyTab}', '${item.id}')" title="Reset ke aktif" class="px-2 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-[10px] text-zinc-300 transition-colors">
              Reset
            </button>
          ` : ''}
          <button onclick="deleteKey('${activeKeyTab}', '${item.id}')" title="Hapus Key" class="p-1 hover:bg-zinc-800 rounded text-zinc-400 hover:text-red-400 transition-colors">
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
          </button>
        </div>
      </div>
    `;
  }).join("");
}

function openKeyModal() {
  document.getElementById("key-modal").classList.remove("hidden");
  loadKeys();
  if (window.lucide) lucide.createIcons();
}

function closeKeyModal() {
  document.getElementById("key-modal").classList.add("hidden");
  refreshSystemStatus();
}

function switchKeyTab(tab) {
  activeKeyTab = tab;
  const verbooBtn = document.getElementById("tab-verboo");
  const groqBtn = document.getElementById("tab-groq");
  const geminiBtn = document.getElementById("tab-gemini");
  const sourceBox = document.getElementById("key-source-box");
  const sourceTitle = document.getElementById("key-source-title");
  const sourceSub = document.getElementById("key-source-sub");
  const sourceLink = document.getElementById("key-source-link");
  const sourceBtnText = document.getElementById("key-source-btn-text");

  // Reset all tabs
  const inactiveClass = "py-2 px-3 border-b-2 border-transparent text-zinc-400 hover:text-zinc-200 flex items-center gap-1.5 font-medium";
  if (verbooBtn) verbooBtn.className = inactiveClass;
  if (groqBtn) groqBtn.className = inactiveClass;
  if (geminiBtn) geminiBtn.className = inactiveClass;

  if (tab === "verboo") {
    if (verbooBtn) verbooBtn.className = "py-2 px-3 border-b-2 border-white text-white flex items-center gap-1.5 font-semibold text-amber-400";
    document.getElementById("bulk-key-input").placeholder = "vbk_pro_xxxxxxxxxxxxxxxxxxxx";
    if (sourceBox) {
      sourceTitle.textContent = "AI Utama: Verboo Code Router";
      sourceSub.textContent = "Multi-Model AI (DeepSeek v4.1, Qwen 3.8, GLM 5.3, MiMo v2.5)";
      sourceLink.href = "https://code.verboo.ai";
      sourceBtnText.textContent = "Buka Verboo ↗";
    }
  } else if (tab === "groq") {
    if (groqBtn) groqBtn.className = "py-2 px-3 border-b-2 border-white text-white flex items-center gap-1.5 font-medium";
    document.getElementById("bulk-key-input").placeholder = "gsk_xxxxxxxxxxxxxx\ngsk_yyyyyyyyyyyyyy";
    if (sourceBox) {
      sourceTitle.textContent = "Belum punya Groq API Key?";
      sourceSub.textContent = "Digunakan untuk Speech-to-Text Whisper";
      sourceLink.href = "https://console.groq.com/keys";
      sourceBtnText.textContent = "Buka Groq ↗";
    }
  } else {
    if (geminiBtn) geminiBtn.className = "py-2 px-3 border-b-2 border-white text-white flex items-center gap-1.5 font-medium";
    document.getElementById("bulk-key-input").placeholder = "AQ.Abxxxxxxxxxxxxxx\nAIzaxxxxxxxxxxxxxx";
    if (sourceBox) {
      sourceTitle.textContent = "Google Gemini API Key (Cadangan)";
      sourceSub.textContent = "Digunakan otomatis bila AI utama Verboo kehabisan kuota";
      sourceLink.href = "https://aistudio.google.com/app/apikey";
      sourceBtnText.textContent = "Buka AI Studio ↗";
    }
  }
  renderKeysTable();
  if (window.lucide) lucide.createIcons();
}

async function submitNewKeys() {
  const textarea = document.getElementById("bulk-key-input");
  const raw = textarea.value.trim();
  if (!raw) return;

  try {
    const res = await fetch("/api/keys", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ provider: activeKeyTab, keys: raw })
    });
    const data = await res.json();
    textarea.value = "";
    loadKeys();
    refreshSystemStatus();
  } catch (err) {
    alert("Gagal menyimpan key: " + err);
  }
}

async function deleteKey(provider, keyId) {
  if (!confirm("Hapus API key ini?")) return;
  try {
    await fetch(`/api/keys/${provider}/${keyId}`, { method: "DELETE" });
    loadKeys();
    refreshSystemStatus();
  } catch (err) {
    alert("Gagal menghapus key: " + err);
  }
}

async function resetKey(provider, keyId) {
  try {
    await fetch(`/api/keys/${provider}/${keyId}/reset`, { method: "POST" });
    loadKeys();
    refreshSystemStatus();
  } catch (err) {
    alert("Gagal reset key: " + err);
  }
}

async function resetAllExhaustedKeys() {
  try {
    await fetch(`/api/keys/reset-all?provider=${activeKeyTab}`, { method: "POST" });
    loadKeys();
    refreshSystemStatus();
  } catch (err) {
    alert("Gagal reset: " + err);
  }
}

// --- PROCESSING WORKFLOW & VISUAL TRIMMER ---
let plannedClips = [];

function handleExecModeChange() {
  const mode = document.querySelector('input[name="exec-mode"]:checked')?.value || 'auto';
  const label = document.getElementById("btn-process-label");
  if (label) {
    if (mode === "review") {
      label.textContent = "Pratinjau & Rangkai Klip AI";
    } else {
      label.textContent = "Mulai Proses Video";
    }
  }
}

function handleResolutionChange() {
  const res = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
  const specLabel = document.getElementById("spec-resolution-label");
  const customBtnLabel = document.getElementById("btn-render-custom-label");

  if (specLabel) {
    if (res === "1080p") {
      specLabel.textContent = "Output: 1080×1920 (9:16)";
    } else {
      specLabel.textContent = "Output: 720×1280 (9:16)";
    }
  }

  if (customBtnLabel) {
    customBtnLabel.textContent = `Render Video Sekarang (${res} 30fps)`;
  }
}

async function startProcessing() {
  if (!currentUploadId && !selectedFile) {
    alert("Silakan pilih video rekaman layar terlebih dahulu.");
    return;
  }

  // Otomatis scroll mulus ke bagian progress agar langsung terlihat di layar / hp
  const progressSection = document.getElementById("progress-card") || document.getElementById("job-status-pill");
  if (progressSection) {
    progressSection.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  const execMode = document.querySelector('input[name="exec-mode"]:checked')?.value || 'auto';
  const visualToggleEl = document.getElementById('visual-ai-toggle');
  const visualAiEnabled = visualToggleEl 
    ? (visualToggleEl.type === 'checkbox' ? visualToggleEl.checked : visualToggleEl.value !== 'false') 
    : true;
  const btn = document.getElementById("btn-process");
  btn.disabled = true;

  document.getElementById("result-card").classList.add("hidden");

  try {
    // 1. Upload Video HANYA jika bukan direct mode dan belum ada upload_id
    if (!currentUploadId) {
      if (!selectedFile) {
        alert("Pilih file video terlebih dahulu.");
        btn.disabled = false;
        handleExecModeChange();
        return;
      }
      btn.innerHTML = `<svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-black" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path></svg> <span>MENGUPLOAD VIDEO...</span>`;
      updateJobPill("Mengupload...", "bg-amber-500/20 text-amber-300 border-amber-500/30");
      addLogMessage("Mengunggah file video ke server...");

      const formData = new FormData();
      formData.append("file", selectedFile);

      const uploadRes = await fetch("/api/upload", {
        method: "POST",
        body: formData
      });

      if (!uploadRes.ok) {
        const err = await uploadRes.json();
        throw new Error(err.detail || "Gagal mengupload video.");
      }

      const uploadData = await uploadRes.json();
      currentUploadId = uploadData.upload_id;
      addLogMessage(`Video berhasil diupload: ${uploadData.filename} (${uploadData.size_mb} MB)`);
    }

    const durationVal = document.getElementById("duration-select")?.value || "30-60";

    if (execMode === "review") {
      // MODE REVIEW & VISUAL TRIMMER
      btn.innerHTML = `<svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-black" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path></svg> <span>MENGANALISIS TRANSKRIP & VISUAL...</span>`;
      updateJobPill("Menganalisis Klip...", "bg-sky-500/20 text-sky-300 border-sky-500/30");
      addLogMessage("[AI Transkrip & Visual] Mengidentifikasi bagian penting dan memvalidasi demonstrasi produk...");

      const planForm = new FormData();
      planForm.append("upload_id", currentUploadId);
      planForm.append("duration_range", durationVal);
      planForm.append("enable_visual_ai", visualAiEnabled ? "true" : "false");

      const planRes = await fetch("/api/plan-clips", {
        method: "POST",
        body: planForm
      });

      if (!planRes.ok) {
        const err = await planRes.json();
        throw new Error(err.detail || "Gagal menganalisis klip.");
      }

      const planData = await planRes.json();
      addLogMessage(`[AI Visual] Berhasil merangkai ${planData.clips.length} klip dengan validasi demonstrasi produk tuntas.`);
      updateJobPill("Siap Di-review", "bg-emerald-500/20 text-emerald-300 border-emerald-500/30");
      
      renderTrimmerUI(planData.clips);
      const trimmerCard = document.getElementById("trimmer-card");
      trimmerCard.classList.remove("hidden");
      trimmerCard.scrollIntoView({ behavior: "smooth" });

      btn.disabled = false;
      handleExecModeChange();
      return;
    }

    // MODE 1-KLIK OTOMATIS PENUH
    const selectedRes = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
    btn.innerHTML = `<svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-black" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path></svg> <span>MEMPROSES VIDEO (${selectedRes.toUpperCase()} 30FPS)...</span>`;
    
    const effectiveWatermark = savedWatermark || (document.getElementById("watermark-input")?.value || "").trim();
    const zoomSlider = document.getElementById("zoom-slider");
    const yOffsetSlider = document.getElementById("y-offset-slider");
    const zoomVal = zoomSlider ? (parseFloat(zoomSlider.value) / 100).toFixed(2) : "1.00";
    const yOffsetVal = yOffsetSlider ? (parseFloat(yOffsetSlider.value) / 100).toFixed(2) : "0.00";

    const procForm = new FormData();
    procForm.append("upload_id", currentUploadId);
    procForm.append("zoom", zoomVal);
    procForm.append("y_offset", yOffsetVal);
    procForm.append("duration_range", durationVal);
    procForm.append("enable_visual_ai", visualAiEnabled ? "true" : "false");
    procForm.append("resolution", selectedRes);
    if (effectiveWatermark) {
      procForm.append("watermark", effectiveWatermark);
    }

    const procRes = await fetch("/api/process", {
      method: "POST",
      body: procForm
    });

    if (!procRes.ok) {
      const err = await procRes.json();
      throw new Error(err.detail || "Gagal memulai pemrosesan video.");
    }

    const procData = await procRes.json();
    currentJobId = procData.job_id;
    updateJobPill("Sedang Diproses", "bg-red-500/20 text-red-300 border-red-500/30");

    listenToJobEvents(currentJobId);

  } catch (err) {
    addLogMessage(`❌ ERROR: ${err.message}`);
    updateJobPill("Gagal", "bg-red-500/20 text-red-400 border-red-500/40");
    btn.disabled = false;
    handleExecModeChange();
    if (window.lucide) lucide.createIcons();
  }
}

function renderTrimmerUI(clips) {
  plannedClips = clips.map((c, i) => ({
    start_segment: c.clip_id || (i + 1),
    end_segment: c.clip_id || (i + 1),
    start: parseFloat(c.start),
    end: parseFloat(c.end),
    reason: c.kind || c.reason || 'body',
    text: c.text || '',
    visual_status: c.visual_status || 'Visual Tuntas',
    thumb_url: c.thumb_url || null
  }));

  const container = document.getElementById("trimmer-clips-list");
  if (!container) return;

  if (plannedClips.length === 0) {
    container.innerHTML = `<div class="p-4 text-center text-zinc-500 text-xs">Tidak ada klip yang terpilih.</div>`;
    updateTrimmerTotalDuration();
    return;
  }

  container.innerHTML = plannedClips.map((clip, idx) => {
    let kindBadge = "bg-zinc-800 text-zinc-300 border-zinc-700";
    let kindLabel = "DEMO PRODUK";
    const r = (clip.reason || '').toLowerCase();
    if (r.includes("hook")) {
      kindBadge = "bg-emerald-950/70 text-emerald-400 border-emerald-800/60";
      kindLabel = "HOOK PEMBUKA";
    } else if (r.includes("cta")) {
      kindBadge = "bg-amber-950/70 text-amber-400 border-amber-800/60";
      kindLabel = "CTA KERANJANG";
    }

    const dur = Math.max(0, clip.end - clip.start).toFixed(1);
    const thumbHtml = clip.thumb_url 
      ? `<img src="${clip.thumb_url}" alt="Frame ${idx+1}" class="w-16 h-24 object-cover rounded border border-zinc-700 shrink-0 shadow" />`
      : `<div class="w-16 h-24 bg-zinc-900 rounded border border-zinc-800 shrink-0 flex items-center justify-center text-[10px] text-zinc-500 font-mono">No Frame</div>`;

    return `
      <div class="p-3 bg-zinc-950 rounded-lg border border-zinc-800 flex gap-3 text-xs items-start hover:border-zinc-700 transition-colors">
        ${thumbHtml}
        <div class="flex-1 space-y-1.5 overflow-hidden">
          <div class="flex items-center justify-between gap-2">
            <div class="flex items-center gap-1.5 overflow-hidden">
              <span class="text-[10px] font-mono px-2 py-0.5 rounded border ${kindBadge} font-semibold uppercase">
                ${kindLabel}
              </span>
              <span class="text-[10px] font-mono text-emerald-400/90 bg-emerald-950/30 px-1.5 py-0.5 rounded truncate border border-emerald-900/40">
                ${clip.visual_status || 'Penjelasan Tuntas'}
              </span>
            </div>
            <button onclick="deleteTrimmerClip(${idx})" title="Hapus segmen ini" class="p-1 text-zinc-500 hover:text-red-400 rounded transition-colors">
              <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
            </button>
          </div>

          <p class="text-[11px] text-zinc-300 line-clamp-2 leading-relaxed italic">
            "${clip.text || 'Tanpa transkrip'}"
          </p>

          <!-- Range controls -->
          <div class="flex items-center gap-2 pt-1 font-mono text-[11px] text-zinc-400">
            <div class="flex items-center gap-1">
              <span>Mulai:</span>
              <input type="number" step="0.1" min="0" value="${clip.start}" onchange="updateClipTime(${idx}, 'start', this.value)" class="w-16 bg-zinc-900 border border-zinc-800 rounded px-1.5 py-0.5 text-white text-center focus:outline-none focus:border-zinc-600 font-mono" />
              <span>s</span>
            </div>
            <div class="flex items-center gap-1">
              <span>Selesai:</span>
              <input type="number" step="0.1" min="0" value="${clip.end}" onchange="updateClipTime(${idx}, 'end', this.value)" class="w-16 bg-zinc-900 border border-zinc-800 rounded px-1.5 py-0.5 text-white text-center focus:outline-none focus:border-zinc-600 font-mono" />
              <span>s</span>
            </div>
            <span class="text-zinc-500 text-[10px]">(${dur}s)</span>
            <button onclick="previewClipSegment(${clip.start}, ${clip.end})" title="Putar preview klip ini" class="ml-auto px-2 py-0.5 rounded bg-zinc-900 hover:bg-zinc-800 border border-zinc-800 text-[10px] text-zinc-300 flex items-center gap-1 transition-colors">
              <svg class="w-2.5 h-2.5 fill-current" viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg>
              <span>Preview</span>
            </button>
          </div>
        </div>
      </div>
    `;
  }).join("");

  updateTrimmerTotalDuration();
  if (window.lucide) lucide.createIcons();
}

function updateClipTime(idx, field, value) {
  const val = parseFloat(value);
  if (isNaN(val) || val < 0) return;
  if (!plannedClips[idx]) return;

  plannedClips[idx][field] = val;
  updateTrimmerTotalDuration();
}

function deleteTrimmerClip(idx) {
  plannedClips.splice(idx, 1);
  renderTrimmerUI(plannedClips);
}

function updateTrimmerTotalDuration() {
  const total = plannedClips.reduce((acc, c) => acc + Math.max(0, c.end - c.start), 0);
  const el = document.getElementById("trimmer-total-dur");
  if (el) {
    el.textContent = `${total.toFixed(1)} detik`;
  }
}

function previewClipSegment(start, end) {
  const previewVideo = document.getElementById("preview-crop-video");
  if (!previewVideo) return;
  previewVideo.currentTime = start;
  previewVideo.play().catch(() => {});
  
  const checkTime = () => {
    if (previewVideo.currentTime >= end) {
      previewVideo.pause();
      previewVideo.removeEventListener("timeupdate", checkTime);
    }
  };
  previewVideo.addEventListener("timeupdate", checkTime);
}

function cancelTrimmer() {
  document.getElementById("trimmer-card").classList.add("hidden");
}

async function submitCustomRender() {
  if (!currentUploadId || plannedClips.length === 0) {
    alert("Belum ada klip yang dipilih.");
    return;
  }

  const btn = document.getElementById("btn-render-custom");
  btn.disabled = true;
  btn.innerHTML = `<svg class="animate-spin -ml-1 mr-2 h-4 w-4 text-black" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z"></path></svg> <span>MEMPROSES RENDER...</span>`;

  try {
    const effectiveWatermark = savedWatermark || (document.getElementById("watermark-input")?.value || "").trim();
    const zoomSlider = document.getElementById("zoom-slider");
    const yOffsetSlider = document.getElementById("y-offset-slider");
    const zoomVal = zoomSlider ? parseFloat(zoomSlider.value) / 100 : 1.0;
    const yOffsetVal = yOffsetSlider ? parseFloat(yOffsetSlider.value) / 100 : 0.0;

    const selectedRes = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
    const payload = {
      upload_id: currentUploadId,
      clips: plannedClips,
      watermark: effectiveWatermark || null,
      zoom: zoomVal,
      y_offset: yOffsetVal,
      resolution: selectedRes
    };

    const res = await fetch("/api/render-custom", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload)
    });

    if (!res.ok) {
      const err = await res.json();
      throw new Error(err.detail || "Gagal render kustom.");
    }

    const data = await res.json();
    currentJobId = data.job_id;
    document.getElementById("trimmer-card").classList.add("hidden");
    const progressSection = document.getElementById("progress-card") || document.getElementById("job-status-pill");
    if (progressSection) {
      progressSection.scrollIntoView({ behavior: "smooth", block: "start" });
    }
    updateJobPill("Sedang Dirender", "bg-amber-500/20 text-amber-300 border-amber-500/30");
    addLogMessage(`[Render] Memulai pemotongan ${selectedRes.toUpperCase()} 30 FPS untuk klip kustom...`);

    listenToJobEvents(currentJobId);
  } catch (err) {
    alert("Gagal merender klip: " + err.message);
    btn.disabled = false;
    const currentRes = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
    btn.innerHTML = `<i data-lucide="film" class="w-4 h-4"></i> <span id="btn-render-custom-label">Render Video Sekarang (${currentRes} 30fps)</span>`;
    if (window.lucide) lucide.createIcons();
  }
}

function listenToJobEvents(jobId) {
  if (eventSource) {
    eventSource.close();
  }

  eventSource = new EventSource(`/api/jobs/${jobId}/stream`);

  eventSource.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      handleJobEvent(data);
    } catch (e) {
      console.error("Gagal parse event:", e);
    }
  };

  eventSource.onerror = () => {
    console.log("SSE connection error");
  };
}

function handleJobEvent(evt) {
  const { stage, percent, message, time } = evt;

  document.getElementById("progress-percent").textContent = `${percent}%`;
  document.getElementById("progress-bar-fill").style.width = `${percent}%`;
  document.getElementById("progress-stage-name").textContent = message;

  addLogMessage(`[${time || ''}] ${message}`);

  highlightStep(stage, message);

  if (stage === "COMPLETED") {
    if (eventSource) eventSource.close();
    const selectedRes = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
    updateJobPill(`Selesai (${selectedRes} 30fps)`, "bg-emerald-500/20 text-emerald-300 border-emerald-500/30");
    showResultCard();
    refreshSystemStatus();
    loadKeys();
  } else if (stage === "ERROR") {
    if (eventSource) eventSource.close();
    updateJobPill("Error", "bg-red-500/20 text-red-400 border-red-500/40");
    const btn = document.getElementById("btn-process");
    btn.disabled = false;
    handleExecModeChange();
    if (window.lucide) lucide.createIcons();
    refreshSystemStatus();
    loadKeys();
  }
}

function highlightStep(stage, message = "") {
  const steps = ["step-audio", "step-groq", "step-gemini", "step-visual", "step-render"];
  steps.forEach(s => {
    const el = document.getElementById(s);
    if (!el) return;
    el.className = "p-2 rounded bg-zinc-900 border border-zinc-800 opacity-40 transition-all";
  });

  if (stage === "COMPLETED") {
    steps.forEach(s => {
      const el = document.getElementById(s);
      if (el) el.className = "p-2 rounded bg-zinc-800 border border-emerald-500/50 opacity-100 transition-all text-emerald-300 font-semibold";
    });
    return;
  }

  let targetId = null;
  if (stage === "AUDIO_EXTRACT") {
    targetId = "step-audio";
  } else if (stage === "GROQ_TRANSCRIBE") {
    targetId = "step-groq";
  } else if (stage === "GEMINI_SELECT" || stage === "AI_SELECT") {
    if (message.toLowerCase().includes("visual") || message.toLowerCase().includes("kamera")) {
      targetId = "step-visual";
    } else {
      targetId = "step-gemini";
    }
  } else if (stage === "VIDEO_RENDER") {
    targetId = "step-render";
  }

  if (targetId) {
    const el = document.getElementById(targetId);
    if (el) {
      el.className = "p-2 rounded bg-zinc-800 border border-zinc-400 opacity-100 transition-all text-white font-medium";
    }
  }
}

function updateJobPill(text, classes) {
  const pill = document.getElementById("job-status-pill");
  if (!pill) return;
  pill.textContent = text;
  pill.className = `text-xs px-2 py-0.5 rounded font-mono border ${classes || 'bg-zinc-900 text-zinc-400 border-zinc-800'}`;
}

function addLogMessage(msg) {
  const terminal = document.getElementById("log-terminal");
  const p = document.createElement("div");
  p.className = "py-0.5";
  if (msg.includes("⚠️")) {
    p.className += " text-amber-400 font-semibold";
  } else if (msg.includes("❌")) {
    p.className += " text-red-400 font-bold";
  } else if (msg.includes("✅") || msg.includes("Selesai")) {
    p.className += " text-emerald-400 font-bold";
  } else if (msg.includes("AI Visual") || msg.includes("Visual")) {
    p.className += " text-sky-400";
  }
  p.textContent = msg;
  terminal.appendChild(p);
  terminal.scrollTop = terminal.scrollHeight;
}

function clearLogs() {
  document.getElementById("log-terminal").innerHTML = "";
}

function toggleLogTerminal() {
  const container = document.getElementById("log-terminal-container");
  const chevron = document.getElementById("log-chevron-icon");
  const clearBtn = document.getElementById("btn-clear-logs");
  const hint = document.getElementById("log-toggle-hint");
  const terminal = document.getElementById("log-terminal");
  if (!container) return;

  const isHidden = container.classList.contains("hidden");
  if (isHidden) {
    container.classList.remove("hidden");
    if (chevron) {
      chevron.classList.add("rotate-90");
    }
    if (clearBtn) clearBtn.classList.remove("hidden");
    if (hint) hint.textContent = "(klik untuk minimize)";
    if (terminal) terminal.scrollTop = terminal.scrollHeight;
  } else {
    container.classList.add("hidden");
    if (chevron) {
      chevron.classList.remove("rotate-90");
    }
    if (clearBtn) clearBtn.classList.add("hidden");
    if (hint) hint.textContent = "(klik untuk melihat detail)";
  }
}


function showResultCard() {
  const card = document.getElementById("result-card");
  card.classList.remove("hidden");

  const selectedRes = document.querySelector('input[name="export-res"]:checked')?.value || '720p';
  const titleEl = document.getElementById("result-status-title");
  if (titleEl) {
    titleEl.textContent = `Hasil Siap (${selectedRes.toUpperCase()} 30 FPS)`;
  }
  const descEl = document.getElementById("result-resolution-desc");
  if (descEl) {
    if (selectedRes === "1080p") {
      descEl.textContent = "Format vertikal 9:16 (1080×1920) Full HD tajam telah disesuaikan dan siap diupload ke TikTok atau Reels.";
    } else {
      descEl.textContent = "Format vertikal 9:16 (720×1280) telah disesuaikan dan siap diupload ke TikTok atau Reels.";
    }
  }

  const player = document.getElementById("result-video-player");
  const videoUrl = `/api/video/${currentJobId}`;
  player.src = videoUrl;

  const downloadBtn = document.getElementById("btn-download");
  downloadBtn.href = `/api/video/${currentJobId}?download=true`;

  const processBtn = document.getElementById("btn-process");
  processBtn.disabled = false;
  handleExecModeChange();

  if (window.lucide) lucide.createIcons();
}

function resetAppForNewVideo() {
  clearSelectedFile();
  document.getElementById("result-card").classList.add("hidden");
  document.getElementById("trimmer-card").classList.add("hidden");
  plannedClips = [];

  const player = document.getElementById("result-video-player");
  player.pause();
  player.src = "";
  
  document.getElementById("progress-percent").textContent = "0%";
  document.getElementById("progress-bar-fill").style.width = "0%";
  document.getElementById("progress-stage-name").textContent = "Siap memproses...";
  updateJobPill("Menunggu Video", "bg-zinc-900 text-zinc-400 border border-zinc-800");
  highlightStep(null);
  clearLogs();

  // Bersihkan sisa file sementara di server
  fetch("/api/cleanup", { method: "POST" }).catch(() => {});
}

async function deleteCurrentOutput() {
  if (!currentJobId) return;
  if (!confirm("Hapus file video ini dari folder outputs di server?")) return;

  try {
    const res = await fetch(`/api/outputs/${currentJobId}`, { method: "DELETE" });
    const data = await res.json();
    if (res.ok && data.status === "success") {
      addLogMessage(`🗑️ File video '${data.deleted_filename || 'output'}' (${data.freed_mb || '0'} MB) berhasil dihapus dari folder outputs.`);
      alert("Video berhasil dihapus dari server!");
      resetAppForNewVideo();
    } else {
      alert("File video sudah tidak ditemukan di server.");
      resetAppForNewVideo();
    }
  } catch (err) {
    alert("Gagal menghapus file: " + err.message);
  }
}

async function cleanupAllOutputs() {
  if (!confirm("Hapus SEMUA file video di folder outputs untuk menghemat ruang disk?")) return;

  try {
    const res = await fetch("/api/outputs/cleanup", { method: "POST" });
    const data = await res.json();
    if (res.ok && data.status === "success") {
      let msg = `Berhasil membersihkan ${data.deleted_count} video (${data.freed_mb} MB ruang dikosongkan).`;
      if (data.locked_count > 0) {
        msg += ` (${data.locked_count} file sedang dibuka aplikasi lain)`;
      }
      addLogMessage(`🧹 Pembersihan Outputs: ${msg}`);
      alert(msg);
      // Jika result card sedang tampil, reset
      const resultCard = document.getElementById("result-card");
      if (resultCard && !resultCard.classList.contains("hidden")) {
        resetAppForNewVideo();
      }
    } else {
      alert("Gagal membersihkan outputs: " + (data.message || "Unknown error"));
    }
  } catch (err) {
    alert("Gagal membersihkan outputs: " + err.message);
  }
}
