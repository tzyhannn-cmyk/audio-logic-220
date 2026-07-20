// ==========================================
// 1. VARIABEL GLOBAL & STATE
// ==========================================
let currentAudioUrl = "";
let currentVideoUrl = "";
let currentTitle = "";
let currentArtist = "";

// ==========================================
// 2. NAVIGASI TAB
// ==========================================
function switchTab(tabId, el) {
    document.querySelectorAll('.tab-content').forEach(t => t.style.display = 'none');
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    
    let targetTab = document.getElementById(tabId);
    if (targetTab) targetTab.style.display = 'block';
    if (el) el.classList.add('active');

    if (tabId === 'videoTab' && currentVideoUrl !== "") {
        if (window.AndroidBridge && typeof window.AndroidBridge.pauseAudioNative === 'function') {
            AndroidBridge.pauseAudioNative();
        }
        let vPlayer = document.getElementById("videoPlayer");
        if (vPlayer && vPlayer.src !== currentVideoUrl) {
            vPlayer.src = currentVideoUrl;
        }
    }
}

// ==========================================
// 3. FUNGSI PENCARIAN (GO-TUBE)
// ==========================================
function lakukanPencarian() {
    let queryInput = document.getElementById("txtCari");
    let query = queryInput ? queryInput.value.trim() : "";
    if (!query) return alert("Masukkan kata kunci pencarian!");

    let loadingContainer = document.getElementById("loadingContainer");
    let loadingText = document.getElementById("loadingText");
    let resultList = document.getElementById("searchResultList");

    if (loadingContainer) loadingContainer.style.display = "block";
    if (loadingText) loadingText.innerText = "Mencari video teratas...";
    if (resultList) resultList.innerHTML = "";

    if (window.AndroidBridge && typeof window.AndroidBridge.searchYouTube === 'function') {
        AndroidBridge.searchYouTube(query);
    } else {
        alert("AndroidBridge belum terhubung dengan aplikasi Android.");
        if (loadingContainer) loadingContainer.style.display = "none";
    }
}

// Callback jika pencarian berhasil
function onSearchSuccess(jsonString) {
    let loadingContainer = document.getElementById("loadingContainer");
    if (loadingContainer) loadingContainer.style.display = "none";

    let container = document.getElementById("searchResultList");
    if (!container) return;

    let videoList = [];
    try {
        videoList = typeof jsonString === 'object' ? jsonString : JSON.parse(jsonString);
    } catch (e) {
        console.error("Gagal parse hasil pencarian:", e);
        return alert("Format data pencarian dari Android tidak valid.");
    }

    if (videoList.length === 0) {
        container.innerHTML = "<p style='color:#aaa; font-size:14px; text-align:center;'>Video tidak ditemukan.</p>";
        return;
    }

    container.innerHTML = "";
    videoList.forEach(video => {
        let card = document.createElement("div");
        card.className = "video-card";
        card.innerHTML = `
            <img src="${video.thumbnail || ''}" alt="thumb">
            <div class="video-card-info">
                <h4>${video.title || 'Tanpa Judul'}</h4>
                <p>${video.uploader || 'Unknown'}</p>
            </div>
        `;

        card.onclick = function() {
            if (loadingContainer) loadingContainer.style.display = "block";
            let loadingText = document.getElementById("loadingText");
            if (loadingText) loadingText.innerText = "Mengekstrak audio...";
            
            if (window.AndroidBridge) {
                AndroidBridge.extractYouTube(video.url);
            }
        };

        container.appendChild(card);
    });
}

// Callback jika pencarian gagal
function onSearchFailed(error) {
    let loadingContainer = document.getElementById("loadingContainer");
    if (loadingContainer) loadingContainer.style.display = "none";
    alert("Pencarian Gagal dari Android: " + error); 
}

// ==========================================
// 4. FUNGSI EKSTRAKSI LINK (PASTE URL DIRECT)
// ==========================================
function lakukanEkstraksiUrl() {
    // Mencari elemen input link (sesuaikan id dengan HTML kamu jika berbeda)
    let urlInput = document.getElementById("txtUrlLink") || document.getElementById("txtUrl") || document.querySelector("input[type='text']");
    let url = urlInput ? urlInput.value.trim() : "";

    if (!url) return alert("Tempelkan (paste) link YouTube terlebih dahulu!");

    let loadingContainer = document.getElementById("loadingContainer");
    let loadingText = document.getElementById("loadingText");

    if (loadingContainer) loadingContainer.style.display = "block";
    if (loadingText) loadingText.innerText = "Mengekstrak link audio...";

    if (window.AndroidBridge && typeof window.AndroidBridge.extractYouTube === 'function') {
        AndroidBridge.extractYouTube(url);
    } else {
        alert("AndroidBridge belum terhubung.");
        if (loadingContainer) loadingContainer.style.display = "none";
    }
}

// Callback jika ekstraksi berhasil (Mendukung format JSON Object & String URL)
function onExtractionSuccess(jsonString) {
    let loadingContainer = document.getElementById("loadingContainer");
    if (loadingContainer) loadingContainer.style.display = "none";

    let data = {};
    try {
        data = typeof jsonString === 'object' ? jsonString : JSON.parse(jsonString);
    } catch (e) {
        // Fallback jika Android hanya mengirim string URL audio mentah
        data = {
            audioUrl: jsonString,
            videoUrl: "",
            title: "YouTube Audio",
            uploader: "GoTube",
            thumbnail: "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=150"
        };
    }

    currentAudioUrl = data.audioUrl || jsonString;
    currentVideoUrl = data.videoUrl || "";
    currentTitle = data.title || "YouTube Audio";
    currentArtist = data.uploader || "GoTube";

    let txtJudul = document.getElementById("txtJudul");
    if (txtJudul) txtJudul.innerText = currentTitle;

    let txtUploader = document.getElementById("txtUploader");
    if (txtUploader) txtUploader.innerText = currentArtist;

    let imgThumbnail = document.getElementById("imgThumbnail");
    if (imgThumbnail && data.thumbnail) imgThumbnail.src = data.thumbnail;

    let metaCard = document.getElementById("metaCard");
    if (metaCard) metaCard.style.display = "flex";

    // Pindah ke tab Audio Player (Tab ke-2)
    let tabBtns = document.querySelectorAll('.tab-btn');
    if (tabBtns.length > 1) {
        switchTab('audioTab', tabBtns[1]);
    }

    // Panggil pemutar audio native Android
    if (window.AndroidBridge && typeof window.AndroidBridge.playAudioNative === 'function') {
        AndroidBridge.playAudioNative(currentAudioUrl, currentTitle, currentArtist);
    }
}

// Callback jika ekstraksi gagal
function onExtractionFailed(error) {
    let loadingContainer = document.getElementById("loadingContainer");
    if (loadingContainer) loadingContainer.style.display = "none";
    alert("Ekstraksi Gagal dari Android: " + error);
}

// ==========================================
// 5. PENANGANAN FILE LOKAL
// ==========================================
function onLocalFileSelected(uri, name) {
    currentAudioUrl = uri;
    currentTitle = name;
    currentArtist = "Penyimpanan Internal";
    currentVideoUrl = "";

    let txtJudul = document.getElementById("txtJudul");
    if (txtJudul) txtJudul.innerText = name;

    let txtUploader = document.getElementById("txtUploader");
    if (txtUploader) txtUploader.innerText = "Lagu Lokal";

    let imgThumbnail = document.getElementById("imgThumbnail");
    if (imgThumbnail) imgThumbnail.src = "https://images.unsplash.com/photo-1470225620780-dba8ba36b745?w=150"; 

    let metaCard = document.getElementById("metaCard");
    if (metaCard) metaCard.style.display = "flex";

    let tabBtns = document.querySelectorAll('.tab-btn');
    if (tabBtns.length > 1) {
        switchTab('audioTab', tabBtns[1]);
    }

    if (window.AndroidBridge && typeof window.AndroidBridge.playAudioNative === 'function') {
        AndroidBridge.playAudioNative(currentAudioUrl, currentTitle, currentArtist);
    }
}
